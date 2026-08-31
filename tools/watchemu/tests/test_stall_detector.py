"""The stall detector decides when to stop a run, so a false positive silently
truncates every session -- including `--live`, where the window then goes on
saying "running" while nothing moves.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_stall_detector.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw.cortexm import EXC_PENDSV
from normwatch.fw.trace import Tracer

quiet = lambda *a, **k: None


def tracer(**kw):
    kw.setdefault("stall_window", 1000)
    return Tracer(log=quiet, **kw)


def test_a_dead_loop_is_reported():
    t = tracer()
    assert t.check_progress(5000) is not None


def test_new_coverage_is_progress():
    t = tracer()
    t.on_block(0x1000)
    assert t.check_progress(5000) is None


def test_context_switches_are_progress():
    t = tracer()
    assert t.check_progress(5000) is not None      # nothing at all -> stuck
    t.on_exception(EXC_PENDSV, 0)
    assert t.check_progress(9000) is None


def test_driving_mmio_is_progress():
    """The firmware brings its hardware up with interrupts masked for millions of
    instructions, so there are no context switches to see -- but a working driver
    is still writing registers, and a wedged poll loop is not."""
    t = tracer()
    t.on_mmio(0x40010000, 4, 0, write=True)
    assert t.check_progress(5000) is None
    assert t.check_progress(9000) is not None      # no further writes -> stuck


def test_reading_one_register_forever_is_not_progress():
    t = tracer()
    for _ in range(500):
        t.on_mmio(0x40010000, 4, 0, write=False)
    assert t.check_progress(5000) is not None


def test_the_stall_stop_can_be_turned_off():
    t = tracer(watch_for_stall=False)
    assert t.check_progress(5000) is None
    assert t.check_progress(50_000_000) is None


def test_the_tracer_registers_no_hook_of_its_own():
    """It rides on the machine's timing hook; a second UC_HOOK_BLOCK costs 3.8x."""
    calls = []

    class FakeUc:
        def hook_add(self, *a, **k):
            calls.append(a)

    class FakeMachine:
        uc = FakeUc()

    tracer().attach(FakeMachine())
    assert calls == [], calls


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
