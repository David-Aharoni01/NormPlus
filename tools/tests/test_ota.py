"""An OTA of the resource partition, against the watch's own OTA responder.

What the firmware does with the Apollo DFU protocol (ew_mod_ota_protocol.c,
handler at 0x0003B798), driven the way the companion app drives it -- see
README "Rehearsing an OTA":

* UPGRADE_MODE (0x0E) is a mode switch inside the application: acknowledged on
  8001, nothing resets. The application receives the update itself.
* The SET handler accepts update types 1-4 only. **The resource partition is
  type 4**, not the 8 that cn.appscomm.ota's getUpdateType (and
  ApolloOtaProtocol.kt) send: 8 is answered [02 00].
* A type-4 SET erases the live resource partition at 0x0C780000 at once, and
  each completed 2 KB page is programmed in place -- no staging, no bootloader.
* The data goes in 200-byte pieces (ten and a 48-byte one per page), each
  answered on 0x1531 with the running byte count; a page boundary is flagged
  [03 01 02 ...].

It also pins the SPI NAND model's write path, which this was the first thing
ever to use: a PROGRAM LOAD's data arrives in the transfer after its opcode.

Default: a short session, two pages (about 40 s). With --full, the whole blob,
then CRC ([04 01]) and REBOOT ([05 01]), and the partition compared page by
page with the file (about five minutes).

Run with:  uv run normtest test_ota [--full]
"""
import asyncio
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from normplus.watch.fw.devices import (CMD_PROGRAM_EXECUTE, CMD_PROGRAM_LOAD, CMD_WRITE_ENABLE,
                                  SpiNand)
from normplus.watch.fw.phone import (BOOT_ANIMATION_FRAMES, OTA_BT_PARAM, OTA_TYPE_RESOURCES,
                                SET, UPGRADE_MODE, EmulatedWatch, ack, apollo_crc, frame,
                                ota_init, ota_pieces, ota_set_header)

REPO = HERE.parents[1]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"
BOUND = HERE / "fixtures/bound-watch.zip"
BLOB = RESOURCES.read_bytes()
ADDRESS, CONTENT = BLOB[:4], BLOB[4:]
#: The resource partition, from the blob's own header: 0x0C780000.
FIRST_PAGE = int.from_bytes(ADDRESS, "little") // 2048
FULL = "--full" in sys.argv


def quiet(*_a, **_k):
    pass


# -- the frames ----------------------------------------------------------------

def test_the_set_header_is_what_the_firmware_accepts():
    # Byte for byte the header that got [02 01] -- and, with the whole blob
    # after it, [04 01] for its CRC.
    assert ota_set_header(OTA_TYPE_RESOURCES, ADDRESS, CONTENT) == bytes.fromhex(
        "02 04 00 00 78 0c 02 22 06 00 a3 8c 00 00 0a".replace(" ", ""))
    assert apollo_crc(CONTENT) == bytes.fromhex("a38c0000")
    assert ota_init(CONTENT) == bytes.fromhex("0102220600")


