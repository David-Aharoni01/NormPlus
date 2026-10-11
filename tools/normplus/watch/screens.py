"""Screenshots of the watch's own screens, for the app (#114).

`normwatch screens` restores a machine saved past the boot (`boot --no-ble --flash-state
BOUND --save-state SNAP`) and saves the face, then what each of N right-to-left swipes
shows ("next-NN.png") and, from the face again, what each of N left-to-right swipes shows
("prev-NN.png"). It names nothing: the firmware does not say which page is on the screen,
and the heart-rate page keeps a swipe for itself (a swipe on it restarts the measurement),
so the pages beyond it are reached from the other side. Which file is which page type is
written by hand next to them (NORM/_screens/pages.json).

The screenshots are the vendor's screens: they go to the private NORM submodule, never
into this repository (CLAUDE.md).

Two traps found making it:
- The machine must be set up as `boot` sets it up, the PMU above all: attached with its
  defaults the battery reads empty, and the first touch wakes a watch that shuts itself
  down -- the glowing ring is its shutdown animation, not a page.
- With the factory resources mounted the boot takes ~40 s of watch time, so a swipe at the
  golden workloads' 420M lands in the boot. Hence starting from a saved machine.
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from .fw import snapshot
from .fw.devices import (attach_heart_rate_sensor, attach_motion_sensor, attach_mspi_devices,
                         attach_pmu, attach_touch_panel)
from .fw.machine import Apollo3Machine

#: Watch-time instructions per millisecond.
MS = Apollo3Machine.CYCLES_PER_SECOND // 1000
#: A page has settled this long after a swipe (its intro animation done).
SETTLE_MS = 4_000
#: The swipe: across the middle of the 360x360 display, as test_golden's face_swipe.
FROM_RIGHT = ((300, 180), (60, 180))
FROM_LEFT = ((60, 180), (300, 180))


def _machine(image, resources: Path, nand_blobs: list[Path], log):
    """A machine set up exactly as `boot --no-ble` sets one up, for `snapshot.restore`."""
    machine = Apollo3Machine(image, log=log, trace=None, fast_hook=True, ble=False)
    devices = attach_mspi_devices(machine, resource_blob=resources, log=log)
    for blob in nand_blobs:
        data = blob.read_bytes()
        devices["nand"].load(data[4:], int.from_bytes(data[:4], "little"))
    touch = attach_touch_panel(machine, log=log)
    attach_motion_sensor(machine, log=log)
    attach_heart_rate_sensor(machine, log=log)
    attach_pmu(machine, percent=80.0, charging=False, charger_status=None, log=log)
    return machine, devices, touch


def _run(machine, ms: int) -> None:
    # A resumed run's budget is the total since the boot, not more on top.
    machine.run(max_instructions=machine._instructions + ms * MS, slice_size=4_000_000, resume=True)


def _swipe(machine, touch, way, ms: int = 350, steps: int = 24) -> None:
    (x0, y0), (x1, y1) = way
    start = machine._instructions + 10 * MS
    machine.schedule(start, lambda: touch.press(x0, y0), "swipe")
    for i in range(1, steps + 1):
        f = i / steps
        machine.schedule(start + int(ms * MS * f), lambda x=x0 + (x1 - x0) * f, y=y0 + (y1 - y0) * f: touch.move(x, y))
    machine.schedule(start + (ms + 2) * MS, touch.release, "lift")


def capture(image, state: Path, out: Path, *, resources: Path, nand_blobs: list[Path], swipes: int, log) -> dict:
    """Saves face.png, next-NN.png and prev-NN.png in *out*, and screens.json describing them."""
    out.mkdir(parents=True, exist_ok=True)
    shots: dict[str, str] = {}

    def save(display, name: str) -> None:
        path = out / f"{name}.png"
        display.save_png(path)
        shots[name] = hashlib.sha256(bytes(display.framebuffer)).hexdigest()[:16]
        log(f"  {name}.png  {shots[name]}")

    for prefix, way in (("next", FROM_RIGHT), ("prev", FROM_LEFT)):
        machine, devices, touch = _machine(image, resources, nand_blobs, log=lambda *a, **k: None)
        snapshot.restore(state, machine, log=lambda *a, **k: None)
        _run(machine, 3_000)
        if prefix == "next":
            save(devices["display"], "face")
        for i in range(1, swipes + 1):
            _swipe(machine, touch, way)
            _run(machine, SETTLE_MS)
            save(devices["display"], f"{prefix}-{i:02d}")
    meta = {
        "state": state.name,
        "swipes": swipes,
        "settle_ms": SETTLE_MS,
        "frames": shots,
        "note": "next-NN: NN right-to-left swipes from the face; prev-NN: left-to-right. "
                "Which is which page: pages.json, by hand.",
    }
    (out / "screens.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    return meta
