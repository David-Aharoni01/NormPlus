"""The quantum comes from the next deadline, not from a fixed sampling rate.

A fixed quantum is a sampling rate, and it degrades the way sampling does. What
made that concrete: over a 200M boot, fixed quanta of 64, 128, 256 and 512 all
give byte-identical results, and 1024 loses half the GPIO interrupts while 2048
starts moving STIMER counts -- one STIMER tick is 1464 cycles, so a longer chunk
can cross two compare points and coalesce them.

So the ceiling on a fixed quantum is real and low. Instead of picking a rate,
every source that moves with the clock says when it next will, and the run loop
goes exactly that far.

Two things are *not* deadlines and are modelled as rates on purpose, because for
them the poll frequency is the model rather than an artefact of it:

* ``Mspi.pending_irqs`` walks the command queue as a side effect, so how often
  it is called decides how fast pixels reach the panel.
* ``Gpio.pending_irqs`` is level-sensitive, so how often it is looked at decides
  how many times an asserting pin re-pends.

Both keep the historical 256-cycle interval while they have something to say and
offer no deadline at all when they do not, which is most of a boot.

Run with:  uv run normtest test_deadline_quantum
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch.fw import image as image_mod
from normplus.watch.fw.devices import (attach_motion_sensor, attach_mspi_devices,
                                  attach_pmu, attach_touch_panel)
from normplus.watch.fw.machine import (FIXED_TIME_QUANTUM, MAX_TIME_QUANTUM,
                                  Apollo3Machine)
from normplus.watch.fw.cortexm import IRQ_MSPI
from normplus.watch.fw.peripherals import CtimerBlock, Gpio, Mspi

REPO = Path(__file__).resolve().parents[2]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"

#: Far enough to have the display running and several hundred exceptions taken.
BUDGET = 20_000_000


def quiet(*a, **k):
    pass


def build(**kwargs):
    img = image_mod.load(IMAGE)
    # ble=False: with the radio answering, the firmware's HCI transport spins
    # in an interrupt-masked retry for the first ~100M instructions (nothing is
    # behind the BLEIF FIFO yet -- task #25), which at this budget leaves no
    # idle time to measure and almost no exceptions to compare. See
    # test_ble_powerup.py.
    m = Apollo3Machine(img, log=quiet, trace=None, ble=False, **kwargs)
    d = attach_mspi_devices(m, resource_blob=RESOURCES, log=quiet)
    d["touch"] = attach_touch_panel(m, log=quiet)
    d["motion"] = attach_motion_sensor(m, log=quiet)
    attach_pmu(m, log=quiet)
    return m, d


def run(**kwargs):
    m, d = build(**kwargs)
    m.run(max_instructions=BUDGET, slice_size=25_000)
    return m, d


def test_it_is_on_by_default_and_can_be_turned_off():
    on, _ = build()
    off, _ = build(deadline_quantum=False)
    assert on.deadline_quantum is True
    assert off.deadline_quantum is False
    assert off.time_quantum == FIXED_TIME_QUANTUM


def test_the_stimer_tick_is_what_bounds_a_quiet_quantum():
    # Nothing else has anything to say for most of a boot, so the answer is the
    # next 32.768 kHz tick. That is the whole win: 1464 cycles instead of 256.
    m, _ = build()
    assert m.ctimer.next_deadline() == CtimerBlock.CYCLES_PER_TICK
    m.ctimer.advance(400)
    assert m.ctimer.next_deadline() == CtimerBlock.CYCLES_PER_TICK - 400
    assert m._next_quantum() <= CtimerBlock.CYCLES_PER_TICK
    assert m._next_quantum() > FIXED_TIME_QUANTUM


def test_a_scheduled_event_pulls_the_quantum_in():
    m, _ = build()
    m._instructions = 1_000
    m.schedule(1_050, lambda: None)
    assert m._next_quantum() == 50


def test_it_never_returns_something_unusable():
    m, _ = build()
    m._instructions = 1_000
    m.schedule(900, lambda: None)          # already overdue
    assert m._next_quantum() == 1
    assert MAX_TIME_QUANTUM > CtimerBlock.CYCLES_PER_TICK


def test_the_te_edge_is_landed_on_exactly():
    m, d = build()
    display = d["display"]
    assert display.next_deadline() == display.TE_HIGH_CYCLES
    display.advance(display.TE_HIGH_CYCLES)          # now low
    assert display._te_level == 0
    assert display.next_deadline() == display.TE_PERIOD_CYCLES - display.TE_HIGH_CYCLES


def test_a_source_with_nothing_to_say_offers_no_deadline():
    m, d = build()
    # The command queue is untouched before the firmware runs, and an idle
    # queue is exactly when there is nothing to be gained by polling it.
    assert m.bus.by_base[0x50014000].next_deadline() is None
    assert m.gpio.next_deadline() is None
    assert m.cortexm.next_deadline() is None         # this build never uses SysTick


def test_the_level_sensitive_sources_ask_for_a_rate_when_they_assert():
    m, _ = build()
    gpio = m.gpio
    gpio.storage[Gpio.INT_EN[0]] = 0xFFFFFFFF
    gpio.raise_interrupt(2)
    assert gpio.next_deadline() == Gpio.POLL_INTERVAL

    mspi = m.bus.by_base[0x50014000]
    mspi.storage[Mspi.INTEN] = 0xFFFFFFFF
    mspi.int_status = Mspi.INT_DMACMP
    assert mspi.next_deadline() == Mspi.CQ_PUMP_INTERVAL


def test_mspi_still_asks_for_a_rate_when_its_irq_is_already_latched():
    """A rejected optimisation, pinned so it is not quietly reintroduced.

    During the BLE retry spin the MSPI interrupt is pending and the core is
    masked, so this poll wins the deadline 135,396 times out of 162,827 and
    delivers nothing -- returning None once the exception is latched in the
    NVIC is worth 1.14x and looks obviously safe. It is not. The poll pumps the
    command queue as a side effect, so skipping it changes the run: a radio-off
    boot moves from 12,040 STIMER interrupts to 12,043, and with --idle-skip to
    12,039, which means the two stop agreeing with each other. Being able to
    turn --idle-skip on without changing a single interrupt is worth more than
    the 1.14x.
    """
    m, _ = build()
    mspi = m.bus.by_base[0x50014000]
    mspi.storage[Mspi.INTEN] = 0xFFFFFFFF
    mspi.int_status = Mspi.INT_DMACMP
    m.cortexm.set_pending_irq(IRQ_MSPI)
    assert mspi.next_deadline() == Mspi.CQ_PUMP_INTERVAL, \
        "the poll must keep its rate even when the interrupt is already pending"


def test_cut_slice_ends_the_slice_without_inventing_cycles():
    # The Python hook's counter *is* the elapsed-cycle accumulator, so expiring
    # it directly would advance the clock by cycles nobody executed.
    m, _ = build()
    m._quantum_cycles = 7
    m.cut_slice()
    assert m._cut_requested is True
    assert m._quantum_cycles == 7


def test_a_real_run_is_unchanged_where_it_can_be_seen():
    fixed, fixed_d = run(deadline_quantum=False)
    deadline, deadline_d = run(deadline_quantum=True)

    # What the watch actually did has to be identical.
    for name in ("frames", "pixel_bytes"):
        assert getattr(fixed_d["display"], name) == getattr(deadline_d["display"], name), name
    assert fixed_d["nand"].pages_read == deadline_d["nand"].pages_read
    assert fixed.uc.mem_read(0x10001710, 4) == deadline.uc.mem_read(0x10001710, 4)

    # Exception delivery moves by a hair and is allowed to. Over the three
    # workloads this was checked against -- a full boot to the UI, a boot with a
    # swipe and a boot with a language selection -- GPIO matched exactly, MSPI
    # moved by 1 or 2 in ~3,000 and PendSV by up to 18 in ~4,700. This is the
    # only switch in the emulator that is not exact; --idle-skip was believed to
    # be one for a long time and turned out not to be.
    a, b = fixed.cortexm.exception_counts, deadline.cortexm.exception_counts
    assert set(a) == set(b), (dict(a), dict(b))
    for number in a:
        drift = abs(a[number] - b[number]) / max(1, a[number])
        assert drift < 0.01, f"exception {number} moved {drift:.2%}: {a[number]} -> {b[number]}"


def test_it_is_faster():
    _, fixed_stats = _timed(deadline_quantum=False)
    _, deadline_stats = _timed(deadline_quantum=True)
    assert deadline_stats.wall_seconds < fixed_stats.wall_seconds, (
        f"deadline {deadline_stats.wall_seconds:.2f}s vs "
        f"fixed {fixed_stats.wall_seconds:.2f}s")


def _timed(**kwargs):
    m, _ = build(**kwargs)
    return m, m.run(max_instructions=BUDGET, slice_size=25_000)


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if not name.startswith("test_") or not callable(fn):
            continue
        try:
            fn()
            print(f"ok   {name}")
        except AssertionError as e:
            failures += 1
            print(f"FAIL {name}: {e}")
    print(f"{failures} failed" if failures else "all passed")
    raise SystemExit(1 if failures else 0)
