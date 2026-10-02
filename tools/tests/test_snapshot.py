"""Snapshots: a machine saved part-way and put back is the same machine.

The claim that matters is the strongest one there is: save a run part-way,
restore it *in another process*, carry on -- and the result is the golden
fingerprint (tests/golden.json), to the instruction, as if it had never
stopped. Another process because a snapshot has to survive one: the CPU
context carries per-engine pointers (see snapshot.py), and a test that
restored into the same process could get away with copying them. And with the
input -- a tap, a swipe -- scheduled only after the restore, because "a
restored machine takes touch input and navigates" is the point of having one.

Then: pausing and resuming in one process is one run, on both block hooks;
restore is well under a second; a machine that cannot be saved, or one built
differently from the one that was, is refused with the reason; and the CLI's
--save-state / --load-state, live window included.

Run with:  uv run normtest test_snapshot
"""
import contextlib
import io
import json
import pickle
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE))

import test_golden as golden
from normplus.watch.fw import image as image_mod
from normplus.watch.fw import snapshot
from normplus.watch.fw.devices import (attach_motion_sensor, attach_mspi_devices, attach_pmu,
                                  attach_touch_panel)
from normplus.watch.fw.machine import Apollo3Machine

_tmp = Path(tempfile.mkdtemp(prefix="watchemu-snapshot-"))

#: Restore a snapshot in a fresh interpreter, carry the workload on to its
#: golden budget, and print the restore report and the fingerprint.
CHILD = r"""
import json, sys
sys.path[:0] = [sys.argv[3], sys.argv[4]]
import test_golden as g
from normplus.watch.fw import snapshot
name, path = sys.argv[1], sys.argv[2]
m, d, t = g.build(name, stimulus=False)
report = snapshot.restore(path, m, log=lambda *a: None)
w = g.WORKLOADS[name]
if w.get("stimulus"):
    w["stimulus"](m, t)
stats = m.run(w["budget"], 4_000_000, resume=True)
print(json.dumps({"restore": report, "fingerprint": g.fingerprint(m, d, t, stats)}))
"""


def restored_elsewhere(name: str, at: int) -> dict:
    """Run *name* to *at* here, save it, finish it in another process."""
    path = _tmp / f"{name}.snap"
    m, _, _ = golden.build(name, stimulus=False)
    m.run(at, 4_000_000)
    snapshot.save(path, m)
    child = subprocess.run([sys.executable, "-c", CHILD, name, str(path),
                            str(HERE.parent), str(HERE)],
                           capture_output=True, text=True, timeout=300)
    assert child.returncode == 0, (child.returncode, child.stderr[-2000:])
    return json.loads(child.stdout.strip().splitlines()[-1])


_reports = {}


def check_against_golden(name: str, at: int) -> None:
    result = restored_elsewhere(name, at)
    _reports[name] = result["restore"]
    diff = golden.differences(golden.golden()["workloads"][name], result["fingerprint"])
    assert not diff, f"{name}, saved at {at:,} and restored in another process:\n    " \
                     + "\n    ".join(diff)


def test_the_radio_boot_resumes_into_its_golden_run():
    # Saved half way through the boot animation, with the NZ8801 model and the
    # firmware's HCI state behind the BLEIF.
    check_against_golden("radio", 200_000_000)


def test_a_restored_watch_takes_a_swipe_and_opens_the_page():
    # Saved on the face (booted from the bound fixture), the swipe scheduled
    # only after the restore.
    check_against_golden("face_swipe", 410_000_000)


def test_restore_is_under_a_second():
    report = _reports.get("face_swipe") or restored_elsewhere("face_swipe", 410_000_000)["restore"]
    assert report["seconds"] < 1.0, report


def quiet(*_a, **_k):
    pass


def machine(*, fast_hook=True, idle_skip=False, pmu=True):
    m = Apollo3Machine(image_mod.load(golden.IMAGE), log=quiet, trace=None,
                       fast_hook=fast_hook, idle_skip=idle_skip, ble=False)
    attach_mspi_devices(m, resource_blob=golden.RESOURCES, log=quiet)
    attach_touch_panel(m, log=quiet)
    attach_motion_sensor(m, log=quiet)
    if pmu:
        attach_pmu(m, log=quiet)
    return m


def counts(m) -> tuple:
    return (m._instructions, m.cycles, dict(m.cortexm.exception_counts))


def test_pausing_and_resuming_is_one_run_on_either_hook():
    for fast in (True, False):
        straight = machine(fast_hook=fast)
        straight.run(20_000_000, 4_000_000)
        paused = machine(fast_hook=fast)
        paused.run(9_000_000, 4_000_000)
        paused.run(20_000_000, 4_000_000, resume=True)
        assert counts(paused) == counts(straight), (fast, counts(paused), counts(straight))


