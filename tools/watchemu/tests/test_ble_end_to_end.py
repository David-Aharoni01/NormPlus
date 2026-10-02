"""The emulated watch, found, paired with and queried over the air -- no hardware.

This is the whole of card #25 in one run. The firmware boots with a bumble
controller behind its NZ8801 seam; the Android emulator's netsim endpoint is
served on the same radio (``AndroidLink``); and a bumble *host* connects to
that endpoint the way the Android emulator does, then behaves like the
companion app: scan, connect, pair (Just Works, LE Secure Connections, bonding
-- what ``BondingPolicy`` asks for), discover services, send a battery CHECK
over the 0x6F protocol, and then *bind* the watch the way the companion app
does after a QR scan -- bindStart, setDateTime, bindEnd -- which is what takes
it out of first-run setup and onto the watch face. Every reply comes from the
watch's own firmware.

Three things this pins that were each found the hard way:

* ``LocalLink`` stamps LE data with the sender's *random* address. The watch
  uses its public one, so every ATT response it sent arrived from
  ``00:00:00:00:00:00`` and was dropped -- discovery timed out after the
  requests had visibly reached the watch. ``Air`` fixes that.
* ``LE_Connection_Update`` has no handler in bumble. The watch asks for new
  parameters right after pairing; the central's host turns that into this
  command, and with no answer its whole command queue stops -- the next thing
  it could not do was disconnect. ``VirtualController`` answers it.
* Pairing goes through the seam's own AES. The phone offers LE Secure
  Connections (AuthReq 0x09) and the watch answers *legacy* Just Works
  (Pairing Response ``02 03 00 01 10 02 01``: NoInputNoOutput, AuthReq 0x01 =
  bonding only), so the STK comes from c1/s1 -- five ``LE_Encrypt`` calls
  visible in the watch's HCI -- and the P-256 key the firmware asked for at
  init is never used. ``LE_Generate_DHKey`` stays implemented for a firmware
  that does turn SC on, and is unit-tested in test_ble_link.py.

Two more, from the bind:

* **A SET written to 8003 gets no acknowledgement; the same SET written to
  8001 does.** The firmware has one command table (0x000CCD60) but two
  dispatchers in front of it: the one behind 8001 (0x00036222) turns a
  handler's result into the generic ``6F 01 81 02 00 <cmd> <status>`` reply,
  the one behind 8003 (0x00036460) throws the result away. CHECKs answer
  either way because their handlers build the reply themselves. The
  companion app writes to 8001 (``AppsCommDevice.smali``), and so does
  ``:app``; ``normlink-cli`` (since removed) wrote to 8003, which is why it
  only ever saw CHECK replies.
* **bindStart opens a 300-frame window.** The 0x93 handler posts UI message
  0x0C, which opens ``ui_notify_pairing_dlg.c`` in its animating state; the
  dialog counts 300 frames (0x0007514C) and then reports failure ("Pairing
  Failed / Try again!", and ``93 01`` to the phone). bindEnd (0x94) posts
  0x0D, which is what the dialog is waiting for: "Pairing Success / Hooray!",
  then the watch face. The app's sequential send queue only gets bindEnd out
  in time because 8001 acknowledges bindStart at once.

And one from flash persistence (#41): **the watch keeps what it wrote.** The
first run's flash is saved (``flashstate``) and the watch is booted a second
time from it. It comes up past first-run setup without a bind, answers
checkInit with 1, and the phone encrypts the link with the keys it stored in
the first run, without pairing -- which only works because the firmware wrote
its half of the bond to 0xFE000 the moment the first link was encrypted.

The watch is paced against the wall clock (``realtime=True``) because a host
is answering it in real time. Runs in about 35 seconds: the bind has to wait
for the boot animation, since the pairing dialog needs the setup screen to
open on top of, and the restart is a second boot. Neither waits for the
emulator to catch up with the wall clock -- ``Air`` makes that unnecessary,
and waiting for it is what made this test fail on slow runs (#55): the watch
ran out of its budget mid-bind.

The watch and the phone are ``normwatch.fw.phone``'s ``EmulatedWatch`` and
``Phone`` -- the same code ``normwatch cmd`` runs -- with the phone going
through the netsim endpoint, which is the path the AVD takes.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_ble_end_to_end.py
"""
import asyncio
import hashlib
import sys
import tempfile
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw.blelink import OPCODE_LE_ENCRYPT, OPCODE_LE_GENERATE_DHKEY
from normwatch.fw.phone import (BOOT_ANIMATION_FRAMES, CHECK, SET, WATCH,
                                EmulatedWatch, datetime_payload, frame)

