"""The "No data" placeholders are missing resources, not a rendering bug (#64).

The firmware draws LVGL's "No / data" placeholder wherever an image fails to decode
(``lv_draw_img``, 0x000A1504 / 0x000A1524). In the emulator every one of them is an image
whose resource address lies outside the one resource image the companion app ships
(``Picture_P03B_NORM2_0.4.bin``, 0x0C780000 + 401,922 bytes), on NAND pages that were never
loaded: the watch's factory resources, which no file we have contains. This pins that, so a
future change that really breaks drawing shows up as a placeholder for an image the NAND
*does* have.

The boot animation is the clearest case: its 134 frames are 360x360 TRUE_COLOR images at
0x0288B517 + n * 0x3F484 (a 4-byte header and 259,200 bytes of pixels each), and all of
them are missing -- which is why it has always run black.

Run with: uv run normtest test_resources
"""
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import test_golden as g  # noqa: E402
from normplus.watch.fw.resources import MissingResources  # noqa: E402

M = 1_000_000
PAGE = 2048
RESOURCE_BASE = 0x0C780000
RESOURCE_END = RESOURCE_BASE + (g.RESOURCES.stat().st_size - 4)
ANIMATION_FIRST, FRAME = 0x0288B517, 0x3F484
#: An LVGL image header for a 360x360 TRUE_COLOR image, as the shipped resource blob's
#: first image carries and as every boot-animation frame does.
HEADER_360 = bytes.fromhex("04a0052d")
#: The watch's factory resources, read off the physical watch over the patched 0xEE
#: (#70) and kept in the private reference repo, which is where the vendor's material
#: lives. The tests above pin what the emulator does *without* them -- which is still
#: how it runs by default -- and the ones below what it does with them.
DUMP = g.REPO / "NORM/_nand"

_run = None


def run():
    """The bound watch booted to its face (radio off), with the monitor on."""
    global _run
    if _run is None:
        m, devices, touch = g.build("face_swipe", stimulus=False)
        missing = MissingResources(m)
        m.run(max_instructions=420 * M, slice_size=4 * M)
        _run = (m, devices, missing)
    return _run


def test_the_monitor_recognises_this_firmware():
    m, _, missing = run()
    assert missing.active


def test_every_placeholder_is_an_image_the_nand_does_not_have():
    _, devices, missing = run()
    nand = devices["nand"]
    assert missing.placeholders > 0
    for address in missing.missing:
        assert not RESOURCE_BASE <= address < RESOURCE_END, hex(address)
        # Blank where the image's header would be: never loaded, or only erased.
        page = nand.pages.get(address // PAGE)
        header = bytes(page[address % PAGE:address % PAGE + 4]) if page is not None else b"\xff" * 4
        assert header == b"\xff" * len(header), f"0x{address:08X} has data: {header.hex()}"


def test_the_boot_animation_is_134_missing_frames():
    _, _, missing = run()
    frames = {ANIMATION_FIRST + n * FRAME for n in range(134)}
    assert frames <= missing.missing, sorted(hex(a) for a in frames - missing.missing)[:5]


def test_the_summary_says_why():
    _, _, missing = run()
    text = missing.summary()
    assert "factory resources" in text and "0x0288B517" in text, text



# -- with the factory resources mounted (#65) ----------------------------------------

_with_dump = None


def dumped() -> list:
    """The dumped NAND blobs, if this machine has them."""
    return sorted(DUMP.glob("0x*.bin")) if DUMP.is_dir() else []


def with_resources():
    """A bound watch with the dumped factory resources in its NAND as well."""
    m, devices, _ = g.build("face_swipe", stimulus=False)
    for blob in dumped():
        data = blob.read_bytes()
        devices["nand"].load(data[4:], int.from_bytes(data[:4], "little"))
    return m, devices


def mounted():
    """...run, with the placeholder monitor watching."""
    global _with_dump
    if _with_dump is None:
        m, devices = with_resources()
        missing = MissingResources(m)
        m.run(max_instructions=420 * M, slice_size=4 * M)
        _with_dump = (m, devices, missing)
    return _with_dump


def test_the_dump_has_all_134_boot_animation_frames():
    """The frames that made the boot animation run black (#64, #65).

    Checked in the NAND rather than on the screen: each frame is a 360x360
    TRUE_COLOR image, so its first four bytes are the LVGL header, and an address
    the dump did not reach reads back erased.
    """
    if not dumped():
        print("   (skipped: NORM/_nand holds no dump on this machine)")
        return
    _, devices = with_resources()
    nand = devices["nand"]

    def header(address: int) -> bytes:
        page = nand.pages.get(address // PAGE)
        if page is None:
            return b"\xff" * 4
        return bytes(page[address % PAGE:address % PAGE + 4])

    absent = [n for n in range(134)
              if header(ANIMATION_FIRST + n * FRAME) != HEADER_360]
    assert not absent, f"frames without a 360x360 header: {absent[:8]}"


def test_with_the_resources_mounted_the_placeholders_are_gone():
    """What #65 asked for: the screens the emulator could not draw, drawn.

    The same workload as the tests above -- a bound watch swiped on its face --
    with the factory resources in the NAND. Every image it drew as "No data"
    before is an address the dump now has, so nothing should be left over.
    """
    if not dumped():
        print("   (skipped: NORM/_nand holds no dump on this machine)")
        return
    _, _, missing = mounted()
    assert missing.active
    assert not missing.missing,         f"still missing: {sorted(hex(a) for a in missing.missing)[:8]}"



# -- which blobs `normwatch boot` mounts (#71) ---------------------------------------

class _Args:
    """Just the attributes ``nand_blobs`` reads off an argparse namespace."""

    def __init__(self, **kw):
        self.nand = kw.get("nand")
        self.no_resources = kw.get("no_resources", False)
        self.no_factory_resources = kw.get("no_factory_resources", False)


def test_boot_mounts_the_factory_resources_by_default():
    """What #71 changed, and the two ways out of it.

    Without them most screens draw "No data" and the animation runs black, which
    is not what the watch does -- so they are the default. They cost ~22 s on a
    boot that plays the whole animation, which is why both opt-outs exist.
    """
    from normplus.watch.__main__ import DEFAULT_FACTORY_NAND, nand_blobs

    if not dumped():
        print("   (skipped: NORM/_nand holds no dump on this machine)")
        return
    blobs, factory = nand_blobs(_Args())
    assert factory and blobs, (factory, blobs)
    assert {b.name for b in blobs} == {b.name for b in dumped()}
    assert all(b.parent == DEFAULT_FACTORY_NAND for b in blobs)

    # ...the app's resource image alone, as the emulator ran until today
    blobs, factory = nand_blobs(_Args(no_factory_resources=True))
    assert not factory and not blobs

    # ...nothing at all
    blobs, factory = nand_blobs(_Args(no_resources=True))
    assert not factory and not blobs

    # ...and an explicit --nand replaces the default rather than adding to it, so
    # a dump under test is not quietly mixed with the watch's own resources.
    blobs, factory = nand_blobs(_Args(nand=[str(DUMP)]))
    assert not factory and blobs


def test_without_the_resources_on_disk_boot_falls_back_quietly():
    """They are in the private submodule, so a checkout without it still boots."""
    import normplus.watch.__main__ as cli

    was = cli.DEFAULT_FACTORY_NAND
    try:
        cli.DEFAULT_FACTORY_NAND = was.parent / "_nand-that-is-not-there"
        blobs, factory = cli.nand_blobs(_Args())
        assert not factory and not blobs
    finally:
        cli.DEFAULT_FACTORY_NAND = was


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