def test_a_snapshot_on_the_python_hook_is_one_run_too():
    # The Python hook keeps its clock in machine attributes rather than the C
    # struct; both have to come across.
    path = _tmp / "python-hook.snap"
    straight = machine(fast_hook=False)
    straight.run(20_000_000, 4_000_000)
    first = machine(fast_hook=False)
    first.run(9_000_000, 4_000_000)
    snapshot.save(path, first)
    second = machine(fast_hook=False)
    snapshot.restore(path, second, log=quiet)
    second.run(20_000_000, 4_000_000, resume=True)
    assert counts(second) == counts(straight), (counts(second), counts(straight))


def refused(fn, kind, *words):
    try:
        fn()
    except kind as exc:
        for word in words:
            assert word in str(exc), (word, str(exc))
        return
    raise AssertionError(f"{fn} was not refused")


def test_what_cannot_be_saved_is_refused():
    refused(lambda: snapshot.save(_tmp / "x.snap", machine()), snapshot.SnapshotRefused, "not run")

    pending = machine()
    pending.schedule(50_000_000, lambda: None)
    pending.run(1_000_000, 4_000_000)
    refused(lambda: snapshot.save(_tmp / "x.snap", pending), snapshot.SnapshotRefused, "pending")

    from normplus.watch.fw.blelink import BumbleController, Radio
    from normplus.watch.fw.devices import attach_ble_controller

    radio = Radio()
    try:
        with_radio = Apollo3Machine(image_mod.load(golden.IMAGE), log=quiet, trace=None)
        attach_ble_controller(with_radio, BumbleController(radio=radio, log=quiet), log=quiet)
        with_radio.run(1_000_000, 4_000_000)
        refused(lambda: snapshot.save(_tmp / "x.snap", with_radio),
                snapshot.SnapshotRefused, "bumble")
    finally:
        radio.close()


def test_a_machine_built_differently_is_refused_and_named():
    path = _tmp / "small.snap"
    m = machine()
    m.run(2_000_000, 4_000_000)
    snapshot.save(path, m)

    refused(lambda: snapshot.restore(path, machine(pmu=False), log=quiet),
            snapshot.SnapshotMismatch, "Cw6303")
    refused(lambda: snapshot.restore(path, machine(idle_skip=True), log=quiet),
            snapshot.SnapshotMismatch, "idle_skip")
    refused(lambda: snapshot.restore(path, machine(fast_hook=False), log=quiet),
            snapshot.SnapshotMismatch, "NativeHook")

    data = pickle.loads(zlib.decompress(path.read_bytes()))
    data["image_sha256"] = "0" * 64
    other = _tmp / "other-image.snap"
    other.write_bytes(zlib.compress(pickle.dumps(data)))
    refused(lambda: snapshot.restore(other, machine(), log=quiet),
            snapshot.SnapshotMismatch, "different firmware image")


def boot_cli(*argv) -> tuple:
    from normplus.watch.__main__ import main

    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        status = main(["boot", str(golden.IMAGE), "--resources", str(golden.RESOURCES),
                       "--no-ble", "--json", *argv])
    return status, json.loads(out.getvalue()) if status == 0 else out.getvalue()


def test_the_cli_saves_and_loads_the_same_screen():
    path = _tmp / "cli.snap"
    status, saved = boot_cli("--max-instructions", "30000000", "--save-state", str(path))
    assert status == 0 and path.exists(), saved
    status, loaded = boot_cli("--load-state", str(path), "--max-instructions", "1000")
    assert status == 0, loaded
    # Picked up where it was, and ran on the thousand it was given (to the
    # end of a slice), not thirty million from the start.
    assert saved["instructions"] < loaded["instructions"] < saved["instructions"] + 100_000, \
        (saved["instructions"], loaded["instructions"])
    for key in ("display_frames", "display_pixel_bytes", "nand_pages_read"):
        assert saved[key] == loaded[key], (key, saved[key], loaded[key])

    status, _ = boot_cli("--load-state", str(path), "--flash-state", str(_tmp / "f.zip"))
    assert status == 2, "--load-state with --flash-state was accepted"


def test_the_live_window_resumes_too():
    # The window's worker calls run() itself; stand a window in that just
    # waits for it, and check the run carried on rather than rebooting.
    from normplus.watch import __main__ as cli
    from normplus.watch.fw import viewer

    class Window:
        def __init__(self, machine, *_a, **_k):
            pass

        def run(self, thread):
            thread.join()

    path = _tmp / "live.snap"
    status, saved = boot_cli("--max-instructions", "5000000", "--save-state", str(path))
    assert status == 0, saved
    real = viewer.WatchWindow
    viewer.WatchWindow = Window
    try:
        status, loaded = boot_cli("--load-state", str(path), "--live", "--max-instructions", "1000")
    finally:
        viewer.WatchWindow = real
    assert status == 0, loaded
    assert saved["instructions"] < loaded["instructions"] < saved["instructions"] + 100_000, \
        (saved["instructions"], loaded["instructions"])


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
