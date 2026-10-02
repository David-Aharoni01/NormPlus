"""The I2C write path and the motion sensor that depends on it.

The IOM used to report its write FIFO permanently full, so the HAL never pushed
a payload and every register write on every I2C bus arrived empty. Nothing
noticed until a device was modelled whose entire configuration is writes.

Run with:  uv run normtest test_iom_and_motion
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch.fw.devices import MOTION_IRQ_PIN, MotionSensor
from normplus.watch.fw.peripherals import I2cDevice, Iom

ADDRESS = 0x68


class Recorder(I2cDevice):
    def __init__(self):
        self.writes = []

    def write(self, register, data):
        self.writes.append((register, bytes(data)))


def build_iom():
    iom = Iom(0x50005000, 0x1000)
    device = Recorder()
    iom.devices[ADDRESS] = device
    iom.write(iom.DEVCFG, 4, ADDRESS)
    iom.write(iom.OFFSETHI, 4, 0)
    return iom, device


def write_command(register, length=1):
    """The CMD word the HAL writes for a one-byte register write."""
    return (register << 24) | (length << 8) | (1 << 5) | Iom.CMD_WRITE


def test_a_register_write_carries_its_payload():
    iom, device = build_iom()
    iom.write(iom.CMD, 4, write_command(0x6C))
    iom.write(iom.FIFOPUSH, 4, 0x0000003F)          # the HAL pushes after CMD
    assert device.writes == [(0x6C, b"\x3f")], device.writes


def test_a_payload_pushed_before_the_command_still_arrives():
    iom, device = build_iom()
    iom.write(iom.FIFOPUSH, 4, 0x0000003F)
    iom.write(iom.CMD, 4, write_command(0x6C))
    assert device.writes == [(0x6C, b"\x3f")], device.writes


def test_the_padding_of_a_short_write_does_not_leak_into_the_next():
    # A one-byte write arrives as a whole 32-bit word with three bytes of
    # padding. Keeping those queued shifted every later value along by one, and
    # the sensor was configured with its neighbours' bytes.
    iom, device = build_iom()
    for register, value in ((0x6C, 0x3F), (0x19, 0x13), (0x1A, 0x01)):
        iom.write(iom.CMD, 4, write_command(register))
        iom.write(iom.FIFOPUSH, 4, value)
    assert device.writes == [(0x6C, b"\x3f"), (0x19, b"\x13"), (0x1A, b"\x01")], device.writes


def test_fifoptr_advertises_room_to_push():
    iom, _ = build_iom()
    assert (iom.read(0x100, 4) >> 8) & 0xFF == iom.FIFO_DEPTH


def test_a_write_to_nobody_is_counted_and_dropped():
    iom, _ = build_iom()
    iom.write(iom.DEVCFG, 4, 0x55)
    iom.write(iom.CMD, 4, write_command(0x01))
    iom.write(iom.FIFOPUSH, 4, 0xAA)
    assert iom.unanswered[0x55] == 1
    assert not iom.tx, "a dropped write must not leave bytes queued for the next one"


# ── the motion sensor ────────────────────────────────────────────────────────

#: The firmware's own table at 0x000CE058, in order.
INIT_TABLE = [(0x6C, 0x3F), (0x19, 0x13), (0x1A, 0x01), (0x1B, 0x00),
              (0x1C, 0x10), (0x1D, 0x08), (0x23, 0x08), (0x37, 0x00),
              (0x38, 0x10), (0x39, 0x40), (0x60, 0x00), (0x61, 0xC8),
              (0x6A, 0x04), (0x6A, 0x44), (0x6B, 0x28), (0x6C, 0x07)]


def configured_sensor():
    sensor = MotionSensor()
    sensor.write(0x6B, b"\x80")                      # DEVICE_RESET, as the driver does
    for register, value in INIT_TABLE:
        sensor.write(register, bytes([value]))
    return sensor


def test_the_firmwares_own_init_table_starts_it_streaming():
    sensor = configured_sensor()
    assert sensor.enabled, "USER_CTRL bit 6 should have switched the FIFO on"
    sensor.advance(48_000_000)                       # one second
    assert sensor.samples_made == 50, sensor.samples_made   # SMPLRT_DIV 19 -> 50 Hz


def test_the_fifo_count_is_big_endian_across_two_registers():
    sensor = configured_sensor()
    sensor.advance(48_000_000 // 10)                 # 5 samples = 40 bytes
    high = sensor.read(sensor.FIFO_COUNTH, 1)[0]
    low = sensor.read(sensor.FIFO_COUNTL, 1)[0]
    assert (high << 8) | low == len(sensor.fifo) == 40, (high, low, len(sensor.fifo))


def test_a_sample_reads_back_as_big_endian_xyz_in_an_eight_byte_stride():
    sensor = configured_sensor()
    sensor.acceleration = (0.0, -1.0, 0.5)
    sensor.advance(48_000_000 // 50)
    sample = sensor.read(sensor.FIFO_R_W, 8)
    assert len(sample) == 8
    x = int.from_bytes(sample[0:2], "big", signed=True)
    y = int.from_bytes(sample[2:4], "big", signed=True)
    z = int.from_bytes(sample[4:6], "big", signed=True)
    assert (x, y, z) == (0, -MotionSensor.LSB_PER_G, MotionSensor.LSB_PER_G // 2), (x, y, z)


def test_it_stays_silent_until_the_fifo_is_switched_on():
    sensor = MotionSensor()
    sensor.advance(48_000_000)
    assert sensor.samples_made == 0
    assert sensor.read(sensor.WHO_AM_I_REG, 1) == bytes([MotionSensor.WHO_AM_I])


# ── the interrupt line ───────────────────────────────────────────────────────
#
# The driver is interrupt-driven and never polls: with the line unwired the
# firmware configures the part, waits, and reads nothing at all. Pin 16 was
# identified by pressing each of the six pins the firmware enables an interrupt
# on and diffing coverage -- it is the only one that moves system_step_task.c.


class FakeGpio:
    """Records what a device does to its interrupt line."""

    def __init__(self, falling=True):
        self.falling = falling
        self.level = None
        self.edges = 0

    def resting_level(self, number):
        return 1 if self.falling else 0

    def set_input(self, number, level):
        self.level = level

    def raise_interrupt(self, number):
        self.edges += 1

    def assert_irq(self, number, asserted):
        from normplus.watch.fw.peripherals import Gpio
        Gpio.assert_irq(self, number, asserted)


class FakeMachine:
    def __init__(self, gpio):
        self.gpio = gpio

    def add_timer(self, source):
        pass


def test_the_sensor_interrupt_is_pin_16():
    assert MOTION_IRQ_PIN == 16
    # And it is one of the six the firmware actually enables.
    assert MOTION_IRQ_PIN in (2, 3, 10, 16, 28, 38)


def test_the_sensor_pulls_its_line_down_to_signal():
    # Pin 16 interrupts on the falling edge, so it rests high and is pulled to
    # ground to assert. Driving it high to mean "asserted" is backwards.
    gpio = FakeGpio(falling=True)
    sensor = configured_sensor()
    sensor.machine = FakeMachine(gpio)
    sensor.irq_pin = MOTION_IRQ_PIN
    sensor.advance(48_000_000)                      # fill well past the watermark
    assert gpio.edges > 0, "the sensor never signalled"
    assert gpio.level == 0, "asserted should pull the line to ground"


def test_the_line_returns_to_rest_once_the_fifo_is_drained():
    gpio = FakeGpio(falling=True)
    sensor = configured_sensor()
    sensor.machine = FakeMachine(gpio)
    sensor.irq_pin = MOTION_IRQ_PIN
    sensor.advance(48_000_000)
    assert gpio.level == 0
    sensor.read(sensor.FIFO_R_W, len(sensor.fifo))
    sensor.advance(0)
    assert gpio.level == 1, "the line should be released after a drain"


def test_a_rising_edge_line_is_driven_the_other_way():
    gpio = FakeGpio(falling=False)
    from normplus.watch.fw.peripherals import Gpio
    Gpio.assert_irq(gpio, 3, True)
    assert gpio.level == 1
    Gpio.assert_irq(gpio, 3, False)
    assert gpio.level == 0


def test_an_unwired_sensor_does_not_reach_for_a_machine():
    sensor = configured_sensor()
    sensor.machine = None
    sensor.advance(48_000_000)          # must not raise
    assert sensor.samples_made > 0


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
