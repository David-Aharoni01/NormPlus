"""Golden fingerprints: what the watch does over four fixed runs, recorded.

Every equivalence check made before this -- the native C block hook,
--idle-skip, the deadline quantum -- compared two settings on one tree, in a
script that was then thrown away. That cannot catch a change that moves both
settings at once, and it left nothing behind to run again. This records what
the watch does in ``golden.json`` and fails, field by field, when it stops
doing it. Run it before and after anything that touches the run loop, the
hooks, the clocks or a device model; it is in the ordinary test loop so that
nothing has to remember to.

The four workloads, each deterministic (two runs of each, same tree, give the
same fingerprint to the instruction):

``boot``        radio off, 445M: past the 134-frame boot animation to "Select
                a Language". The configuration the performance figures quote.
``radio``       the same with the BLE controller answering (the NZ8801 model):
                the BLEIF transport, the firmware download, HCI init, IRQ 12.
``language``    radio off, a tap on the first language row at 410M, 460M: the
                touch path into first-run setup's navigation.
``face_swipe``  radio off, started from ``fixtures/bound-watch.zip`` (a watch
                bound over BLE and saved with ``normwatch cmd ... --save``), so
                it boots to its face; ``--force-gestures`` and a right-to-left
                swipe at 420M open the activity page. The gesture recogniser.
                (A swipe during the boot animation, which is what this used to
                be, reaches no further than the GPIO count.)

And the one switch that claims to change nothing but speed, held to it at full
length: ``--idle-skip`` must reproduce ``boot`` exactly. With the radio up it
does not quite -- it is exactly one STIMER tick short from somewhere between
225M and 250M on, everything else identical (#54). That is pinned as it is, so
a fix shows up here and a bigger drift fails.

Regenerate after a change that is *meant* to move the watch, and read the diff:

    PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_golden.py --update

Regenerating the fixture changes ``face_swipe`` too (its bond and bind time are
whatever the BLE run produced), so the fixture is kept, not rebuilt per run.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_golden.py
"""
import hashlib
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw import flashstate
from normwatch.fw import image as image_mod
from normwatch.fw.bootrom import BootRom
from normwatch.fw.cortexm import exception_name
from normwatch.fw.devices import (attach_ble_controller, attach_motion_sensor,
                                  attach_mspi_devices, attach_pmu, attach_touch_panel)
from normwatch.fw.machine import Apollo3Machine
from normwatch.fw.patches import force_gestures

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"
GOLDEN = HERE / "golden.json"
BOUND = HERE / "fixtures/bound-watch.zip"

#: 48 MHz: instructions per millisecond of watch time.
MS = 48_000
#: The boot animation's frame count (+4) and current frame (+0x10).
ANIMATION = 0x10000114
#: LVGL's active screen object, and the gesture recogniser's flags (bit 6 =
#: gesture in progress; see README "Touch reaches the firmware").
LVGL_SCREEN = 0x10001710
GESTURE_FLAGS = 0x10001BFA


def quiet(*_a, **_k) -> None:
    pass


def tap(machine, touch, at: int, x: int, y: int, ms: int = 150) -> None:
    machine.schedule(at, lambda: touch.press(x, y), "tap")
    machine.schedule(at + ms * MS, touch.release, "lift")


def swipe(machine, touch, at: int, start, end, ms: int = 350, steps: int = 24) -> None:
    """A finger dragged *start* -> *end* over *ms*, in *steps* reports."""
    (x0, y0), (x1, y1) = start, end
    machine.schedule(at, lambda: touch.press(x0, y0), "swipe")
    for i in range(1, steps + 1):
        f = i / steps
        machine.schedule(at + int(ms * MS * f),
                         lambda x=x0 + (x1 - x0) * f, y=y0 + (y1 - y0) * f: touch.move(x, y))
    machine.schedule(at + (ms + 2) * MS, touch.release, "lift")


#: name -> how to run it. The rows of "Select a Language" are at y 205..274,
#: 295..364 and 385..454 (README); a tap at (180, 180) lands in the gap.
WORKLOADS = {
    "boot": dict(budget=445_000_000),
    "radio": dict(budget=445_000_000, radio=True),
    "language": dict(budget=460_000_000,
                     stimulus=lambda m, t: tap(m, t, 410_000_000, 180, 240)),
    "face_swipe": dict(budget=500_000_000, state=BOUND, gestures=True,
                       stimulus=lambda m, t: swipe(m, t, 420_000_000, (300, 180), (60, 180))),
}


def build(name: str, *, idle_skip: bool = False, stimulus: bool = True):
    """The workload's machine, ready to run: (machine, devices, touch panel)."""
    w = WORKLOADS[name]
    m = Apollo3Machine(image_mod.load(IMAGE), log=quiet, trace=None, fast_hook=True,
                       ble=w.get("radio", False), idle_skip=idle_skip)
    devices = attach_mspi_devices(m, resource_blob=RESOURCES, log=quiet)
    if w.get("state"):
        flashstate.load(w["state"], m, devices["nand"], log=quiet)
    touch = attach_touch_panel(m, log=quiet)
    attach_motion_sensor(m, log=quiet)
    attach_pmu(m, log=quiet)
    if w.get("radio"):
        attach_ble_controller(m, log=quiet)
    if w.get("gestures"):
        force_gestures(m, log=quiet)
    if stimulus and w.get("stimulus"):
        w["stimulus"](m, touch)
    return m, devices, touch