REPO = Path(__file__).resolve().parents[3]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"

#: Not the bridge's 8877, so this can run beside a live Android emulator.
PORT = 8898
#: Watch time the run may take; the host is done long before.
BUDGET_SECONDS = 60
#: Wall-clock seconds a phone flow may take before the run counts as hung.
PHONE_TIMEOUT_S = 150
#: Where each run leaves the watch's flash; the restart boots from it.
STATE = Path(tempfile.mkdtemp(prefix="watchemu-e2e-")) / "watch-flash.zip"
#: The 0x6F protocol's battery CHECK: [6F][08][70 CHECK][len 1][payload 00][8F].
#: The payload byte is required -- the watch ignores the command without it.
BATTERY_CHECK = bytes.fromhex("6f08700100008f")
#: The bind handshake, byte for byte what BindDevice.start6F sends for the
#: Norm 2 (BindStart.smali / DateTime.smali / BindEnd.smali; the same bytes
#: :protocol's BindStartCommand / DateTimeCommand / BindEndCommand build):
#: bindStart mode 2 = BIND_START_QR_CODE, then the clock, then bindEnd.
BIND_START = bytes.fromhex("6f9371010002 8f".replace(" ", ""))
#: 2026-09-16 08:03:00 UTC+3: [yLo yHi][mo][d][h][mi][s][0][reHome 0][tz+][3][0]
SET_DATETIME = bytes.fromhex("6f04710c00" "ea07" "09" "10" "08" "03" "00" "00" "00" "01" "03" "00" "8f")
BIND_END = bytes.fromhex("6f94710100018f")
#: 0x94 CHECK -- MBluetooth.checkInit; the reply's byte is the init flag.
CHECK_INIT = bytes.fromhex("6f94700100008f")
#: The generic acknowledgement: [6F][01 RESPONSE][81 SET_RESPONSE][02 00][cmd][status][8F].
def ack(cmd: int, status: int = 0) -> bytes:
    return bytes([0x6F, 0x01, 0x81, 0x02, 0x00, cmd, status, 0x8F])
#: The clock the bind sets, as a time rather than bytes.
BIND_TIME = datetime(2026, 9, 16, 8, 3, 0, tzinfo=timezone(timedelta(hours=3)))

_result = None
_restarted = None


def run_once():
    """Boot, pair, query and bind; cached."""
    global _result
    if _result is None:
        _result = boot_with_a_phone(first_run)
    return _result


def run_restarted():
    """The same watch switched off and on: a second boot from the flash the
    first run left, met by the same phone holding the keys it stored; cached."""
    global _restarted
    if _restarted is None:
        first = run_once()
        if first.get("keystore") is None or not STATE.exists():
            _restarted = {"error": "the first run left no bond or no flash state"}
        else:
            _restarted = boot_with_a_phone(again, flash_state=STATE,
                                           keystore=first["keystore"])
    return _restarted


