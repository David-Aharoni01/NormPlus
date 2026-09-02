"""Reading the interrupt-mask registers without the binding's overhead.

CortexM._masked reads FAULTMASK, PRIMASK and BASEPRI on almost every basic block
while FreeRTOS holds a critical section — 1,094,454 times per 40M instructions.
Through uc.reg_read that is 3,431 ns a time and was 36% of the emulator's wall
clock; straight to uc_reg_read with a preallocated buffer it is 1,022 ns.

It is the same C function either way, so this pins that it really is the same
answer — including after the firmware changes a mask, which is the case that
would break a naive cache.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_fast_regs.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from unicorn.arm_const import (UC_ARM_REG_BASEPRI, UC_ARM_REG_CONTROL,
                               UC_ARM_REG_FAULTMASK, UC_ARM_REG_PRIMASK,
                               UC_ARM_REG_SP)

from normwatch.fw import image as image_mod
from normwatch.fw.cortexm import _make_fast_reg_reader
from normwatch.fw.machine import Apollo3Machine

REPO = Path(__file__).resolve().parents[3]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
MASKS = (UC_ARM_REG_FAULTMASK, UC_ARM_REG_PRIMASK, UC_ARM_REG_BASEPRI)

_img = image_mod.load(IMAGE)


def machine():
    return Apollo3Machine(_img, log=lambda *a, **k: None, trace=None)


def test_it_engages_on_this_unicorn():
    m = machine()
    assert m.cortexm.fast_reg_reads, "fell back to uc.reg_read"


def test_it_agrees_with_the_supported_api():
    m = machine()
    for register in MASKS + (UC_ARM_REG_CONTROL, UC_ARM_REG_SP):
        assert m.cortexm._reg(register) == m.uc.reg_read(register), register


def test_it_still_agrees_after_the_values_change():
    # A cache without invalidation would pass the test above and fail this one.
    m = machine()
    for value in (1, 0, 0xE0, 0x40, 0):
        m.uc.reg_write(UC_ARM_REG_BASEPRI, value)
        assert m.cortexm._reg(UC_ARM_REG_BASEPRI) == m.uc.reg_read(UC_ARM_REG_BASEPRI)
        assert m.cortexm._reg(UC_ARM_REG_BASEPRI) & 0xFF == value
    for value in (1, 0):
        m.uc.reg_write(UC_ARM_REG_PRIMASK, value)
        assert m.cortexm._reg(UC_ARM_REG_PRIMASK) & 1 == value


def test_masking_follows_the_registers():
    m = machine()
    cm = m.cortexm
    m.uc.reg_write(UC_ARM_REG_PRIMASK, 0)
    m.uc.reg_write(UC_ARM_REG_FAULTMASK, 0)
    m.uc.reg_write(UC_ARM_REG_BASEPRI, 0)
    assert not cm._masked(20, 0x40)

    m.uc.reg_write(UC_ARM_REG_PRIMASK, 1)
    assert cm._masked(20, 0x40), "PRIMASK should mask a normal exception"
    m.uc.reg_write(UC_ARM_REG_PRIMASK, 0)

    m.uc.reg_write(UC_ARM_REG_BASEPRI, 0x20)
    assert cm._masked(20, 0x40), "priority below BASEPRI should be masked"
    assert not cm._masked(20, 0x10), "priority above BASEPRI should get through"
    m.uc.reg_write(UC_ARM_REG_BASEPRI, 0)
    assert not cm._masked(20, 0x40)


def test_nmi_is_never_masked():
    m = machine()
    m.uc.reg_write(UC_ARM_REG_PRIMASK, 1)
    m.uc.reg_write(UC_ARM_REG_FAULTMASK, 1)
    m.uc.reg_write(UC_ARM_REG_BASEPRI, 0x10)
    assert not m.cortexm._masked(2, -2), "NMI must always be deliverable"


def test_it_falls_back_rather_than_raising_on_an_unfamiliar_binding():
    class Odd:
        def reg_read(self, register):
            return 0x1234

    read, fast = _make_fast_reg_reader(Odd())
    assert not fast, "should not claim the fast path on an object it cannot use"
    assert read(UC_ARM_REG_PRIMASK) == 0x1234


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
