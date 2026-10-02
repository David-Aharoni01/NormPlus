"""Which way round the watch's buttons are wired, and where that comes from.

The firmware configures every one of its interrupt pins itself, and that
configuration is the only evidence for the wiring: no internal pull-up is
enabled on any of them, so a pin set to interrupt on the falling edge has to be
held high by a resistor on the board and pulled to ground by the switch. Driving
such a pin *high* to mean "pressed" is backwards, and leaves it reading as held
down for the entire run.

Run with:  uv run normtest test_buttons
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch.fw.peripherals import Gpio

#: What the firmware leaves in GPIO after boot, read back with `normwatch boot`:
#: INT0EN = 0x1001040C, INT1EN = 0x00000040, every pad 0x1A (GPIO, input enabled,
#: no pull), every CFG nibble 8 (INTD set) except pin 3's, which is 0.
BOOT_INT0EN = 0x1001040C
BOOT_INT1EN = 0x00000040
FALLING = (2, 10, 16, 28, 38)
RISING = (3,)
BUTTON = 3


def configured() -> Gpio:
    """A GPIO block set up the way the firmware leaves it."""
    gpio = Gpio(0x40010000, 0x1000)
    gpio.write(Gpio.INT_EN[0], 4, BOOT_INT0EN)
    gpio.write(Gpio.INT_EN[1], 4, BOOT_INT1EN)
    for pin in FALLING + RISING:
        offset = Gpio.CFG_BASE + (pin // 8) * 4
        nibble = 8 if pin in FALLING else 0
        gpio.write(offset, 4, gpio.storage.get(offset, 0) | (nibble << ((pin % 8) * 4)))
    return gpio


def test_the_interrupt_pins_are_the_six_the_firmware_enables():
    assert configured().interrupt_pins() == [2, 3, 10, 16, 28, 38]


def test_the_button_is_the_only_pin_that_interrupts_on_the_rising_edge():
    gpio = configured()
    rising = [p for p in gpio.interrupt_pins() if gpio.interrupt_edge(p) == 0]
    assert rising == [BUTTON], rising


def test_a_falling_edge_pin_rests_high_and_a_rising_edge_pin_rests_low():
    gpio = configured()
    assert gpio.resting_level(38) == 1
    assert gpio.resting_level(BUTTON) == 0


class FakeMachine:
    """Just enough of Apollo3Machine for press_button."""

    CYCLES_PER_SECOND = 48_000_000
    press_button = None          # filled in below

    def __init__(self):
        self.gpio = configured()
        self.events = []

    def schedule(self, at, action, description=""):
        self.events.append((at, action, description))

    def fire_all(self):
        for _, action, _ in sorted(self.events, key=lambda e: e[0]):
            action()


from normplus.watch.fw.machine import Apollo3Machine     # noqa: E402

FakeMachine.press_button = Apollo3Machine.press_button


def test_pressing_an_active_low_pin_pulls_it_down_and_lets_it_back_up():
    m = FakeMachine()
    m.gpio.set_input(38, m.gpio.resting_level(38))
    assert m.gpio.level(38) == 1, "an active-low line rests high"
    m.press_button(38, 1_000, hold=500)
    press, release = sorted(m.events, key=lambda e: e[0])
    press[1]()
    assert m.gpio.level(38) == 0, "pressing pulls it to ground"
    release[1]()
    assert m.gpio.level(38) == 1, "releasing lets the pull-up win again"


def test_pressing_the_button_drives_it_high_because_it_is_the_rising_edge_one():
    m = FakeMachine()
    m.press_button(BUTTON, 1_000, hold=500)
    press, release = sorted(m.events, key=lambda e: e[0])
    press[1]()
    assert m.gpio.level(BUTTON) == 1
    release[1]()
    assert m.gpio.level(BUTTON) == 0


def test_both_edges_latch_an_interrupt():
    m = FakeMachine()
    m.press_button(38, 1_000, hold=500)
    for _, action, _ in sorted(m.events, key=lambda e: e[0]):
        m.gpio.write(Gpio.INT_CLR[1], 4, 0xFFFFFFFF)
        action()
        assert m.gpio.int_status[1] & (1 << (38 - 32)), "no edge latched"


def test_an_unconfigured_pin_still_presses_active_high():
    # Nothing should crash on a pin the firmware never set up; the old
    # behaviour (drive high to press) is the sensible default there.
    gpio = Gpio(0x40010000, 0x1000)
    assert gpio.resting_level(7) == 0


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