def boot_with_a_phone(flow, *, flash_state=None, keystore=None):
    """Boot the watch, run ``flow(phone, watch, outcome)`` against it over netsim.

    With *flash_state* the watch starts from that saved flash. Either way its
    flash is saved to STATE once the CPU has stopped.
    """
    watch = EmulatedWatch(IMAGE, RESOURCES, address=WATCH, flash_state=flash_state,
                          netsim_port=PORT)
    watch.start(seconds=BUDGET_SECONDS)
    outcome = {}
    try:
        watch.drive(lambda phone: flow(phone, watch, outcome),
                    timeout=PHONE_TIMEOUT_S, keystore=keystore)
    except Exception as exc:  # noqa: BLE001 - the tests report it
        outcome["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        stopped = watch.stop(save_to=STATE)
    if not stopped:
        outcome.setdefault("error", "the watch's CPU did not stop")
    outcome["machine"] = watch.machine
    outcome["controller"] = watch.controller
    outcome["stats"] = watch.stats
    return outcome


def fingerprint(display) -> str:
    return hashlib.md5(bytes(display.framebuffer)).hexdigest()[:12]


def watch_seconds(watch) -> float:
    return watch.machine.cycles / watch.machine.CYCLES_PER_SECOND


async def settled_screen(watch, *, unlike=None, timeout: float = 60) -> str:
    """The screen once it has held for three seconds of *watch* time (and
    differs from *unlike*).

    Three seconds is longer than the bind's "Pairing Success", so a settled
    screen after the bind is the face. Watch time, not wall time: the watch
    is binding seconds behind the wall clock, and three wall seconds of a
    slow run can be less than the dialog's two.
    """
    last, since = None, watch_seconds(watch)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        await asyncio.sleep(0.1)
        now = fingerprint(watch.display)
        if now != last:
            last, since = now, watch_seconds(watch)
        elif watch_seconds(watch) - since >= 3 and (unlike is None or now != unlike):
            break
    return last


async def first_run(phone, watch, outcome: dict) -> None:
    found = await phone.find()
    outcome["seen"] = phone.seen
    if not found:
        return
    await phone.connect()
    outcome["connected"] = True
    await phone.pair()
    outcome["encrypted"] = phone.encrypted
    # The phone's half of the bond, for the restart.
    outcome["keystore"] = phone.keystore
    outcome["services"] = await phone.discover()
    await phone.listen()
    # What normlink-cli did: the frame to 8003, then [03] to 8002 to make
    # the watch process it, then the answer arrives as a notification.
    reply = await phone.exchange(BATTERY_CHECK, char="8003")
    outcome["replies"] = [reply] if reply else []

    # -- the bind ----------------------------------------------------------------
    await watch.past_boot_animation(timeout=70)
    outcome["frames_at_bind"] = watch.display.frames
    outcome["lag_at_bind"] = watch.machine.realtime_lag
    outcome["screen_before"] = fingerprint(watch.display)
    bind = {"init_before": await phone.exchange(CHECK_INIT)}
    bind.update(await phone.bind(clock=datetime_payload(BIND_TIME)))
    bind["screen_after"] = await settled_screen(watch, unlike=outcome["screen_before"])
    bind["init_after"] = await phone.exchange(CHECK_INIT)
    outcome["bind"] = bind

    await phone.disconnect()
    outcome["disconnected"] = True


async def again(phone, watch, outcome: dict) -> None:
    """The phone from the first run, back after the watch restarted."""
    if not await phone.find():
        return
    await phone.connect()
    # No pair(). The phone's keys are the first run's; the watch's
    # controller asks the firmware for its key for this EDIV/Rand, and it
    # has one only if its flash kept the bond. Without it the answer is
    # negative and this raises PIN or Key Missing.
    await phone.encrypt()
    outcome["encrypted"] = phone.encrypted
    outcome["paired_again"] = phone.paired
    await phone.discover()

    # Whatever the watch shows once the boot animation is over and the
    # screen has held for three seconds: a provisioned watch goes to its
    # face, a fresh one to "Select a Language".
    await watch.past_boot_animation(timeout=70)
    outcome["screen"] = await settled_screen(watch)
    outcome["frames"] = watch.display.frames

    await phone.listen()
    outcome["init"] = await phone.exchange(CHECK_INIT)
    await phone.disconnect()
    outcome["disconnected"] = True


def test_the_phone_sends_the_companion_apps_bytes():
    """The frames ``phone.py`` builds are the ones the smali and :protocol pin."""
    assert frame(0x08, CHECK, b"\x00") == BATTERY_CHECK
    assert frame(0x94, CHECK, b"\x00") == CHECK_INIT
    assert frame(0x93, SET, b"\x02") == BIND_START
    assert frame(0x94, SET, b"\x01") == BIND_END
    assert frame(0x04, SET, datetime_payload(BIND_TIME)) == SET_DATETIME, \
        frame(0x04, SET, datetime_payload(BIND_TIME)).hex(" ")


def test_the_watch_is_on_the_air_as_itself():
    r = run_once()
    assert "error" not in r, r["error"]
    match = [a for a in r["seen"] if a.startswith(WATCH)]
    assert match, f"the watch was not seen; saw {list(r['seen'])}"
    name, data = r["seen"][match[0]]
    # The name the QR screen shows, the address the seam was given, the
    # service the companion app filters on.
    assert name == "Norm2#00000", name
    assert bytes.fromhex("0303e7fe") in data, data.hex(" ")          # 0xFEE7
    assert bytes.fromhex(WATCH.replace(":", "")) in data, data.hex(" ")


def test_the_phone_connects_and_pairs():
    r = run_once()
    assert r.get("connected"), "connect() did not complete"
    assert r.get("encrypted"), "pairing did not leave the link encrypted"
    hci = r["controller"].hci_commands
    # Legacy Just Works: c1 twice (two encrypts each) and s1 once.
    assert hci.count(OPCODE_LE_ENCRYPT) >= 5, hci.count(OPCODE_LE_ENCRYPT)
    assert OPCODE_LE_GENERATE_DHKEY not in hci, (
        "the watch paired with Secure Connections -- this firmware never did "
        "(AuthReq 0x01 in its Pairing Response); if that changed, so did the firmware")
    assert r["controller"].unanswered == [], \
        [hex(o) for o in r["controller"].unanswered]
    # The watch's controller asked its host for the key once, for the STK,
    # and the firmware's answer matched the phone's.
    assert r["controller"].controller.ltk_answers == ["match"], \
        r["controller"].controller.ltk_answers


def test_the_firmware_serves_its_gatt_table():
    r = run_once()
    services = r.get("services") or {}
    assert services.get("6006") == ["8001", "8002", "8003", "8004"], services
    assert services.get("1530") == ["1531", "1532"], services       # Apollo DFU
    assert "FEE7" in services, services


def test_the_watch_answers_a_battery_check():
    r = run_once()
    replies = r.get("replies") or []
    assert replies, "no notification came back for the battery CHECK"
    frame = replies[0]
    # [6F][08][80 CHECK_RESPONSE][01 00][percent][8F]
    assert frame[:5] == bytes.fromhex("6f08800100") and frame[-1] == 0x8F, frame.hex(" ")
    assert 0 <= frame[5] <= 100, frame[5]
    assert r.get("disconnected"), "the host could not disconnect afterwards"


def test_the_bind_takes_the_watch_out_of_first_run_setup():
    r = run_once()
    assert r.get("frames_at_bind", 0) > BOOT_ANIMATION_FRAMES, r.get("frames_at_bind")
    b = r.get("bind") or {}
    # 8001 acknowledges every SET at once (the 8003 dispatcher would not, and
    # the pairing dialog would time out waiting for bindEnd).
    assert b.get("start") == ack(0x93), (b.get("start") or b"").hex(" ")
    assert b.get("datetime") == ack(0x04), (b.get("datetime") or b"").hex(" ")
    assert b.get("end") == ack(0x94), (b.get("end") or b"").hex(" ")
    # The watch was in setup before and is not after: the screen changed and
    # settled for longer than the two-second "Pairing Success" (the face), and
    # checkInit reports the flag the app polls for.
    assert b.get("screen_after") not in (None, r["screen_before"]), \
        (r["screen_before"], b.get("screen_after"))
    assert b.get("init_before") == bytes.fromhex("6f94800100008f"), \
        (b.get("init_before") or b"").hex(" ")
    assert b.get("init_after") == bytes.fromhex("6f94800100018f"), \
        (b.get("init_after") or b"").hex(" ")


def test_the_watch_comes_back_bound_after_a_restart():
    first, r = run_once(), run_restarted()
    assert "error" not in r, r["error"]
    # The phone's stored keys encrypted the link and nothing paired: the
    # firmware was asked for its key and had the right one, so it kept its
    # half of the bond (0xFE000) across the restart.
    assert r.get("encrypted"), "the restarted watch did not accept the stored keys"
    assert not r.get("paired_again"), "the phone had to pair again"
    assert r["controller"].controller.ltk_answers == ["match"], \
        r["controller"].controller.ltk_answers
    # No bind in this run, and the firmware says it is initialised...
    assert r.get("init") == bytes.fromhex("6f94800100018f"), (r.get("init") or b"").hex(" ")
    # ...and it did not stop at first-run setup.
    assert r.get("frames", 0) > BOOT_ANIMATION_FRAMES, r.get("frames")
    assert r.get("screen") not in (None, first.get("screen_before")), \
        ("still on the setup screen", r.get("screen"))
    assert not r["machine"].faults, list(r["machine"].faults)


def test_the_run_was_clean():
    r = run_once()
    assert not r["machine"].faults, list(r["machine"].faults)
    stats = r["stats"]
    assert stats is not None and stats.stop.kind == "stopped", \
        (stats.stop.kind, stats.stop.detail) if stats else "no stats"


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok   {name}")
            except AssertionError as exc:
                failures += 1
                print(f"FAIL {name}: {exc}")
    print("all passed" if not failures else f"{failures} failed")
    sys.exit(1 if failures else 0)
