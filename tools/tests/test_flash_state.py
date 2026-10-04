"""--flash-state: what the watch writes to its flash survives into the next run.

These are the fast checks on the file itself -- what is kept, what is refused,
and that a patch never leaks into it. That a bound watch really comes back
bound (setup skipped, the phone's stored keys accepted) is in
test_ble_end_to_end.py, because it takes a phone to show it.

Writes go through the boot ROM helpers, the path the firmware itself uses.

Run with:  uv run normtest test_flash_state
"""
import json
import sys
import tempfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch.fw import flashstate
from normplus.watch.fw import image as image_mod
from normplus.watch.fw.devices import (CMD_BLOCK_ERASE, CMD_PROGRAM_EXECUTE,
                                  CMD_PROGRAM_LOAD, CMD_WRITE_ENABLE, SpiNand)
from normplus.watch.fw.machine import Apollo3Machine

REPO = Path(__file__).resolve().parents[2]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"

#: The settings page the bind flips (byte 0, ff -> 01) and the bond page,
#: both seen being written in a boot + bond + bind.
SETTINGS = 0xFA000
BOND = 0xFE000
SCRATCH = 0x10020000  # SRAM the ROM program helper copies from

_img = image_mod.load(IMAGE)
_tmp = Path(tempfile.mkdtemp(prefix="flashstate-"))


def quiet(*a, **k):
    pass


def machine():
    return Apollo3Machine(_img, log=quiet, trace=None)


