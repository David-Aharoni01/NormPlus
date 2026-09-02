"""Fast-forwarding the idle task must not change what the watch does.

A PC sample every 40,000 instructions across the 440M it takes to reach the UI
puts 70% of the boot in FreeRTOS's idle busy-wait, and `halts` is zero for the
whole run -- this build never executes WFI, so the core spins at full speed with
nothing to do. Skipping that is worth about 2.2x.

The invariant that makes it usable is that instructions ARE the emulated clock:
every skipped cycle is still counted and still advances the timers, so anything
driven by time happens at the same emulated instant either way.

It is NOT bit-identical, and these tests say so rather than pretending
otherwise. The totals agree to within 100 instructions in 20M and the boot
animation lands on exactly the same frame, but the STIMER interrupt count shifts
by about 4% because a tick the fully emulated run coalesced can be delivered
separately here. Finer steps converge on the same shifted number, so it is a
real difference in delivery rather than sampling error. That is the reason the
flag exists instead of this being the default.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_idle_skip.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw import image as image_mod
from normwatch.fw.devices import (attach_motion_sensor, attach_mspi_devices,
                                  attach_pmu, attach_touch_panel)
from normwatch.fw.machine import (IDLE_LOOP_EXPECTED, IDLE_LOOP_HEAD,
                                  Apollo3Machine)

REPO = Path(__file__).resolve().parents[3]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"

#: Far enough in that the idle task is running and the boot animation has
#: started stepping, so there is something to compare. At 20M the animation is
#: on frame 5 and about 200 skips have happened.
BUDGET = 20_000_000
#: The boot animation's state: frame count at +4, current frame at +0x10. It is
#: driven by timers, so it is a direct readout of the emulated clock.
ANIMATION = 0x10000114
#: STIMER_CMPR0. The one exception whose count the skip is allowed to move.
STIMER_EXCEPTION = 39

_img = image_mod.load(IMAGE)


def quiet(*a, **k):
    pass


def run(idle_skip):
    """A machine with the devices attached — the firmware does not get anywhere
    near its idle task without storage and a panel to talk to."""
    img = image_mod.load(IMAGE)
    m = Apollo3Machine(img, log=quiet, trace=None, idle_skip=idle_skip)
    attach_mspi_devices(m, resource_blob=RESOURCES, log=quiet)
    attach_touch_panel(m, log=quiet)
    attach_motion_sensor(m, log=quiet)
    attach_pmu(m, log=quiet)
    stats = m.run(max_instructions=BUDGET, slice_size=25_000)
    return m, stats


def test_the_idle_loop_is_where_we_think_it_is():
    m = Apollo3Machine(_img, log=lambda *a, **k: None, trace=None)
    found = bytes(m.uc.mem_read(IDLE_LOOP_HEAD, len(IDLE_LOOP_EXPECTED)))
    assert found == IDLE_LOOP_EXPECTED, found.hex(" ")


def test_it_refuses_to_engage_on_an_image_it_does_not_recognise():
    from normwatch.fw import machine as machine_mod

    m = Apollo3Machine(_img, log=lambda *a, **k: None, trace=None, idle_skip=True)
    assert m.idle_skip, "should have engaged on this image"

    # Expect different bytes at the loop head, as a different build would have.
    original = machine_mod.IDLE_LOOP_EXPECTED
    machine_mod.IDLE_LOOP_EXPECTED = b"\xde\xad\xbe\xef" * 4
    try:
        other = Apollo3Machine(_img, log=lambda *a, **k: None, trace=None,
                               idle_skip=True)
    finally:
        machine_mod.IDLE_LOOP_EXPECTED = original
    assert not other.idle_skip, "engaged on an image whose idle loop is not there"


def test_skipping_actually_happens_and_saves_most_of_the_run():
    m, stats = run(True)
    assert m.idle_skips > 0, "never skipped"
    share = m.idle_instructions_skipped / stats.instructions
    # Early boot does real work, so the share here is lower than the
    # ~68% seen once the watch settles into its animation.
    assert share > 0.2, f"only skipped {share:.0%} of the run"


def animation(m):
    """Frame count and current frame. Driven by timers, so it reads the clock."""
    return (m.uc.mem_read(ANIMATION + 4, 1)[0],
            int.from_bytes(m.uc.mem_read(ANIMATION + 0x10, 4), "little"))


def test_the_boot_animation_lands_on_the_same_frame():
    # The animation steps off the RTOS clock, so if the skip shifted emulated
    # time this is where it would show.
    plain, _ = run(False)
    skipped, _ = run(True)
    assert animation(plain) == animation(skipped), (animation(plain), animation(skipped))
    assert animation(plain)[1] > 0, "the animation never started; widen BUDGET"


def test_the_clock_agrees_to_a_rounding_error():
    plain, plain_stats = run(False)
    skipped, skipped_stats = run(True)
    # The fast-forward advances in fixed steps, so it can overshoot the budget
    # by less than one step. Anything larger than that is drift.
    assert abs(plain_stats.instructions - skipped_stats.instructions) < 1_000
    assert abs(plain.cycles - skipped.cycles) < 1_000, (plain.cycles, skipped.cycles)


def test_the_divergence_is_confined_to_timer_delivery():
    # PendSV and the MSPI have to match exactly: a skip that lost a context
    # switch or a transfer would be unusable. STIMER is allowed to move a few
    # percent, and is documented as doing so.
    plain, _ = run(False)
    skipped, _ = run(True)
    a, b = dict(plain.cortexm.exception_counts), dict(skipped.cortexm.exception_counts)
    assert set(a) == set(b), (a, b)
    for number in a:
        if number == STIMER_EXCEPTION:
            drift = abs(a[number] - b[number]) / max(1, a[number])
            assert drift < 0.10, f"STIMER moved {drift:.0%}: {a[number]} -> {b[number]}"
        else:
            assert a[number] == b[number], (number, a[number], b[number])


def test_it_is_off_unless_asked_for():
    m, _ = run(False)
    assert not m.idle_skip
    assert m.idle_skips == 0


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
