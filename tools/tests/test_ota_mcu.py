"""A main-firmware (type 1) OTA of the unmodified image, against the watch's own responder.

The rehearsal for #67: the image the companion app sends a Norm 2 as update type 1,
``Apollo3_P03B_NORM2_F0.2B01.bin``, sent the way the app sends it, so the update flow
is proven with nothing new in the image before a patched one exists (#68). What the
firmware does with it (``ew_mod_ota_protocol.c``, handler at 0x0003B798; the OTA task's
loop at 0x0005A224):

* The SET handler (0x0003B86A) keeps an address/length slot per type in the block at
  0x10011464 -- type 1's at +4/+8/+C -- and for types 1-3 the address is the SET
  header's, which for this image is its own first four bytes: 0x0FC00000. The constant
  appears nowhere in the firmware.
* On the SET the task erases eight 128 KB blocks from that address (0x0005A292-
  0x0005A2B2): 1 MB of staging, 0x0FC00000-0x0FCFFFFF, the most the application's
  internal flash could hold. Pages are programmed in place as they complete, exactly as
  for the resource partition -- the staging *is* the in-place write.
* The data goes in the same 200-byte pieces as type 4, but in 20-byte writes
  (``OtaApolloCommand.create``: 0x80 for the picture type only, 0x14 otherwise).

The staging half is all the emulator can prove. The copy into internal flash is done
by a first-stage bootloader in the low 128 KB that is not in the OTA image (and is
read-protected over 0xEE, #66), so nothing here runs it. What it is handed is pinned
below instead: the content, header first, at 0x0FC00000.

Default: a short session, two pages (about 20 s). With --full, every piece, then
CRC ([04 01]) and REBOOT ([05 01]), the whole staged region compared with the image,
and the record REBOOT leaves in internal flash (about 16 minutes: 40,000 20-byte
writes).

Run with:  uv run normtest ota_mcu [-- --full]
"""
import asyncio
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from normplus.watch.fw.devices import OTA_STAGING_ADDRESS
from normplus.watch.fw.image import PAYLOAD_OFFSET
from normplus.watch.fw.phone import (BOOT_ANIMATION_FRAMES, OTA_BT_PARAM, OTA_TYPE_MCU, SET,
                                     UPGRADE_MODE, EmulatedWatch, ack, apollo_crc, frame,
                                     ota_init, ota_pieces, ota_set_header, ota_write_size)