def run(name: str, *, idle_skip: bool = False) -> dict:
    m, devices, touch = build(name, idle_skip=idle_skip)
    stats = m.run(max_instructions=WORKLOADS[name]["budget"], slice_size=4_000_000)
    return fingerprint(m, devices, touch, stats)


def fingerprint(m, devices, touch, stats) -> dict:
    display = devices["display"]
    fb = bytes(display.framebuffer)
    read = lambda address, size: int.from_bytes(m.uc.mem_read(address, size), "little")
    return {
        "instructions": stats.instructions,
        "exceptions": {exception_name(k): v
                       for k, v in sorted(m.cortexm.exception_counts.items())},
        "frames": display.frames,
        "pixel_bytes": display.pixel_bytes,
        "lit_pixels": sum(1 for i in range(0, len(fb), 2) if fb[i] | fb[i + 1]),
        "screen": hashlib.md5(fb).hexdigest()[:16],
        "nand_pages_read": devices["nand"].pages_read,
        "animation": [read(ANIMATION + 4, 1), read(ANIMATION + 0x10, 4)],
        "lvgl_screen": f"0x{read(LVGL_SCREEN, 4):08X}",
        "gesture_flags": f"0x{read(GESTURE_FLAGS, 1):02X}",
        "touch_reports": touch.reports,
        "flash_pages_written": [f"0x{i * BootRom.PAGE_SIZE:05X}" for i in sorted(m.flash_dirty)],
        "faults": len(m.faults),
    }


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs() -> dict:
    return {"image_sha256": digest(IMAGE), "resources_sha256": digest(RESOURCES),
            "bound_watch_sha256": digest(BOUND)}


_golden = None


def golden() -> dict:
    global _golden
    if _golden is None:
        _golden = json.loads(GOLDEN.read_text(encoding="utf-8"))
        changed = [k for k, v in inputs().items() if _golden["inputs"].get(k) != v]
        assert not changed, (f"{', '.join(changed)} changed since golden.json was recorded: "
                             "every fingerprint moves with it. Re-record with --update.")
    return _golden


UPDATE = "PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_golden.py --update"


def differences(expected: dict, got: dict, *, ignore=()) -> list:
    out = []
    for key in sorted(set(expected) | set(got)):
        if key in ignore or expected.get(key) == got.get(key):
            continue
        out.append(f"{key}: {expected.get(key)} -> {got.get(key)}")
    return out


def check(name: str) -> None:
    diff = differences(golden()["workloads"][name], run(name))
    assert not diff, (f"{name} moved:\n    " + "\n    ".join(diff)
                      + f"\n  if that is intended: {UPDATE}")


def test_boot():
    check("boot")


def test_radio():
    check("radio")


def test_language():
    check("language")


def test_face_swipe():
    check("face_swipe")


def test_the_swipe_and_the_tap_actually_change_the_screen():
    # A workload whose stimulus does nothing pins nothing about input.
    w = golden()["workloads"]
    assert w["language"]["screen"] != w["boot"]["screen"]
    assert w["language"]["touch_reports"] > 0
    assert w["face_swipe"]["touch_reports"] > 10
    assert w["face_swipe"]["lit_pixels"] > 5 * w["boot"]["lit_pixels"], w["face_swipe"]["lit_pixels"]


def test_idle_skip_reproduces_the_boot_exactly():
    # It fast-forwards the idle task in fixed steps, so it may overshoot the
    # budget by part of one; nothing else may move.
    diff = differences(golden()["workloads"]["boot"], run("boot", idle_skip=True),
                       ignore={"instructions"})
    assert not diff, diff


def test_idle_skip_with_the_radio_is_one_tick_short_and_nothing_else():
    """#54: one STIMER interrupt goes missing between 225M and 250M, and the two
    runs are in lockstep before and after. If this now matches exactly, the tick
    has been found: drop the offset here and the note in the README."""
    expected = json.loads(json.dumps(golden()["workloads"]["radio"]))
    expected["exceptions"]["STIMER_CMPR0"] -= 1
    diff = differences(expected, run("radio", idle_skip=True), ignore={"instructions"})
    assert not diff, diff


def update() -> None:
    old = json.loads(GOLDEN.read_text(encoding="utf-8")) if GOLDEN.exists() else {}
    record = {"inputs": inputs(), "workloads": {}}
    for name in WORKLOADS:
        record["workloads"][name] = got = run(name)
        diff = differences(old.get("workloads", {}).get(name, {}), got)
        print(f"{name}: " + ("unchanged" if not diff else "\n    " + "\n    ".join(diff)))
    GOLDEN.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {GOLDEN}")


if __name__ == "__main__":
    if "--update" in sys.argv[1:]:
        update()
        sys.exit(0)
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
