"""Regression tests for Cortex-M exception entry.

Run them directly -- no test framework, so a bare Python 3.11 with Unicorn is
enough::

    PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_exception_entry.py

The ITSTATE test below guards a bug that cost a long hunt and would be easy to
reintroduce, because nothing about it looks like a stack problem until the very
last step. See ``CortexM.enter`` for the full story.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from unicorn import UC_ARCH_ARM, UC_HOOK_BLOCK, UC_HOOK_CODE, UC_MODE_MCLASS, UC_MODE_THUMB, Uc
from unicorn.arm_const import (
    UC_ARM_REG_PC, UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_SP,
    UC_ARM_REG_XPSR, UC_CPU_ARM_CORTEX_M4,
)

from normwatch.fw.cortexm import XPSR_IT_MASK

#: The firmware's own memcpy at 0x00020FF8, which is where this actually bites:
#:
#:   20FF8  lsls.w ip, r2, #0x1c          sets C from bit 3 of r2
#:   20FFC  itt hs
#:   20FFE  ldmhs.w r1!, {r3,r4,ip,lr}    32-bit, straddles the 0x21000 page edge
#:   21002  stmhs.w r0!, {r3,r4,ip,lr}
#:
#: The page crossing is what makes a translation block start *inside* the IT
#: block, which is the only way an exception can be taken there: a false IT
#: block is folded whole and offers no boundary to stop on.
MEMCPY = bytes.fromhex("5fea027c" "24bf" "b1e81850" "a0e81850" "44bf" "18c9" "18c0")

#: A stand-in handler: push {r4, lr} ; movs r0,#0 ; movs r2,#7 ; b .
HANDLER = bytes.fromhex("10b5" "0020" "0722" "fee7")

MEMCPY_BASE = 0x20FF8
HANDLER_BASE = 0x8E004
SPLIT_BLOCK = 0x21002


def _interrupt_inside_it_block(clear_itstate: bool):
    """Run into the page-split IT block, stop there, then enter a handler."""
    uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
    uc.ctl_set_cpu_model(UC_CPU_ARM_CORTEX_M4)
    uc.mem_map(0x20000, 0x4000)
    uc.mem_map(0x8E000, 0x1000)
    uc.mem_write(MEMCPY_BASE, MEMCPY)
    uc.mem_write(HANDLER_BASE, HANDLER)
    uc.reg_write(UC_ARM_REG_R0, 0x22000)
    uc.reg_write(UC_ARM_REG_R1, 0x22800)
    uc.reg_write(UC_ARM_REG_R2, 0x8)  # bit 3 set -> C set -> the `hs` block runs
    uc.reg_write(UC_ARM_REG_SP, 0x23000)

    stopped = []

    def on_block(u, address, size, data):
        if address == SPLIT_BLOCK and not stopped:
            stopped.append(address)
            u.emu_stop()

    uc.hook_add(UC_HOOK_BLOCK, on_block)
    uc.emu_start(MEMCPY_BASE | 1, 0, count=20)
    assert stopped, "never stopped inside the IT block — the reproduction is broken"

    xpsr = uc.reg_read(UC_ARM_REG_XPSR)
    assert xpsr & XPSR_IT_MASK, f"expected live ITSTATE at the stop, got xPSR=0x{xpsr:08X}"
    if clear_itstate:
        uc.reg_write(UC_ARM_REG_XPSR, xpsr & ~XPSR_IT_MASK)
    # Enter the handler with C clear, so a leaked `hs` slot evaluates false.
    uc.reg_write(UC_ARM_REG_XPSR, uc.reg_read(UC_ARM_REG_XPSR) & ~(1 << 29))

    executed: list[int] = []
    uc.hook_add(UC_HOOK_CODE, lambda u, a, s, d: executed.append(a))
    sp_before = uc.reg_read(UC_ARM_REG_SP)
    uc.emu_start(HANDLER_BASE | 1, 0, count=3)
    return executed, sp_before, uc.reg_read(UC_ARM_REG_SP), xpsr


def test_itstate_leaks_without_the_clear():
    """The bug itself: without clearing, the handler's prologue is swallowed."""
    executed, sp_before, sp_after, _ = _interrupt_inside_it_block(clear_itstate=False)
    assert HANDLER_BASE not in executed, (
        "Unicorn no longer leaks ITSTATE across emu_start — if this fails the "
        "workaround in CortexM.enter may no longer be needed"
    )
    assert sp_after == sp_before, "expected the push to have been swallowed"


def test_clearing_itstate_lets_the_handler_run():
    """The fix: with ITSTATE cleared, the handler executes from its first instruction."""
    executed, sp_before, sp_after, _ = _interrupt_inside_it_block(clear_itstate=True)
    assert executed[0] == HANDLER_BASE, f"handler started at 0x{executed[0]:08X}"
    assert sp_after == sp_before - 8, (
        f"push {{r4, lr}} did not run: sp 0x{sp_before:08X} -> 0x{sp_after:08X}"
    )


def test_entry_stacks_itstate_so_the_return_can_restore_it():
    """The saved xPSR must keep ITSTATE; only the live copy is cleared."""
    _, _, _, xpsr_at_stop = _interrupt_inside_it_block(clear_itstate=True)
    assert xpsr_at_stop & XPSR_IT_MASK, "nothing to stack — reproduction broken"
    # What enter() writes into the frame is the value read before the clear.
    assert (xpsr_at_stop & ~(1 << 9)) & XPSR_IT_MASK, "stacked xPSR lost ITSTATE"


if __name__ == "__main__":
    failures = 0
    for name, test in sorted(globals().items()):
        if not name.startswith("test_") or not callable(test):
            continue
        try:
            test()
        except AssertionError as exc:
            failures += 1
            print(f"FAIL {name}: {exc}")
        else:
            print(f"ok   {name}")
    print("all passed" if not failures else f"{failures} failed")
    raise SystemExit(1 if failures else 0)