def program(m, address, data):
    """Erase the page and program *data* into it, as the firmware does."""
    rom = m.bootrom
    instance, offset = divmod(address, rom.INSTANCE_SIZE)
    rom._rom_nv_page_erase(0, instance, offset // rom.PAGE_SIZE)
    data = data + b"\xff" * (-len(data) % 4)
    m.uc.mem_write(SCRATCH, data)
    rom._rom_nv_program_main(0, SCRATCH, address, len(data) // 4)


def erase(m, address):
    rom = m.bootrom
    instance, offset = divmod(address, rom.INSTANCE_SIZE)
    rom._rom_nv_page_erase(0, instance, offset // rom.PAGE_SIZE)


def flash(m, address, n=8):
    return bytes(m.uc.mem_read(address, n))


def test_what_the_firmware_wrote_comes_back():
    path = _tmp / "roundtrip.zip"
    a = machine()
    program(a, SETTINGS, b"\x01")
    program(a, BOND, b"\x01\xee\xdd\xcc\xbb\xaa")
    flashstate.save(path, a)

    b = machine()
    assert flash(b, SETTINGS, 1) == b"\xff", "a fresh machine is not fresh"
    flashstate.load(path, b, log=quiet)
    assert flash(b, SETTINGS, 1) == b"\x01"
    assert flash(b, BOND, 6) == b"\x01\xee\xdd\xcc\xbb\xaa"
    assert flash(b, SETTINGS, 0x2000) == flash(a, SETTINGS, 0x2000), "page differs"


def test_only_written_pages_are_stored():
    path = _tmp / "sparse.zip"
    a = machine()
    program(a, SETTINGS, b"\x01")
    manifest = flashstate.save(path, a)
    assert manifest["flash_pages"] == [f"0x{SETTINGS:08X}"], manifest["flash_pages"]
    with zipfile.ZipFile(path) as z:
        assert sorted(z.namelist()) == ["flash/0x000FA000.bin", "manifest.json"]


def test_a_restored_page_is_kept_even_if_this_run_never_touches_it():
    first, second = _tmp / "carry1.zip", _tmp / "carry2.zip"
    a = machine()
    program(a, BOND, b"\x01\x02")
    flashstate.save(first, a)

    b = machine()
    flashstate.load(first, b, log=quiet)
    program(b, SETTINGS, b"\x01")       # this run writes something else
    flashstate.save(second, b)

    c = machine()
    flashstate.load(second, c, log=quiet)
    assert flash(c, BOND, 2) == b"\x01\x02", "the bond was dropped by a run that did not write it"
    assert flash(c, SETTINGS, 1) == b"\x01"


def test_a_page_the_firmware_erased_stays_erased():
    first, second = _tmp / "erase1.zip", _tmp / "erase2.zip"
    a = machine()
    program(a, BOND, b"\x01\x02")
    flashstate.save(first, a)

    b = machine()
    flashstate.load(first, b, log=quiet)
    erase(b, BOND)                      # e.g. the watch forgetting its bond
    flashstate.save(second, b)

    c = machine()
    flashstate.load(second, c, log=quiet)
    assert flash(c, BOND, 2) == b"\xff\xff", "an erased page came back"


def test_a_patch_is_not_saved():
    # A write with persist=False is a patch on top of the watch, not the watch writing
    # its flash, and must not reach a state file that a later, unpatched run loads. It
    # replaces the bytes (erase=True): a plain program ANDs into them, as NOR flash does.
    path, at = _tmp / "patched.zip", 0x000842A4
    a = machine()
    a.write_flash(at, b"\x00\xbf\x00\xbf", erase=True, persist=False)
    assert flash(a, at, 4) == b"\x00\xbf\x00\xbf"
    flashstate.save(path, a)
    b = machine()
    flashstate.load(path, b, log=quiet)
    assert flash(b, at, 4) == flash(machine(), at, 4), "a patch leaked into the state file"


def test_a_state_from_another_image_is_refused():
    path = _tmp / "other.zip"
    a = machine()
    program(a, SETTINGS, b"\x01")
    flashstate.save(path, a)
    # Rewrite the manifest as if it had been saved against another build.
    with zipfile.ZipFile(path) as z:
        files = {n: z.read(n) for n in z.namelist()}
    manifest = json.loads(files["manifest.json"])
    manifest["image_sha256"] = "0" * 64
    files["manifest.json"] = json.dumps(manifest).encode()
    with zipfile.ZipFile(path, "w") as z:
        for n, data in files.items():
            z.writestr(n, data)

    b = machine()
    try:
        flashstate.load(path, b, log=quiet)
    except flashstate.FlashStateMismatch as exc:
        assert "different firmware image" in str(exc), exc
    else:
        raise AssertionError("a state from another image was loaded")
    assert flash(b, SETTINGS, 1) == b"\xff", "a refused state still wrote flash"


def nand_program(nand, page, data):
    nand._command(CMD_WRITE_ENABLE, b"")
    nand._command(CMD_PROGRAM_LOAD, b"\x00\x00" + data)
    nand._command(CMD_PROGRAM_EXECUTE, page.to_bytes(3, "big"))


def test_nand_pages_come_back_and_erased_blocks_stay_erased():
    path = _tmp / "nand.zip"
    a, nand = machine(), SpiNand(log=quiet)
    nand.load(b"\x11" * nand.page_size * 2, 0)      # stands in for the resource blob
    nand_program(nand, 100, b"staged")
    nand._command(CMD_WRITE_ENABLE, b"")
    nand._command(CMD_BLOCK_ERASE, (0).to_bytes(3, "big"))   # block 0: pages 0..63
    flashstate.save(path, a, nand)

    b, fresh = machine(), SpiNand(log=quiet)
    fresh.load(b"\x11" * fresh.page_size * 2, 0)
    assert 0 in fresh.pages, "the blob did not load"
    flashstate.load(path, b, fresh, log=quiet)
    assert bytes(fresh.pages[100][:6]) == b"staged"
    assert 0 not in fresh.pages and 1 not in fresh.pages, "the erased block came back"


def test_a_state_over_another_resource_blob_is_refused():
    path = _tmp / "blob.zip"
    a, nand = machine(), SpiNand(log=quiet)
    nand.load(b"\x11" * nand.page_size, 0)
    nand_program(nand, 100, b"staged")
    flashstate.save(path, a, nand)

    b, other = machine(), SpiNand(log=quiet)
    other.load(b"\x22" * other.page_size, 0)
    try:
        flashstate.load(path, b, other, log=quiet)
    except flashstate.FlashStateMismatch as exc:
        assert "different NAND" in str(exc), exc
    else:
        raise AssertionError("a state over another resource blob was loaded")


def test_saving_over_a_state_leaves_no_temporary_behind():
    path = _tmp / "replace.zip"
    a = machine()
    program(a, SETTINGS, b"\x01")
    flashstate.save(path, a)
    program(a, SETTINGS, b"\x00")
    flashstate.save(path, a)
    assert not path.with_name(path.name + ".tmp").exists()
    b = machine()
    flashstate.load(path, b, log=quiet)
    assert flash(b, SETTINGS, 1) == b"\x00", "the second save did not replace the first"


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
