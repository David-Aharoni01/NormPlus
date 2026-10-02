"""Two things on the input/display path that were modelled wrongly, pinned to
the firmware's own arithmetic rather than to my reading of it.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_touch_and_te.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw import patches
from normwatch.fw.devices import Rm67162Display, TouchPanel


# ── the touch panel's coordinate frame ───────────────────────────────────────

def firmware_driver_store(raw: int) -> int:
    """0x000418B4: the driver stores ``359 - raw`` into its state block."""
    return 0x167 - raw


def firmware_axis_transform(value: int) -> int:
    """0x0003F2D0: the axis stage rotates the point 180 degrees.

    Which of the four branches runs is chosen by the three config bytes at
    0x10001875; on this watch they read ``01 01 00`` at runtime, which is the
    ``x = 359 - x; y = 359 - y`` case.
    """
    return 0x167 - value


def panel_report(x: int, y: int) -> tuple[int, int]:
    """What the model puts on the wire for a finger at screen (x, y)."""
    panel = TouchPanel(machine=None)
    panel.press(x, y)
    report = panel.read(TouchPanel.REPORT_REGISTER, 10)
    return (int.from_bytes(report[4:6], "little"),
            int.from_bytes(report[6:8], "little"))


def test_the_panels_coordinates_reach_lvgl_unchanged():
    # The driver's mirror and the axis transform above it cancel out, so
    # whatever the panel reports is what the UI is told the finger touched.
    for x, y in ((0, 0), (180, 180), (300, 60), (359, 359)):
        raw_x, raw_y = panel_report(x, y)
        seen_x = firmware_axis_transform(firmware_driver_store(raw_x))
        seen_y = firmware_axis_transform(firmware_driver_store(raw_y))
        assert (seen_x, seen_y) == (x, y), f"({x},{y}) came out as ({seen_x},{seen_y})"


def test_a_press_is_reported_as_contact_and_a_lift_as_lift():
    panel = TouchPanel(machine=None)
    panel.press(10, 20)
    assert panel.read(TouchPanel.EVENT_REGISTER, 2)[1] & 3
    panel.release()
    assert panel.read(TouchPanel.EVENT_REGISTER, 2)[1] & TouchPanel.EVENT_LIFT


# ── the panel's tearing-effect line ──────────────────────────────────────────

class FakeGpio:
    def __init__(self):
        self.level = {}

    def set_input(self, pin, level):
        self.level[pin] = level


class FakeMachine:
    def __init__(self):
        self.gpio = FakeGpio()
        self.timers = []

    def add_timer(self, source):
        self.timers.append(source)


def test_te_pulses_once_per_refresh():
    machine = FakeMachine()
    display = Rm67162Display(log=lambda *a: None)
    display.attach_te(machine)
    assert display in machine.timers

    # Two full periods, stepped at the emulator's own clock quantum. The pin
    # has to spend time at both levels: the firmware waits for it to read low
    # and then high, so a line stuck either way never releases the frame.
    display.advance(0)                    # publish the starting level
    levels = set()
    for _ in range(2 * Rm67162Display.TE_PERIOD_CYCLES // 256 - 1):
        display.advance(256)
        levels.add(machine.gpio.level[Rm67162Display.TE_PIN])
    assert levels == {0, 1}, levels
    assert display.te_pulses == 2, f"expected two pulses, saw {display.te_pulses}"

    # And the high phase has to be short next to the period, or the wait for a
    # low reading is the one that never finishes.
    assert Rm67162Display.TE_HIGH_CYCLES * 4 < Rm67162Display.TE_PERIOD_CYCLES


def test_te_is_inert_until_attached():
    display = Rm67162Display(log=lambda *a: None)
    display.advance(10_000_000)          # must not raise, must not touch a machine
    assert display.te_pulses == 0


# ── the opt-in gesture patch ─────────────────────────────────────────────────

class FakeUc:
    def __init__(self, at, data):
        self.mem = {at: bytearray(data)}
        self.at = at

    def mem_read(self, address, size):
        return bytes(self.mem[self.at][address - self.at:address - self.at + size])


class PatchableMachine:
    def __init__(self, data):
        self.uc = FakeUc(patches.GESTURE_CANCEL_ADDRESS, data)
        self.written = None

    def write_flash(self, address, data, *, persist=True):
        self.written = (address, bytes(data))
        self.persisted = persist
        self.uc.mem[self.uc.at][address - self.uc.at:address - self.uc.at + len(data)] = data


def test_force_gestures_neutralises_the_cancel():
    machine = PatchableMachine(patches.GESTURE_CANCEL_EXPECTED)
    assert patches.force_gestures(machine, log=lambda *a: None) is True
    assert machine.written == (patches.GESTURE_CANCEL_ADDRESS, patches.THUMB_NOP2)
    # A patch is not the watch writing its flash: --flash-state must not
    # carry it into a later run that never asked for it.
    assert machine.persisted is False


def test_force_gestures_refuses_an_image_it_does_not_recognise():
    machine = PatchableMachine(bytes(4))
    assert patches.force_gestures(machine, log=lambda *a: None) is False
    assert machine.written is None, "must not write to an image it cannot identify"


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
