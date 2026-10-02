"""Internal flash: what an erased page reads as, and what programming can do.

The watch keeps settings in the region below the application, which links at
0x20000. The emulator only ever loaded the application, so everything else read
back as zero -- but erased flash reads 0xFF, and firmware that checks for a
blank record cannot tell "erased" from "all zeros" if we hand it zeros.

Programming NOR flash can only clear bits. Modelling it as a plain overwrite
makes a missing page erase invisible here and a corrupt page on the watch.

Run with:  uv run normtest test_flash
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch.fw import image as image_mod
from normplus.watch.fw.bootrom import BootRom
from normplus.watch.fw.machine import Apollo3Machine

REPO = Path(__file__).resolve().parents[2]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"

#: The settings region the firmware reads at boot, and the page it sits in.
SETTINGS = 0x1E000

#: (instance, page, destination) triples taken from a real boot: every
#: nv_program_main destination landed exactly on a page a preceding
#: nv_page_erase had cleared.
OBSERVED_ERASES = [(0, 0x0E, 0x1C000), (0, 0x0C, 0x18000), (1, 0x3D, 0xFA000)]

_img = image_mod.load(IMAGE)


def machine():
    return Apollo3Machine(_img, log=lambda *a, **k: None, trace=None)


def test_the_application_is_where_it_links():
    m = machine()
    at_link = bytes(m.uc.mem_read(_img.link_address, 16))
    assert at_link == _img.payload[:16]


def test_flash_outside_the_application_reads_as_erased():
    m = machine()
    assert bytes(m.uc.mem_read(SETTINGS, 16)) == b"\xff" * 16
    # Below the application, where the bootloader and settings live.
    assert bytes(m.uc.mem_read(0x0000, 4)) == b"\xff" * 4
    # And above it.
    end = _img.link_address + len(_img.payload)
    assert bytes(m.uc.mem_read(end + 0x100, 4)) == b"\xff" * 4


def test_the_page_arithmetic_matches_the_boots_own_calls():
    for instance, page, expected in OBSERVED_ERASES:
        addr = instance * BootRom.INSTANCE_SIZE + page * BootRom.PAGE_SIZE
        assert addr == expected, (instance, page, hex(addr), hex(expected))


def test_erasing_a_page_clears_exactly_that_page():
    m = machine()
    m.write_flash(0x1C000, bytes(0x2000), erase=True)          # scribble
    assert bytes(m.uc.mem_read(0x1C000, 4)) == b"\x00" * 4
    m.bootrom._rom_nv_page_erase(0, 0, 0x0E)
    assert bytes(m.uc.mem_read(0x1C000, 4)) == b"\xff" * 4
    assert bytes(m.uc.mem_read(0x1DFFC, 4)) == b"\xff" * 4
    # The next page is untouched.
    assert bytes(m.uc.mem_read(0x1E000, 4)) == b"\xff" * 4


def test_programming_an_erased_page_stores_the_data():
    m = machine()
    m.bootrom._rom_nv_page_erase(0, 0, 0x0E)
    m.uc.mem_write(0x10020000, b"\x01\x02\x03\x04")
    m.bootrom._rom_nv_program_main(0, 0x10020000, 0x1C000, 1)
    assert bytes(m.uc.mem_read(0x1C000, 4)) == b"\x01\x02\x03\x04"
    assert m.flash_programs_without_erase == 0


def test_programming_can_only_clear_bits():
    m = machine()
    m.bootrom._rom_nv_page_erase(0, 0, 0x0E)
    m.uc.mem_write(0x10020000, b"\xf0\xf0\xf0\xf0")
    m.bootrom._rom_nv_program_main(0, 0x10020000, 0x1C000, 1)
    assert bytes(m.uc.mem_read(0x1C000, 4)) == b"\xf0" * 4
    # Programming again without erasing cannot set the low nibble back.
    m.uc.mem_write(0x10020000, b"\x0f\x0f\x0f\x0f")
    m.bootrom._rom_nv_program_main(0, 0x10020000, 0x1C000, 1)
    assert bytes(m.uc.mem_read(0x1C000, 4)) == b"\x00" * 4
    assert m.flash_programs_without_erase == 1, "the missed erase went unnoticed"


def test_a_write_outside_flash_is_refused_rather_than_applied():
    m = machine()
    m.uc.mem_write(0x10020000, b"\xaa\xaa\xaa\xaa")
    m.write_flash(0x10020000, b"\x00\x00\x00\x00")     # SRAM, not flash
    assert bytes(m.uc.mem_read(0x10020000, 4)) == b"\xaa" * 4, "the write landed"
    m.write_flash(0x40000000, b"\x00")                 # MMIO: must not raise


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
