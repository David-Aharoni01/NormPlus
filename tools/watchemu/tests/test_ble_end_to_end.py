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
  ``:app``; ``normlink-cli`` writes to 8003, which is why it only ever saw
  CHECK replies.
* **bindStart opens a 300-frame window.** The 0x93 handler posts UI message
  0x0C, which opens ``ui_notify_pairing_dlg.c`` in its animating state; the
  dialog counts 300 frames (0x0007514C) and then reports failure ("Pairing
  Failed / Try again!", and ``93 01`` to the phone). bindEnd (0x94) posts
  0x0D, which is what the dialog is waiting for: "Pairing Success / Hooray!",
  then the watch face. The app's sequential send queue only gets bindEnd out
  in time because 8001 acknowledges bindStart at once.

The watch is paced against the wall clock (``realtime=True``) because a host
is answering it in real time. Runs in about 30 seconds: the bind has to wait
for the boot animation, since the pairing dialog needs the setup screen to
open on top of.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_ble_end_to_end.py
"""
import asyncio
import hashlib
import sys
import threading
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw import image as image_mod
from normwatch.fw.blelink import (OPCODE_LE_ENCRYPT, OPCODE_LE_GENERATE_DHKEY,
                                  AndroidLink, BumbleController, Radio)
from normwatch.fw.devices import (attach_ble_controller, attach_motion_sensor,
                                  attach_mspi_devices, attach_pmu,
                                  attach_touch_panel)
from normwatch.fw.machine import Apollo3Machine

REPO = Path(__file__).resolve().parents[3]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"

WATCH = "4C:59:80:12:44:F1"
#: Not the bridge's 8877, so this can run beside a live Android emulator.
PORT = 8898
#: Watch time the run may take; the host is done long before.
BUDGET_SECONDS = 60
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
#: The boot animation is 134 frames; the first-run setup screen is what the
#: panel draws after it, and the pairing dialog opens on top of that.
BOOT_ANIMATION_FRAMES = 134

_result = None


def quiet(*a, **k):
    pass


def run_once():
    """Boot the watch on a thread and drive it from a bumble host; cached."""
    global _result
    if _result is not None:
        return _result

    img = image_mod.load(IMAGE)
    m = Apollo3Machine(img, log=quiet, trace=None, fast_hook=True, ble=True,
                       realtime=True)
    devices = attach_mspi_devices(m, resource_blob=RESOURCES, log=quiet)
    attach_touch_panel(m, log=quiet)
    attach_motion_sensor(m, log=quiet)
    attach_pmu(m, log=quiet)
    radio = Radio()
    controller = attach_ble_controller(
        m, BumbleController(WATCH, radio=radio, log=quiet), log=quiet)
    android = AndroidLink(radio, PORT, log=quiet)

    stats = {}

    def worker():
        stats["run"] = m.run(max_instructions=BUDGET_SECONDS * 48_000_000,
                             slice_size=4_000_000)

    thread = threading.Thread(target=worker, name="watch-cpu", daemon=True)
    thread.start()
    outcome = {}
    try:
        asyncio.run(phone(outcome, m, devices["display"]))
    except Exception as exc:  # noqa: BLE001 - the tests report it
        outcome["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        m.stop_requested = True
        thread.join(timeout=60)
        android.close()
        radio.close()
    outcome["machine"] = m
    outcome["controller"] = controller
    outcome["stats"] = stats.get("run")
    _result = outcome
    return outcome


def fingerprint(display) -> str:
    return hashlib.md5(bytes(display.framebuffer)).hexdigest()[:12]


async def phone(outcome: dict, machine, display) -> None:
    from bumble.device import Device, Peer
    from bumble.hci import Address
    from bumble.pairing import PairingConfig, PairingDelegate
    from bumble.transport import open_transport

    async with await open_transport(f"android-netsim:localhost:{PORT}") as (src, sink):
        device = Device.with_hci("phone", Address("F0:F1:F2:F3:F4:F5"), src, sink)
        await device.power_on()

        # Scan until the watch shows up (its radio is up at ~0.1s of watch time).
        seen = {}
        device.on("advertisement", lambda adv: seen.setdefault(
            str(adv.address), (adv.data.get(0x09), bytes(adv.data_bytes))))
        await device.start_scanning(legacy=True)
        for _ in range(100):
            if any(a.startswith(WATCH) for a in seen):
                break
            await asyncio.sleep(0.1)
        await device.stop_scanning()
        outcome["seen"] = seen
        if not any(a.startswith(WATCH) for a in seen):
            return

        device.pairing_config_factory = lambda conn: PairingConfig(
            sc=True, mitm=False, bonding=True,
            delegate=PairingDelegate(io_capability=PairingDelegate.NO_OUTPUT_NO_INPUT))
        connection = await device.connect(f"{WATCH}/P", timeout=10)
        outcome["connected"] = True
        await asyncio.wait_for(connection.pair(), 20)
        outcome["encrypted"] = connection.is_encrypted

        peer = Peer(connection)
        await asyncio.wait_for(peer.discover_all(), 30)
        outcome["services"] = {
            str(s.uuid)[-4:]: [str(c.uuid)[-4:] for c in s.characteristics]
            for s in peer.services}

        main = next(s for s in peer.services if str(s.uuid).endswith("6006"))
        char = {str(c.uuid)[-4:]: c for c in main.characteristics}
        replies = []
        got = asyncio.get_running_loop().create_future()

        def on_notify(value):
            replies.append(bytes(value))
            if not got.done():
                got.set_result(bytes(value))

        await peer.subscribe(char["8002"], on_notify)
        await peer.subscribe(char["8004"], on_notify)
        # What normlink-cli does: the frame to 8003, then [03] to 8002 to make
        # the watch process it, then the answer arrives as a notification.
        await peer.write_value(char["8003"], BATTERY_CHECK, with_response=True)
        await peer.write_value(char["8002"], b"\x03", with_response=False)
        try:
            await asyncio.wait_for(got, 10)
        except asyncio.TimeoutError:
            pass
        outcome["replies"] = replies

        # -- the bind ----------------------------------------------------------
        # The pairing dialog opens on top of the setup screen, so wait for the
        # boot animation to finish and the setup screen to be drawn -- and
        # then for the emulator to catch up with the wall clock. Drawing that
        # screen puts the watch behind real time, and while it is behind, the
        # firmware's few-millisecond window between handing over a reply and
        # clearing its command buffer (see ``Air``) is tens of milliseconds
        # of the phone's time, wider than the connection interval that keeps
        # a phone's next write out of it.
        for _ in range(400):
            if display.frames > BOOT_ANIMATION_FRAMES:
                break
            await asyncio.sleep(0.1)
        for _ in range(300):
            if machine.realtime_lag < 0.01:
                break
            await asyncio.sleep(0.1)
        outcome["frames_at_bind"] = display.frames
        outcome["lag_at_bind"] = machine.realtime_lag
        outcome["screen_before"] = fingerprint(display)

        async def exchange(frame: bytes, timeout: float = 10) -> bytes | None:
            """A frame to 8001 (the companion app's write characteristic) plus
            the [03] trigger; the next notification is the reply.

            Sent the moment the previous reply is in, on purpose: the firmware
            clears its 8001 buffer some milliseconds *after* handing over a
            reply, and a frame that lands in between is wiped and answered
            ``00 02``. ``Air`` keeps that from happening the way a radio does
            (one connection event per interval), and this is where it shows.
            """
            reply = asyncio.get_running_loop().create_future()
            waiting.append(reply)
            await peer.write_value(char["8001"], frame, with_response=True)
            await peer.write_value(char["8002"], b"\x03", with_response=False)
            try:
                return await asyncio.wait_for(reply, timeout)
            except asyncio.TimeoutError:
                return None

        waiting: list = []

        def on_bind_notify(value):
            for w in waiting:
                if not w.done():
                    w.set_result(bytes(value))
            waiting.clear()

        await peer.subscribe(char["8002"], on_bind_notify)
        bind = {}
        bind["init_before"] = await exchange(CHECK_INIT)
        bind["start"] = await exchange(BIND_START)
        bind["datetime"] = await exchange(SET_DATETIME)
        bind["end"] = await exchange(BIND_END)
        # "Pairing Success" shows for two seconds, then the watch face; wait
        # for a screen that holds longer than that.
        stable, last = 0, None
        for _ in range(300):
            await asyncio.sleep(0.1)
            now = fingerprint(display)
            stable = stable + 1 if now == last else 0
            last = now
            if stable >= 30 and now != outcome["screen_before"]:
                break
        bind["screen_after"] = last
        bind["init_after"] = await exchange(CHECK_INIT)
        outcome["bind"] = bind

        await connection.disconnect()
        outcome["disconnected"] = True


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
