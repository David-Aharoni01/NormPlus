"""The charger half of the PMU, at 0x09 on the bit-banged bus.

Both addresses belong to pmu_cw6303.c -- looking for a second driver talking to
0x09 found nothing, because there isn't one. The register meanings here come
from the firmware's own bring-up traffic and from feeding it each status value
and watching which code the driver derives, not from a datasheet.

Run with:  uv run normtest test_charger
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch.fw.devices import (CHARGER_I2C_ADDRESS, Cw6303Charger, Cw6303Pmu,
                                  PMU_I2C_ADDRESS, PMU_SCL_PIN, PMU_SDA_PIN)
from normplus.watch.fw.peripherals import BitBangI2cBus, Gpio

#: What the firmware writes at bring-up, 175k instructions into boot.
BRINGUP = [(0x01, 0xB7), (0x02, 0x87), (0x03, 0x00), (0x04, 0xFF),
           (0x0A, 0xBD), (0x0B, 0xBD), (0x0C, 0xBD), (0x0D, 0xBD), (0x00, 0x00)]


def test_the_charge_current_and_voltage_survive_the_bringup():
    charger = Cw6303Charger()
    assert not charger.configured
    for register, value in BRINGUP:
        charger.write(register, bytes([value]))
    assert charger.configured
    assert charger.read(charger.CHARGE_CURRENT, 1) == bytes([0xB7])
    assert charger.read(charger.CHARGE_VOLTAGE, 1) == bytes([0x87])
    assert charger.read(0x0A, 4) == bytes([0xBD] * 4)


def test_writing_zero_to_the_status_does_not_clear_it():
    # The firmware writes 0 to register 3 after every single read, so a part
    # that stayed cleared would report "nothing" forever -- and the driver
    # demonstrably keeps deriving a stable code from it.
    charger = Cw6303Charger(charging=True)
    assert charger.read(charger.STATUS, 1)[0] == charger.STATUS_CHARGE
    charger.write(charger.STATUS, b"\x00")
    assert charger.read(charger.STATUS, 1)[0] == charger.STATUS_CHARGE


def test_the_status_follows_the_charging_flag():
    assert Cw6303Charger(charging=False).status == 0x00
    assert Cw6303Charger(charging=True).status == Cw6303Charger.STATUS_CHARGE


def test_an_explicit_status_overrides_the_flag():
    # The bit meanings are not fully named yet, so a raw byte has to be settable
    # for experiments -- that is how the decode table was measured.
    charger = Cw6303Charger(charging=False, status=0xA0)
    assert charger.status == 0xA0
    charger = Cw6303Charger(charging=True, status=0x10)
    assert charger.status == 0x10


def test_a_sequential_read_puts_the_status_in_the_right_slot():
    charger = Cw6303Charger(charging=True)
    charger.write(0x01, bytes([0xB7, 0x87]))
    block = charger.read(0x00, 5)
    assert block[0x01] == 0xB7
    assert block[0x02] == 0x87
    assert block[0x03] == charger.STATUS_CHARGE
    assert block[0x04] == 0x00


# -- both halves share one bus ------------------------------------------------

def _open_drain(gpio, *pins):
    """OUTCFG = 2 for each pin, as ew_drv_sim_i2c.c configures the lines; a
    released line then reads the board's pull-up."""
    for pin in pins:
        offset = Gpio.CFG_BASE + (pin // 8) * 4
        nibble = (pin % 8) * 4
        gpio.storage[offset] = (gpio.storage.get(offset, 0) & ~(0xF << nibble)) | (4 << nibble)


def _set(gpio, pin, level):
    bank, bit = divmod(pin, 32)
    offset = (Gpio.WTSA, Gpio.WTSB)[bank] if level else (Gpio.WTCA, Gpio.WTCB)[bank]
    gpio.write(offset, 4, 1 << bit)


def _start(gpio):
    for pin, lvl in ((PMU_SDA_PIN, 1), (PMU_SCL_PIN, 1), (PMU_SDA_PIN, 0), (PMU_SCL_PIN, 0)):
        _set(gpio, pin, lvl)


def _stop(gpio):
    for pin, lvl in ((PMU_SDA_PIN, 0), (PMU_SCL_PIN, 1), (PMU_SDA_PIN, 1)):
        _set(gpio, pin, lvl)


def _send(gpio, value):
    for i in range(7, -1, -1):
        _set(gpio, PMU_SDA_PIN, (value >> i) & 1)
        _set(gpio, PMU_SCL_PIN, 1)
        _set(gpio, PMU_SCL_PIN, 0)
    _set(gpio, PMU_SCL_PIN, 1)          # the acknowledge, not a data bit
    _set(gpio, PMU_SCL_PIN, 0)


def _recv(gpio):
    bits = []
    for _ in range(8):
        _set(gpio, PMU_SCL_PIN, 1)
        bits.append(gpio.level(PMU_SDA_PIN))
        _set(gpio, PMU_SCL_PIN, 0)
    _set(gpio, PMU_SCL_PIN, 1)
    _set(gpio, PMU_SCL_PIN, 0)
    return int("".join(str(b) for b in bits), 2)


def test_the_gauge_and_the_charger_are_told_apart_on_the_wire():
    gpio = Gpio(0x40010000, 0x1000)
    bus = BitBangI2cBus(gpio, PMU_SCL_PIN, PMU_SDA_PIN)
    _open_drain(gpio, PMU_SCL_PIN, PMU_SDA_PIN)
    bus.devices[PMU_I2C_ADDRESS] = Cw6303Pmu(percent=55.0)
    bus.devices[CHARGER_I2C_ADDRESS] = Cw6303Charger(charging=True)

    # The gauge's state of charge.
    _start(gpio); _send(gpio, PMU_I2C_ADDRESS << 1); _send(gpio, Cw6303Pmu.SOC_H)
    _start(gpio); _send(gpio, (PMU_I2C_ADDRESS << 1) | 1)
    charge = _recv(gpio)
    _stop(gpio)

    # The charger's status, on the same two pins.
    _start(gpio); _send(gpio, CHARGER_I2C_ADDRESS << 1); _send(gpio, Cw6303Charger.STATUS)
    _start(gpio); _send(gpio, (CHARGER_I2C_ADDRESS << 1) | 1)
    status = _recv(gpio)
    _stop(gpio)

    assert charge == 55, charge
    assert status == Cw6303Charger.STATUS_CHARGE, hex(status)
    assert not bus.unanswered, dict(bus.unanswered)


def test_nothing_on_the_bus_goes_unanswered_now():
    gpio = Gpio(0x40010000, 0x1000)
    bus = BitBangI2cBus(gpio, PMU_SCL_PIN, PMU_SDA_PIN)
    _open_drain(gpio, PMU_SCL_PIN, PMU_SDA_PIN)
    bus.devices[PMU_I2C_ADDRESS] = Cw6303Pmu()
    bus.devices[CHARGER_I2C_ADDRESS] = Cw6303Charger()
    for address in (PMU_I2C_ADDRESS, CHARGER_I2C_ADDRESS):
        _start(gpio); _send(gpio, address << 1); _stop(gpio)
    assert dict(bus.unanswered) == {}


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
