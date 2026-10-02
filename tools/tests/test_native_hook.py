"""The block hook, in C, must do exactly what the Python one did.

Crossing into Python costs ~285 ns per basic block and the firmware averages a
block every 5.4 instructions, so the hook alone was about 30% of the wall clock.
native/watchemu_hook.c does the same work without entering Python — about 2.5x.

The whole value of this depends on it being indistinguishable, so the tests that
matter compare a real run against the Python hook and demand the exception
counts match *exactly*. Two bugs were found that way and neither showed up as a
crash:

* Unicorn's ``count`` argument counts instructions while the emulator's clock
  counts halfwords. Ending the slice with ``count`` ran the clock ~30% fast.
* After ``enter()`` consumed a pending IRQ, nothing told the C side, so it
  stopped on the handler's first block and the poll that followed re-armed the
  interrupt before the handler had cleared its source. Every peripheral IRQ was
  taken exactly twice.

Run with:  uv run normtest test_native_hook
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch.fw import fasthook
from normplus.watch.fw import image as image_mod
from normplus.watch.fw.devices import (attach_motion_sensor, attach_mspi_devices,
                                  attach_pmu, attach_touch_panel)
from normplus.watch.fw.machine import Apollo3Machine

REPO = Path(__file__).resolve().parents[2]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"

#: Far enough to take several hundred exceptions of three different kinds.
BUDGET = 20_000_000
ANIMATION = 0x10000114

_built = fasthook._LIBRARY.exists()


def quiet(*a, **k):
    pass


def run(fast_hook, idle_skip=False):
    img = image_mod.load(IMAGE)
    # ble=False: with the radio answering, the firmware's HCI transport spins
    # in an interrupt-masked retry for the first ~100M instructions (nothing is
    # behind the BLEIF FIFO yet -- task #25), which at this budget leaves no
    # idle time to measure and almost no exceptions to compare. See
    # test_ble_powerup.py.
    m = Apollo3Machine(img, log=quiet, trace=None, idle_skip=idle_skip,
                       fast_hook=fast_hook, ble=False)
    attach_mspi_devices(m, resource_blob=RESOURCES, log=quiet)
    attach_touch_panel(m, log=quiet)
    attach_motion_sensor(m, log=quiet)
    attach_pmu(m, log=quiet)
    stats = m.run(max_instructions=BUDGET, slice_size=25_000)
    return m, stats


def animation(m):
    return (m.uc.mem_read(ANIMATION + 4, 1)[0],
            int.from_bytes(m.uc.mem_read(ANIMATION + 0x10, 4), "little"))


def test_the_library_is_built():
    assert _built, (f"{fasthook._LIBRARY} is missing — "
                    f"uv run python tools/normplus/watch/native/build.py")


def test_it_loads_and_registers():
    m, _ = run(True)
    assert m.native_hook is not None, "fell back to the Python hook"
    assert m.native_hook.state.blocks > 0, "the C hook never ran"


def test_every_exception_matches_the_python_hook_exactly():
    plain, _ = run(False)
    native, _ = run(True)
    assert dict(native.cortexm.exception_counts) == dict(plain.cortexm.exception_counts), \
        (dict(plain.cortexm.exception_counts), dict(native.cortexm.exception_counts))
    # And there has to be something to compare.
    assert sum(plain.cortexm.exception_counts.values()) > 100


def test_the_clock_matches():
    plain, plain_stats = run(False)
    native, native_stats = run(True)
    assert animation(plain) == animation(native), (animation(plain), animation(native))
    assert abs(plain_stats.instructions - native_stats.instructions) < 1_000
    # machine.cycles is not purely the instruction clock: bootrom_delay_cycles
    # adds to it directly, ~108k times a boot, and how many of those land inside
    # a given budget shifts by a handful. Proportional rather than absolute, and
    # the exception counts above are the exact check.
    drift = abs(plain.cycles - native.cycles) / max(1, plain.cycles)
    assert drift < 1e-4, f"clock drifted {drift:.2%} ({plain.cycles} vs {native.cycles})"


def test_it_is_still_exact_with_idle_skip_on_top():
    plain, _ = run(False)
    native, _ = run(True, idle_skip=True)
    assert dict(native.cortexm.exception_counts) == dict(plain.cortexm.exception_counts)
    assert native.idle_skips > 0, "idle skipping never engaged"


def test_the_python_hook_is_not_also_registered():
    # Both running would double-count the clock, and it would look like drift
    # rather than like two hooks.
    m, _ = run(True)
    assert m.native_hook.state.instructions > 0
    assert abs(m.native_hook.state.instructions - m._instructions) < 1_000


# ── it has to degrade rather than break ──────────────────────────────────────

def test_it_falls_back_when_the_library_is_missing():
    original = fasthook._LIBRARY
    fasthook._LIBRARY = original.with_name("definitely-not-here.dll")
    try:
        m, _ = run(True)
    finally:
        fasthook._LIBRARY = original
    assert m.native_hook is None, "claimed a hook it could not have loaded"
    assert m._instructions > 0, "the run should still work on the Python hook"


def test_tracing_keeps_the_python_hook():
    from normplus.watch.fw.symbols import SymbolMap
    from normplus.watch.fw.trace import Tracer

    img = image_mod.load(IMAGE)
    tracer = Tracer(SymbolMap(img.payload, img.link_address), log=quiet)
    m = Apollo3Machine(img, log=quiet, trace=tracer, fast_hook=True)
    assert m.native_hook is None, "tracing needs per-block Python"


def test_watchpoints_refuse_rather_than_silently_doing_nothing():
    m, _ = run(True)
    try:
        m.watch(0x10000000, 4)
    except RuntimeError as exc:
        assert "fast_hook" in str(exc)
    else:
        raise AssertionError("watch() should refuse while the native hook is in use")


def test_a_stale_library_is_rejected():
    # The struct is shared between C and Python by layout alone, so a rebuild
    # that changes it has to be caught rather than silently misread.
    import ctypes

    original = fasthook.State

    class Bigger(ctypes.Structure):
        _fields_ = original._fields_ + [("extra", ctypes.c_uint64)]

    fasthook.State = Bigger
    try:
        m, _ = run(True)
    finally:
        fasthook.State = original
    assert m.native_hook is None, "accepted a library whose state does not match"


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
