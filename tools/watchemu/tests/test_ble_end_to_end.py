"""The emulated watch, found, paired with and queried over the air -- no hardware.

This is the whole of card #25 in one run. The firmware boots with a bumble
controller behind its NZ8801 seam; the Android emulator's netsim endpoint is
served on the same radio (``AndroidLink``); and a bumble *host* connects to
that endpoint the way the Android emulator does, then behaves like the
companion app: scan, connect, pair (Just Works, LE Secure Connections, bonding
-- what ``BondingPolicy`` asks for), discover services, and send a battery
CHECK over the 0x6F protocol. The reply comes from the watch's own firmware.

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

The watch is paced against the wall clock (``realtime=True``) because a host
is answering it in real time. Runs in about 10 seconds.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_ble_end_to_end.py
"""
import asyncio
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
    attach_mspi_devices(m, resource_blob=RESOURCES, log=quiet)
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
        asyncio.run(phone(outcome))
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


async def phone(outcome: dict) -> None:
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
        await connection.disconnect()
        outcome["disconnected"] = True


def test_the_watch_is_on_the_air_as_itself():
    r = run_once()
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