REPO = HERE.parents[1]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"
BOUND = HERE / "fixtures/bound-watch.zip"
RAW = IMAGE.read_bytes()
ADDRESS, CONTENT = RAW[:4], RAW[4:]
PAGE = 2048
FIRST_PAGE = OTA_STAGING_ADDRESS // PAGE
#: Eight 128 KB blocks: what a type-1 SET erases.
STAGING_SIZE = 8 * 128 * 1024
FULL = "--full" in sys.argv
#: Marks written into the NAND before the watch starts: one inside the staging
#: region (it must be erased by the SET) and one just past it (it must not).
INSIDE = OTA_STAGING_ADDRESS + STAGING_SIZE - PAGE
OUTSIDE = OTA_STAGING_ADDRESS + STAGING_SIZE
MARK = b"#67 " * (PAGE // 4)


# -- the frames ----------------------------------------------------------------

def test_the_image_targets_the_staging_area():
    assert int.from_bytes(ADDRESS, "little") == OTA_STAGING_ADDRESS
    assert len(CONTENT) <= STAGING_SIZE
    # The content is file[4:]: a 44-byte header -- link address 0x00020000, the
    # staging address again, the payload length -- then the payload that runs at
    # 0x00020000, starting with its vector table.
    link, staging, length = (int.from_bytes(CONTENT[i:i + 4], "little") for i in (0, 4, 8))
    assert (link, staging) == (0x00020000, OTA_STAGING_ADDRESS), (hex(link), hex(staging))
    assert length == len(CONTENT) - (PAYLOAD_OFFSET - 4), hex(length)


def test_the_set_header_is_the_one_for_this_image():
    # Address 0x0FC00000, 747,200 bytes, transport CRC 0x1979 (docs/firmware.md §2).
    assert ota_set_header(OTA_TYPE_MCU, ADDRESS, CONTENT) == bytes.fromhex(
        "02 01 00 00 c0 0f c0 66 0b 00 79 19 00 00 0a".replace(" ", ""))
    assert apollo_crc(CONTENT) == bytes.fromhex("79190000")
    assert ota_init(CONTENT) == bytes.fromhex("01c0660b00")


def test_the_pieces_and_writes_are_the_apps():
    pieces = ota_pieces(CONTENT)
    assert b"".join(pieces) == CONTENT
    assert len(pieces) == 4013, len(pieces)
    assert ota_write_size(OTA_TYPE_MCU) == 0x14


# -- against the firmware --------------------------------------------------------

_session = None


def session() -> dict:
    """Bound watch, on its face; UPGRADE_MODE, then the main-firmware update."""
    global _session
    if _session is not None:
        return _session
    watch = EmulatedWatch(IMAGE, RESOURCES, flash_state=BOUND)
    nand = watch.devices["nand"]
    nand.load(MARK, INSIDE)
    nand.load(MARK, OUTSIDE)
    watch.start()
    result = {}

    async def flow(phone):
        assert await phone.find()
        await phone.connect()
        await phone.pair()
        await phone.discover()
        await phone.listen()
        # Not during the boot animation (#57).
        while watch.display.frames <= BOOT_ANIMATION_FRAMES:
            await asyncio.sleep(0.1)
        await asyncio.sleep(2)
        result["upgrade"] = await phone.exchange(frame(UPGRADE_MODE, SET, b"\x00"))
        await phone.open_dfu()
        result["bt_param"] = await phone.dfu_command(OTA_BT_PARAM, timeout=0.5)
        result["init"] = await phone.dfu_command(ota_init(CONTENT))
        result["set"] = await phone.dfu_command(
            ota_set_header(OTA_TYPE_MCU, ADDRESS, CONTENT), timeout=20)
        pieces = ota_pieces(CONTENT)
        count = len(pieces) if FULL else 22
        size = ota_write_size(OTA_TYPE_MCU)
        result["pieces"] = [await phone.dfu_piece(p, write_size=size) for p in pieces[:count]]
        if FULL:
            result["crc"] = await phone.dfu_command(b"\x04", timeout=30)
            result["flash_before_reboot"] = set(watch.machine.flash_dirty)
            result["reboot"] = await phone.dfu_command(b"\x05", timeout=10)
            for _ in range(100):
                if watch.machine.reset_requested:
                    break
                await asyncio.sleep(0.1)
        await phone.disconnect()

    try:
        watch.drive(flow, timeout=1800 if FULL else 150)
    except Exception as exc:  # noqa: BLE001 - reported by the tests
        result["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        watch.stop()
    result["nand"] = nand
    result["machine"] = watch.machine
    _session = result
    return result


def test_upgrade_mode_init_and_a_type_1_set_are_accepted():
    r = session()
    assert "error" not in r, r["error"]
    assert r["upgrade"] == ack(UPGRADE_MODE), (r["upgrade"] or b"").hex(" ")
    assert r["bt_param"] is None, "the watch answered BT_PARAM; the app does not wait for it"
    assert r["init"] == b"\x01\x01", r["init"]
    assert r["set"] == b"\x02\x01", r["set"]
    assert not r["machine"].faults, list(r["machine"].faults)


def test_the_set_erases_one_megabyte_of_staging_and_nothing_past_it():
    nand = session()["nand"]
    assert INSIDE // PAGE not in nand.pages, "the last page of the staging region was not erased"
    assert bytes(nand.pages[OUTSIDE // PAGE][:PAGE]) == MARK, "the page past the staging region changed"


def test_each_piece_is_answered_with_the_running_count():
    r = session()
    total = 0
    for i, (piece, reply) in enumerate(zip(ota_pieces(CONTENT), r["pieces"])):
        total += len(piece)
        assert reply is not None, f"no answer to piece {i}"
        # 02 when a page completes (the task's event 0x20, 0x0005A46C); 04 for the
        # piece that ends the image on a short page (event 0x40, 0x0005A4BE).
        flag = 0x04 if total == len(CONTENT) else 0x02 if total % PAGE == 0 else 0x01
        assert reply[:3] == bytes([0x03, 0x01, flag]), (i, reply.hex(" "))
        assert int.from_bytes(reply[3:7], "little") == total, (i, reply.hex(" "))


def test_the_pages_are_staged_unchanged():
    r = session()
    nand = r["nand"]
    pages = (len(CONTENT) + PAGE - 1) // PAGE if FULL else 2
    for k in range(pages):
        want = CONTENT[k * PAGE:(k + 1) * PAGE]
        got = bytes(nand.pages.get(FIRST_PAGE + k, b"\xff" * 2112)[:len(want)])
        assert got == want, f"page {k} at 0x{(FIRST_PAGE + k) * PAGE:08X} differs"


def test_the_whole_update_is_accepted_and_the_watch_resets():
    if not FULL:
        return
    r = session()
    assert r["crc"] == b"\x04\x01", r["crc"]
    assert r["reboot"] == b"\x05\x01", r["reboot"]
    # Unlike a resource update, which stays up on "Upgrade Success": the REBOOT is a
    # SYSRESETREQ, and on the real watch what runs next is the bootloader.
    assert r["machine"].reset_requested, "the watch did not reset after REBOOT"
    assert not r["machine"].faults, list(r["machine"].faults)


def test_the_rest_of_the_staging_region_is_erased():
    if not FULL:
        return
    nand = session()["nand"]
    used = (len(CONTENT) + PAGE - 1) // PAGE
    tail = len(CONTENT) % PAGE
    # The last page is programmed whole from a page buffer that is not cleared:
    # past the image it holds the previous page's bytes at the same offsets.
    last = bytes(nand.pages[FIRST_PAGE + used - 1][:PAGE])
    assert last[tail:] == CONTENT[(used - 2) * PAGE + tail:(used - 1) * PAGE], \
        last[tail:tail + 32].hex(" ")
    left = [hex((FIRST_PAGE + k) * PAGE) for k in range(used, STAGING_SIZE // PAGE)
            if FIRST_PAGE + k in nand.pages]
    assert not left, f"pages past the image still hold data: {left[:5]}"


def test_the_reboot_leaves_the_image_header_at_0x1e000_for_the_bootloader():
    # What the bootloader is handed, besides the staged image: after REBOOT the task
    # reads the first 0x2C bytes back from the staging address, checks them, and
    # programs them into internal flash at 0x0001E000 (0x0005A538-0x0005A59C) -- the
    # image's own header with the staging address at +4. That page is above the
    # read-protected boot region, so 0xEE can read it on the physical watch (#66).
    if not FULL:
        return
    r = session()
    machine = r["machine"]
    written = sorted(machine.flash_dirty - r["flash_before_reboot"])
    assert written == [0x1E000 // 0x2000], [hex(p * 0x2000) for p in written]
    record = bytes(machine.uc.mem_read(0x1E000, 0x2000))
    assert record[:0x2C] == CONTENT[:0x2C], record[:0x2C].hex(" ")
    assert record[0x2C:] == b"\xff" * (0x2000 - 0x2C)
    assert not machine.flash_programs_without_erase


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