def test_the_pieces_are_cut_the_way_the_app_cuts_them():
    pieces = ota_pieces(CONTENT)
    assert b"".join(pieces) == CONTENT
    assert [len(p) for p in pieces[:11]] == [200] * 10 + [48]       # one 2 KB page
    assert len(pieces) == 2159, len(pieces)
    tail = len(CONTENT) % 2048
    assert sum(len(p) for p in pieces[-((tail + 199) // 200):]) == tail


# -- the NAND write path ---------------------------------------------------------

def test_a_program_load_takes_its_data_from_the_next_transfer():
    nand = SpiNand(log=quiet)
    data = bytes(range(256)) * 8
    nand.exchange(bytes([CMD_WRITE_ENABLE]), 0)
    nand.exchange(bytes([CMD_PROGRAM_LOAD, 0, 0]), 0)         # opcode and column...
    nand.exchange(data, 0)                                     # ...then the page, alone
    nand.exchange(bytes([CMD_WRITE_ENABLE]), 0)                # a command again
    assert nand.write_enabled, "the transfer after the data was not taken as a command"
    nand.exchange(bytes([CMD_PROGRAM_EXECUTE, 0, 0, 5]), 0)
    assert bytes(nand.pages[5][:2048]) == data
    # Data in the same transfer as the opcode still works, and is not followed
    # by a data phase.
    nand.exchange(bytes([CMD_WRITE_ENABLE]), 0)
    nand.exchange(bytes([CMD_PROGRAM_LOAD, 0, 0]) + b"\x5a" * 4, 0)
    nand.exchange(bytes([CMD_WRITE_ENABLE]), 0)
    nand.exchange(bytes([CMD_PROGRAM_EXECUTE, 0, 0, 6]), 0)
    assert bytes(nand.pages[6][:4]) == b"\x5a" * 4


# -- against the firmware --------------------------------------------------------------

_session = None


def session() -> dict:
    """Bound watch, on its face; UPGRADE_MODE, then a resource update."""
    global _session
    if _session is not None:
        return _session
    watch = EmulatedWatch(IMAGE, RESOURCES, flash_state=BOUND)
    watch.start()
    result = {}

    async def flow(phone):
        assert await phone.find()
        await phone.connect()
        await phone.pair()
        await phone.discover()
        await phone.listen()
        # Not during the boot animation: an OTA screen opened over it sends
        # the UI task into unbounded recursion (README).
        while watch.display.frames <= BOOT_ANIMATION_FRAMES:
            await asyncio.sleep(0.1)
        await asyncio.sleep(2)
        result["upgrade"] = await phone.exchange(frame(UPGRADE_MODE, SET, b"\x00"))
        await phone.open_dfu()
        result["bt_param"] = await phone.dfu_command(OTA_BT_PARAM, timeout=0.5)
        result["init"] = await phone.dfu_command(ota_init(CONTENT))
        result["set_8"] = await phone.dfu_command(ota_set_header(8, ADDRESS, CONTENT))
        result["init_again"] = await phone.dfu_command(ota_init(CONTENT))
        result["set_4"] = await phone.dfu_command(
            ota_set_header(OTA_TYPE_RESOURCES, ADDRESS, CONTENT))
        pieces = ota_pieces(CONTENT)
        count = len(pieces) if FULL else 22
        result["pieces"] = [await phone.dfu_piece(p) for p in pieces[:count]]
        if FULL:
            result["crc"] = await phone.dfu_command(b"\x04")
            result["reboot"] = await phone.dfu_command(b"\x05", timeout=10)
        await phone.disconnect()

    try:
        watch.drive(flow, timeout=900 if FULL else 150)
    except Exception as exc:  # noqa: BLE001 - reported by the tests
        result["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        watch.stop()
    result["nand"] = watch.devices["nand"]
    result["machine"] = watch.machine
    _session = result
    return result


def test_upgrade_mode_is_acknowledged_and_resets_nothing():
    r = session()
    assert "error" not in r, r["error"]
    assert r["upgrade"] == ack(UPGRADE_MODE), (r["upgrade"] or b"").hex(" ")
    assert not r["machine"].reset_requested
    assert not r["machine"].faults, list(r["machine"].faults)


def test_type_8_is_refused_and_type_4_accepted():
    r = session()
    assert r["bt_param"] is None, "the watch answered BT_PARAM; the app does not wait for it"
    assert r["init"] == b"\x01\x01", r["init"]
    assert r["set_8"] == b"\x02\x00", r["set_8"]
    assert r["init_again"] == b"\x01\x01", r["init_again"]
    assert r["set_4"] == b"\x02\x01", r["set_4"]


def test_each_piece_is_answered_with_the_running_count():
    r = session()
    total = 0
    for i, (piece, reply) in enumerate(zip(ota_pieces(CONTENT), r["pieces"])):
        total += len(piece)
        assert reply is not None, f"no answer to piece {i}"
        page_done = total % 2048 == 0
        assert reply[:3] == bytes([0x03, 0x01, 0x02 if page_done else 0x01]), (i, reply.hex(" "))
        assert int.from_bytes(reply[3:7], "little") == total, (i, reply.hex(" "))


def test_the_pages_land_in_the_resource_partition_unchanged():
    r = session()
    nand = r["nand"]
    pages = len(CONTENT) // 2048 + 1 if FULL else 2
    for k in range(pages):
        want = CONTENT[k * 2048:(k + 1) * 2048]
        got = bytes(nand.pages.get(FIRST_PAGE + k, b"\xff" * 2112)[:len(want)])
        assert got == want, f"page {k} at 0x{(FIRST_PAGE + k) * 2048:08X} differs"


def test_the_whole_update_is_accepted():
    if not FULL:
        return
    r = session()
    assert r["crc"] == b"\x04\x01", r["crc"]
    assert r["reboot"] == b"\x05\x01", r["reboot"]


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
