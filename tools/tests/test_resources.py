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
