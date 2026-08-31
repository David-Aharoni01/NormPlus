"""The bit-banged I2C bus and the battery gauge on it.

The gauge is not on any hardware I2C controller. ew_drv_sim_i2c.c clocks it by
hand on two GPIOs, which is why all six IOMs stay idle and the part went
unmodelled -- there was no bus traffic to miss, only a very hot GPIO register.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_pmu_and_bitbang.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw.devices import Cw6303Pmu
from normwatch.fw.peripherals import BitBangI2cBus, Gpio, I2cDevice

SCL, SDA, ADDR = 11, 13, 0x62


class Recorder(I2cDevice):
    def __init__(self):
        self.writes = []
        self.reads = []
        self.canned = b""

    def read(self, register, length):
        self.reads.append((register, length))
        return (self.canned or bytes(length))[:length]

    def write(self, register, data):
        self.writes.append((register, bytes(data)))


def build():
    gpio = Gpio(0x40010000, 0x1000)
    bus = BitBangI2cBus(gpio, SCL, SDA)
    device = Recorder()
    bus.devices[ADDR] = device
    return gpio, bus, device


# ── driving the bus the way the firmware does ────────────────────────────────

def _set(gpio, pin, level):
    """Drive a pin through the write path, so the bus's watcher sees the edge."""
    bank, bit = divmod(pin, 32)
    offset = (Gpio.WTSA, Gpio.WTSB)[bank] if level else (Gpio.WTCA, Gpio.WTCB)[bank]
    gpio.write(offset, 4, 1 << bit)


def start(gpio):
    _set(gpio, SDA, 1); _set(gpio, SCL, 1); _set(gpio, SDA, 0); _set(gpio, SCL, 0)


def stop(gpio):
    _set(gpio, SDA, 0); _set(gpio, SCL, 1); _set(gpio, SDA, 1)


def send_byte(gpio, value):
    for i in range(7, -1, -1):
        _set(gpio, SDA, (value >> i) & 1)
        _set(gpio, SCL, 1)
        _set(gpio, SCL, 0)
    _set(gpio, SCL, 1)          # the ninth pulse is the acknowledge, not data
    _set(gpio, SCL, 0)


def read_byte(gpio):
    bits = []
    for _ in range(8):
        _set(gpio, SCL, 1)
        bits.append(gpio.level(SDA))
        _set(gpio, SCL, 0)
    _set(gpio, SCL, 1)
    _set(gpio, SCL, 0)
    return int("".join(str(b) for b in bits), 2)


def test_a_register_write_reaches_the_device():
    gpio, bus, device = build()
    start(gpio)
    send_byte(gpio, ADDR << 1)          # address + W
    send_byte(gpio, 0x08)               # register
    send_byte(gpio, 0x5F)               # value
    stop(gpio)
    assert device.writes == [(0x08, bytes([0x5F]))], device.writes


def test_the_acknowledge_pulse_is_not_counted_as_data():
    # Every ninth clock is the ACK. Sampling it shifts each byte one place
    # right -- 0x08 arrives as 4, 0x5F as 0x17 -- which is quietly plausible
    # enough to look like a real register map.
    gpio, bus, device = build()
    start(gpio)
    send_byte(gpio, ADDR << 1)
    send_byte(gpio, 0x10)
    send_byte(gpio, 0x17)
    stop(gpio)
    register, payload = device.writes[0]
    assert register == 0x10, hex(register)
    assert payload == bytes([0x17]), payload.hex()


def test_setting_the_pointer_then_reading_does_not_look_like_a_write():
    gpio, bus, device = build()
    device.canned = bytes([0x2A])
    start(gpio)
    send_byte(gpio, ADDR << 1)
    send_byte(gpio, 0x04)               # pointer only
    start(gpio)                         # repeated START
    send_byte(gpio, (ADDR << 1) | 1)    # address + R
    value = read_byte(gpio)
    stop(gpio)
    assert device.writes == [], device.writes
    assert device.reads and device.reads[0][0] == 0x04, device.reads
    assert value == 0x2A, hex(value)


def test_the_register_pointer_survives_a_stop():
    # The firmware sets the pointer in one transaction and reads in the next.
    gpio, bus, device = build()
    device.canned = bytes([0x77])
    start(gpio); send_byte(gpio, ADDR << 1); send_byte(gpio, 0x03); stop(gpio)
    start(gpio); send_byte(gpio, (ADDR << 1) | 1); read_byte(gpio); stop(gpio)
    assert device.reads[0][0] == 0x03, device.reads


def test_an_address_nobody_answers_is_counted_not_crashed():
    gpio, bus, _ = build()
    start(gpio)
    send_byte(gpio, 0x09 << 1)
    stop(gpio)
    assert bus.unanswered[0x09] == 1, dict(bus.unanswered)


def test_a_slave_can_pull_the_line_low_against_a_driven_high():
    # An open-drain bus works only if a pull beats the master's high; ORing the
    # input with the output could never express that, so nothing could ACK.
    gpio = Gpio(0x40010000, 0x1000)
    _set(gpio, SDA, 1)
    assert gpio.level(SDA) == 1
    gpio.pull_low(SDA, True)
    assert gpio.level(SDA) == 0
    assert (gpio.read(Gpio.RDA, 4) >> SDA) & 1 == 0
    gpio.pull_low(SDA, False)
    assert gpio.level(SDA) == 1


# ── the gauge ────────────────────────────────────────────────────────────────

def test_charge_and_voltage_read_back_where_the_firmware_looks():
    pmu = Cw6303Pmu(percent=80.0)
    assert pmu.read(pmu.SOC_H, 1)[0] == 80
    raw = (pmu.read(pmu.VCELL_H, 1)[0] << 8) | pmu.read(pmu.VCELL_L, 1)[0]
    millivolts = raw * pmu.UV_PER_LSB / 1000.0
    assert abs(millivolts - pmu.millivolts) < 1.0, millivolts
    assert 3300 <= pmu.millivolts <= 4200


def test_a_sequential_read_walks_forward():
    pmu = Cw6303Pmu(percent=50.0)
    block = pmu.read(pmu.VERSION, 6)
    assert len(block) == 6
    assert block[pmu.SOC_H] == 50


def test_config_and_mode_read_back_what_was_written():
    # The firmware writes MODE then reads CONFIG back; if those do not persist
    # it cannot tell that bring-up succeeded.
    pmu = Cw6303Pmu()
    pmu.write(pmu.MODE, bytes([0x00]))
    pmu.write(pmu.CONFIG, bytes([0x1F]))
    assert pmu.read(pmu.MODE, 1) == bytes([0x00])
    assert pmu.read(pmu.CONFIG, 1) == bytes([0x1F])


def test_the_uploaded_profile_reads_back():
    # The firmware uploads 64 bytes starting at 0x10 -- the CellWise BATINFO
    # table -- and the first byte it writes is 0x17.
    pmu = Cw6303Pmu()
    assert pmu.PROFILE_BASE == 0x10
    pmu.write(pmu.PROFILE_BASE, bytes([0x17, 0x67, 0x65]))
    assert pmu.read(pmu.PROFILE_BASE, 3) == bytes([0x17, 0x67, 0x65])
    assert pmu.profile_bytes == 3


def test_charge_is_clamped_to_a_real_range():
    assert Cw6303Pmu(percent=-20).percent == 0.0
    assert Cw6303Pmu(percent=250).percent == 100.0


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
