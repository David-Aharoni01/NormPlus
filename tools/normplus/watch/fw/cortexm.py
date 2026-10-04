"""ARMv7-M private peripheral block: SCB, SysTick and the NVIC.

Unicorn's M-class core executes instructions and banks MSP/PSP, but it provides
neither the PPB register block nor the exception machinery — a ``bx lr`` with an
``EXC_RETURN`` value in LR raises ``UC_ERR_EXCEPTION`` and leaves PC at
``0xFFFFFFF8``. So exception *entry* and *return* are implemented here in
software, which is what makes the firmware's FreeRTOS 9 port able to run: its
scheduler is built on SVCall (start the first task), PendSV (context switch) and
SysTick (the tick).

Deliberate simplification: the exception frame is always the basic 8-word one and
``EXC_RETURN`` always has bit 4 set ("no floating-point state"). The FreeRTOS
Cortex-M4F port tests that bit to decide whether to save ``s16-s31``, so the model
stays self-consistent; what it gives up is lazy FP stacking across preemption.
"""

from __future__ import annotations

import ctypes
import struct
from typing import Optional

from unicorn.arm_const import (
    UC_ARM_REG_BASEPRI,
    UC_ARM_REG_CONTROL,
    UC_ARM_REG_FAULTMASK,
    UC_ARM_REG_IPSR,
    UC_ARM_REG_LR,
    UC_ARM_REG_MSP,
    UC_ARM_REG_PC,
    UC_ARM_REG_PRIMASK,
    UC_ARM_REG_PSP,
    UC_ARM_REG_R0,
    UC_ARM_REG_R1,
    UC_ARM_REG_R12,
    UC_ARM_REG_R2,
    UC_ARM_REG_R3,
    UC_ARM_REG_SP,
    UC_ARM_REG_XPSR,
)

PPB_BASE = 0xE0000000
PPB_SIZE = 0x00100000

#: The IT bits in xPSR: ``ICI/IT[7:2]`` at [15:10] and ``IT[1:0]`` at [26:25].
#: Exception entry saves them in the stacked xPSR and then clears them, and the
#: return restores them. See enter() for why skipping that is fatal.
XPSR_IT_MASK = (0x3F << 10) | (0x3 << 25)

# ── exception numbers ────────────────────────────────────────────────────────
EXC_RESET = 1
EXC_NMI = 2
EXC_HARDFAULT = 3
EXC_MEMMANAGE = 4
EXC_BUSFAULT = 5
EXC_USAGEFAULT = 6
EXC_SVCALL = 11
EXC_DEBUGMON = 12
EXC_PENDSV = 14
EXC_SYSTICK = 15
EXC_IRQ0 = 16

#: Apollo3 Blue has 32 external interrupts.
NUM_IRQ = 32

#: Apollo3 implements 3 priority bits (``__NVIC_PRIO_BITS``), so the low 5 bits of
#: every priority byte are RAZ/WI. This is not cosmetic: FreeRTOS's
#: ``xPortStartScheduler`` writes 0xFF to a priority register and reads it back to
#: discover the implemented width, then asserts on the result
#: (``FreeRTOS_AMapollo3/port.c:479``). A model that stores all 8 bits fails that
#: assert and the scheduler never starts.
PRIO_BITS = 3
PRIO_MASK = (0xFF << (8 - PRIO_BITS)) & 0xFF

EXC_NAMES = {
    EXC_NMI: "NMI",
    EXC_HARDFAULT: "HardFault",
    EXC_MEMMANAGE: "MemManage",
    EXC_BUSFAULT: "BusFault",
    EXC_USAGEFAULT: "UsageFault",
    EXC_SVCALL: "SVCall",
    EXC_DEBUGMON: "DebugMon",
    EXC_PENDSV: "PendSV",
    EXC_SYSTICK: "SysTick",
}

#: Apollo3 IRQ names, in vector order (``apollo3.h`` ``IRQn_Type``).
#: ``SOFTWARE0`` sits at 21, *before* the STIMER entries — so the eight STIMER
#: compare interrupts are 23..30, not 22..29. Getting this off by one means the
#: FreeRTOS tick (driven by STIMER compare 0 = IRQ23) is pended on the wrong line
#: and never fires, which looks exactly like a dead scheduler.
APOLLO3_IRQ_NAMES = [
    "BROWNOUT", "WDT", "RTC", "VCOMP", "IOSLAVE", "IOSLAVE_ACC", "IOMSTR0", "IOMSTR1",
    "IOMSTR2", "IOMSTR3", "IOMSTR4", "IOMSTR5", "BLE", "GPIO", "CTIMER", "UART0",
    "UART1", "SCARD", "ADC", "PDM", "MSPI", "SOFTWARE0", "STIMER", "STIMER_CMPR0",
    "STIMER_CMPR1", "STIMER_CMPR2", "STIMER_CMPR3", "STIMER_CMPR4", "STIMER_CMPR5",
    "STIMER_CMPR6", "STIMER_CMPR7", "CLKGEN",
]

#: IRQ number of the RTC (its alarm; the firmware's ISR is at 0x0008DFE8).
IRQ_RTC = 2
#: IRQ number of the BLE controller interrupt.
IRQ_BLE = 12
#: IRQ number of the GPIO block (every button and sensor line shares it).
IRQ_GPIO = 13
#: IRQ number of the MSPI controller (SPI NAND, PSRAM and the display panel).
IRQ_MSPI = 20
#: IRQ number of STIMER compare 0; compares 0..7 are consecutive from here.
IRQ_STIMER_CMPR0 = 23


def irq_name(irq: int) -> str:
    if 0 <= irq < len(APOLLO3_IRQ_NAMES):
        return APOLLO3_IRQ_NAMES[irq]
    return f"IRQ{irq}"


def exception_name(exc: int) -> str:
    if exc >= EXC_IRQ0:
        return irq_name(exc - EXC_IRQ0)
    return EXC_NAMES.get(exc, f"EXC{exc}")


def _make_fast_reg_reader(uc):
    """A register read that skips the Unicorn binding's per-call overhead.

    ``uc.reg_read`` costs about 1,161 ns, and almost none of that is the actual
    call: the binding resolves a register class through a generator on every
    invocation, then marshals a fresh ctypes value. Going straight to
    ``uc_reg_read`` with a preallocated buffer is 279 ns for the same answer,
    and ``_masked`` — which reads three of these on almost every basic block —
    drops from 3,431 ns to 1,022 ns. That was 36% of the emulator's wall clock.

    Nothing about the semantics changes; this is the same C function the binding
    calls. It does reach into the binding's internals, so it is written to fail
    back to the supported API rather than break on a future Unicorn.
    """
    try:
        from unicorn.unicorn_py3 import unicorn as _ucmod

        # A separate CDLL handle, so setting argtypes cannot disturb the
        # binding's own function objects.
        lib = ctypes.CDLL(_ucmod.uclib._name)
        fn = lib.uc_reg_read
        fn.restype = ctypes.c_int
        fn.argtypes = (ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p)
        handle = uc._uch
        buf = ctypes.c_uint32(0)
        ref = ctypes.byref(buf)

        def read(register, _fn=fn, _h=handle, _ref=ref, _buf=buf):
            _fn(_h, register, _ref)
            return _buf.value

        # Prove it agrees with the supported path before trusting it. PC is on
        # the list because the run loop reads it after every emu_start, and a
        # wrong answer there would send emulation somewhere rather than merely
        # misreading a mask bit.
        for probe in (UC_ARM_REG_PRIMASK, UC_ARM_REG_BASEPRI, UC_ARM_REG_FAULTMASK,
                      UC_ARM_REG_PC):
            if read(probe) != uc.reg_read(probe):
                return uc.reg_read, False
        return read, True
    except Exception:
        return uc.reg_read, False


class CortexM:
    """PPB registers plus software exception entry/return."""

    def __init__(self, machine, log=print) -> None:
        self.machine = machine
        self.log = log
        #: See _make_fast_reg_reader. Falls back to uc.reg_read if the binding
        #: does not look the way we expect.
        self._reg, self.fast_reg_reads = _make_fast_reg_reader(machine.uc)
        #: The same reader, for callers outside this class — the run loop reads
        #: PC through it after every emu_start.
        self.read_register = self._reg

        # SCB
        self.vtor = 0
        self.icsr = 0
        self.aircr = 0xFA050000
        self.scr = 0
        self.ccr = 0
        self.cpacr = 0
        self.shcsr = 0
        self.shpr = bytearray(12)  # priorities for exceptions 4..15

        # SysTick
        self.systick_ctrl = 0
        self.systick_load = 0
        self.systick_val = 0
        self.systick_countflag = False
        self._systick_acc = 0

        # NVIC
        self.irq_enabled = 0
        self.irq_pending = 0
        self.irq_active = 0
        self.irq_priority = bytearray(NUM_IRQ)

        # Bookkeeping for the triage report.
        self.exception_counts: dict[int, int] = {}
        self.active_stack: list[int] = []
        #: Cached min priority across active_stack, so the per-block pending check
        #: does not have to walk it.
        self._active_priority = 256

    #: Names for the PPB registers this model implements, for tracing output.
    REG_NAMES = {
        0xE000E010: "SYST_CSR", 0xE000E014: "SYST_RVR", 0xE000E018: "SYST_CVR",
        0xE000E01C: "SYST_CALIB", 0xE000ED00: "SCB_CPUID", 0xE000ED04: "SCB_ICSR",
        0xE000ED08: "SCB_VTOR", 0xE000ED0C: "SCB_AIRCR", 0xE000ED10: "SCB_SCR",
        0xE000ED14: "SCB_CCR", 0xE000ED18: "SCB_SHPR1", 0xE000ED1C: "SCB_SHPR2",
        0xE000ED20: "SCB_SHPR3", 0xE000ED24: "SCB_SHCSR", 0xE000ED88: "SCB_CPACR",
        0xE000EF00: "NVIC_STIR",
    }

    def reg_name(self, addr: int) -> str:
        if addr in self.REG_NAMES:
            return self.REG_NAMES[addr]
        if 0xE000E100 <= addr < 0xE000E120:
            return f"NVIC_ISER{(addr - 0xE000E100) // 4}"
        if 0xE000E180 <= addr < 0xE000E1A0:
            return f"NVIC_ICER{(addr - 0xE000E180) // 4}"
        if 0xE000E200 <= addr < 0xE000E220:
            return f"NVIC_ISPR{(addr - 0xE000E200) // 4}"
        if 0xE000E280 <= addr < 0xE000E2A0:
            return f"NVIC_ICPR{(addr - 0xE000E280) // 4}"
        if 0xE000E400 <= addr < 0xE000E4F0:
            return f"NVIC_IPR{(addr - 0xE000E400) // 4}"
        return f"PPB+0x{addr - PPB_BASE:05X}"

    # ── PPB register access ──────────────────────────────────────────────────

    def read(self, addr: int, size: int) -> int:
        if 0xE000E010 <= addr <= 0xE000E01F:
            return self._systick_read(addr)
        if 0xE000E100 <= addr <= 0xE000E4EF:
            return self._nvic_read(addr)
        if 0xE000ED00 <= addr <= 0xE000ED8F:
            return self._scb_read(addr)
        if addr == 0xE000EF00:  # STIR is write-only
            return 0
        # CPUID-ish / debug / DWT / ITM: harmless zeros.
        return 0

    def write(self, addr: int, size: int, value: int) -> None:
        """PPB write, then re-tell the native hook what is pending.

        This is the only thing that can change the pending set *during* a slice
        — everything else (the tick, exception entry and return, a device
        raising an IRQ) happens between slices, where the run loop refreshes it
        anyway. Wrapping rather than appending to _write because that method has
        a dozen early returns and one of them would eventually be missed.
        """
        self._write(addr, size, value)
        native = self.machine.native_hook
        if native is not None:
            self.refresh_native_pending()

    def refresh_native_pending(self) -> None:
        native = self.machine.native_hook
        if native is None:
            return
        exc, priority = self.best_candidate()
        if exc is None or not self.preempts(priority):
            native.set_pending(None)
        else:
            native.set_pending(priority, is_nmi=(exc == EXC_NMI))

    def _write(self, addr: int, size: int, value: int) -> None:
        if 0xE000E010 <= addr <= 0xE000E01F:
            self._systick_write(addr, value)
            return
        if 0xE000E100 <= addr <= 0xE000E4EF:
            self._nvic_write(addr, value, size)
            return
        if 0xE000ED00 <= addr <= 0xE000ED8F:
            self._scb_write(addr, value, size)
            return
        if addr == 0xE000EF00:  # STIR: software-triggered interrupt
            self.set_pending_irq(value & 0xFF)
            return

    # -- SysTick --

    def _systick_read(self, addr: int) -> int:
        if addr == 0xE000E010:
            value = self.systick_ctrl | (0x10000 if self.systick_countflag else 0)
            self.systick_countflag = False  # COUNTFLAG clears on read
            return value
        if addr == 0xE000E014:
            return self.systick_load
        if addr == 0xE000E018:
            return self.systick_val
        if addr == 0xE000E01C:
            # TENMS: 10 ms of the reference clock. Apollo3 runs the core at 48 MHz.
            return 48000000 // 100
        return 0

    def _systick_write(self, addr: int, value: int) -> None:
        # Any of these moves the wrap the current slice was sized against.
        self.machine.cut_slice()
        if addr == 0xE000E010:
            self.systick_ctrl = value & 0x7
        elif addr == 0xE000E014:
            self.systick_load = value & 0x00FFFFFF
        elif addr == 0xE000E018:
            self.systick_val = 0  # any write clears the counter and COUNTFLAG
            self.systick_countflag = False

    @property
    def systick_enabled(self) -> bool:
        return bool(self.systick_ctrl & 1)

    @property
    def systick_irq_enabled(self) -> bool:
        return bool(self.systick_ctrl & 2)

    # -- NVIC --

    def _nvic_read(self, addr: int) -> int:
        if 0xE000E100 <= addr < 0xE000E120:  # ISER
            return self._bank(self.irq_enabled, addr - 0xE000E100)
        if 0xE000E180 <= addr < 0xE000E1A0:  # ICER
            return self._bank(self.irq_enabled, addr - 0xE000E180)
        if 0xE000E200 <= addr < 0xE000E220:  # ISPR
            return self._bank(self.irq_pending, addr - 0xE000E200)
        if 0xE000E280 <= addr < 0xE000E2A0:  # ICPR
            return self._bank(self.irq_pending, addr - 0xE000E280)
        if 0xE000E300 <= addr < 0xE000E320:  # IABR
            return self._bank(self.irq_active, addr - 0xE000E300)
        if 0xE000E400 <= addr < 0xE000E4F0:  # IPR (byte per IRQ)
            base = addr - 0xE000E400
            out = 0
            for i in range(4):
                if base + i < NUM_IRQ:
                    out |= self.irq_priority[base + i] << (8 * i)
            return out
        return 0

    def _nvic_write(self, addr: int, value: int, size: int = 4) -> None:
        if 0xE000E100 <= addr < 0xE000E120:
            self.irq_enabled |= value << (32 * ((addr - 0xE000E100) // 4))
        elif 0xE000E180 <= addr < 0xE000E1A0:
            self.irq_enabled &= ~(value << (32 * ((addr - 0xE000E180) // 4)))
        elif 0xE000E200 <= addr < 0xE000E220:
            self.irq_pending |= value << (32 * ((addr - 0xE000E200) // 4))
        elif 0xE000E280 <= addr < 0xE000E2A0:
            self.irq_pending &= ~(value << (32 * ((addr - 0xE000E280) // 4)))
        elif 0xE000E400 <= addr < 0xE000E4F0:
            # IPR is byte addressable and this firmware writes it a byte at a
            # time. Assuming a 4-byte write here means a store to one IRQ's
            # priority silently zeroes the next three — which is how the RTOS
            # tick ended up at priority 0x00, above FreeRTOS's BASEPRI mask, free
            # to preempt critical sections and corrupt its task lists.
            base = addr - 0xE000E400
            for i in range(size):
                if base + i < NUM_IRQ:
                    self.irq_priority[base + i] = (value >> (8 * i)) & PRIO_MASK

    @staticmethod
    def _bank(bits: int, byte_offset: int) -> int:
        return (bits >> (32 * (byte_offset // 4))) & 0xFFFFFFFF

    # -- SCB --

    def _scb_read(self, addr: int) -> int:
        if addr == 0xE000ED00:
            return 0x410FC241  # Cortex-M4 r0p1
        if addr == 0xE000ED04:  # ICSR
            value = self.machine.uc.reg_read(UC_ARM_REG_IPSR) & 0x1FF
            if self.icsr & (1 << 28):
                value |= 1 << 28  # PENDSVSET
            if self.icsr & (1 << 26):
                value |= 1 << 26  # PENDSTSET
            return value
        if addr == 0xE000ED08:
            return self.vtor
        if addr == 0xE000ED0C:
            return self.aircr
        if addr == 0xE000ED10:
            return self.scr
        if addr == 0xE000ED14:
            return self.ccr
        if 0xE000ED18 <= addr <= 0xE000ED23:
            base = addr - 0xE000ED18
            return int.from_bytes(self.shpr[base : base + 4], "little")
        if addr == 0xE000ED24:
            return self.shcsr
        if addr == 0xE000ED88:
            return self.cpacr
        return 0

    def _scb_write(self, addr: int, value: int, size: int = 4) -> None:
        if addr == 0xE000ED04:  # ICSR
            if value & (1 << 28):
                self.icsr |= 1 << 28  # PENDSVSET
            if value & (1 << 27):
                self.icsr &= ~(1 << 28)  # PENDSVCLR
            if value & (1 << 26):
                self.icsr |= 1 << 26  # PENDSTSET
            if value & (1 << 25):
                self.icsr &= ~(1 << 26)  # PENDSTCLR
        elif addr == 0xE000ED08:
            self.vtor = value & 0xFFFFFF80
        elif addr == 0xE000ED0C:
            if (value >> 16) == 0x05FA:
                if value & 0x4:  # SYSRESETREQ
                    self.machine.request_reset()
                self.aircr = (0xFA05 << 16) | (value & 0x0700)
        elif addr == 0xE000ED10:
            self.scr = value
        elif addr == 0xE000ED14:
            self.ccr = value
        elif 0xE000ED18 <= addr <= 0xE000ED23:
            # SHPR is byte addressable too; same trap as NVIC_IPR above.
            base = addr - 0xE000ED18
            for i in range(size):
                if base + i < len(self.shpr):
                    self.shpr[base + i] = (value >> (8 * i)) & PRIO_MASK
        elif addr == 0xE000ED24:
            self.shcsr = value
        elif addr == 0xE000ED88:
            self.cpacr = value

    # ── time ─────────────────────────────────────────────────────────────────

    def next_deadline(self) -> Optional[int]:
        """Cycles until SysTick next wraps, or None while it is stopped.

        This firmware drives the RTOS tick from an STIMER compare rather than
        SysTick, so in practice the answer is None for the whole run — but the
        model implements SysTick, so it has to be able to say when it matters.
        """
        if not self.systick_enabled or self.systick_load == 0:
            return None
        return (self.systick_load + 1) - self._systick_acc

    def advance(self, cycles: int) -> None:
        """Advance SysTick by *cycles* core clocks, pending the exception on wrap."""
        if not self.systick_enabled or self.systick_load == 0:
            return
        self._systick_acc += cycles
        reload_at = self.systick_load + 1
        if self._systick_acc >= reload_at:
            wraps = self._systick_acc // reload_at
            self._systick_acc %= reload_at
            self.systick_countflag = True
            self.systick_val = self.systick_load - self._systick_acc
            if self.systick_irq_enabled and wraps > 0:
                self.icsr |= 1 << 26  # PENDSTSET
        else:
            self.systick_val = self.systick_load - self._systick_acc

    # ── interrupt sources ────────────────────────────────────────────────────

    def set_pending_irq(self, irq: int) -> None:
        if 0 <= irq < NUM_IRQ:
            self.irq_pending |= 1 << irq

    def assert_irq(self, irq: int) -> bool:
        """A peripheral holding its interrupt line high; False if it was not pended.

        Every source here is level-sensitive (status AND enable), and the NVIC
        pends a level only "when the interrupt signal is HIGH and the interrupt
        is not active"; a line still high when its handler returns is pended
        again then (Cortex-M4 Devices Generic User Guide, 4.2.9 and 4.2.10,
        and :meth:`exception_return`). Pending it while its own handler runs,
        before the handler has cleared the source, takes the interrupt twice
        -- which is what a clock poll a few instructions into a handler did.
        """
        if not 0 <= irq < NUM_IRQ:
            return False
        if (self.irq_active >> irq) & 1:
            return False
        self.irq_pending |= 1 << irq
        return True

    def clear_pending_irq(self, irq: int) -> None:
        self.irq_pending &= ~(1 << irq)

    def set_pendsv(self) -> None:
        self.icsr |= 1 << 28

    # ── priority + selection ─────────────────────────────────────────────────

    def _priority_of(self, exc: int) -> int:
        if exc == EXC_NMI:
            return -2
        if exc == EXC_HARDFAULT:
            return -1
        if exc >= EXC_IRQ0:
            return self.irq_priority[exc - EXC_IRQ0]
        if 4 <= exc <= 15:
            return self.shpr[exc - 4]
        return 0

    def _masked(self, exc: int, priority: int) -> bool:
        # Called on almost every basic block while FreeRTOS holds a critical
        # section, so the three reads here were 36% of the emulator's run time
        # until they stopped going through the binding. See _make_fast_reg_reader.
        read = self._reg
        if exc == EXC_NMI:
            return False
        if read(UC_ARM_REG_FAULTMASK) & 1 and exc != EXC_NMI:
            return True
        if read(UC_ARM_REG_PRIMASK) & 1 and priority >= 0:
            return True
        basepri = read(UC_ARM_REG_BASEPRI) & 0xFF
        if basepri and priority >= basepri:
            return True
        return False

    def best_candidate(self):
        """The highest-priority pending, enabled exception, ignoring the masks.

        Split out so the native hook can be told what to watch for without
        holding a second copy of this selection. It only needs the priority; the
        choice of exception stays here and is made once, after it stops us.
        """
        best_exc = None
        best_priority = 256

        icsr = self.icsr
        if icsr & 0x04000000:  # PENDSTSET
            priority = self.shpr[EXC_SYSTICK - 4]
            if priority < best_priority:
                best_priority, best_exc = priority, EXC_SYSTICK
        if icsr & 0x10000000:  # PENDSVSET
            priority = self.shpr[EXC_PENDSV - 4]
            if priority < best_priority:
                best_priority, best_exc = priority, EXC_PENDSV

        ready = self.irq_pending & self.irq_enabled
        while ready:
            lowest = ready & -ready
            irq = lowest.bit_length() - 1
            ready ^= lowest
            priority = self.irq_priority[irq]
            if priority < best_priority:
                best_priority, best_exc = priority, EXC_IRQ0 + irq
        return best_exc, best_priority

    def preempts(self, priority: int) -> bool:
        """Whether *priority* may interrupt whatever is running."""
        return not (self.active_stack and priority >= self._active_priority)

    def pending_exception(self) -> Optional[int]:
        """Highest-priority pending, enabled and unmasked exception, or ``None``.

        Called once per basic block on the Python hook path, so it is written to
        touch the CPU's mask registers only when there is actually a candidate.
        """
        best_exc = None
        best_priority = 256

        icsr = self.icsr
        if icsr & 0x04000000:  # PENDSTSET
            priority = self.shpr[EXC_SYSTICK - 4]
            if priority < best_priority:
                best_priority, best_exc = priority, EXC_SYSTICK
        if icsr & 0x10000000:  # PENDSVSET
            priority = self.shpr[EXC_PENDSV - 4]
            if priority < best_priority:
                best_priority, best_exc = priority, EXC_PENDSV

        ready = self.irq_pending & self.irq_enabled
        while ready:
            lowest = ready & -ready
            irq = lowest.bit_length() - 1
            ready ^= lowest
            priority = self.irq_priority[irq]
            if priority < best_priority:
                best_priority, best_exc = priority, EXC_IRQ0 + irq

        if best_exc is None:
            return None
        # Do not preempt a handler of equal or higher priority.
        if self.active_stack and best_priority >= self._active_priority:
            return None
        if self._masked(best_exc, best_priority):
            return None
        return best_exc

    # ── entry and return ─────────────────────────────────────────────────────

    def _vector(self, exc: int) -> int:
        return self.machine.read32(self.vtor + 4 * exc)

    def enter(self, exc: int) -> int:
        """Perform exception entry; returns the handler address to resume at."""
        uc = self.machine.uc
        control = uc.reg_read(UC_ARM_REG_CONTROL)
        in_handler = (uc.reg_read(UC_ARM_REG_IPSR) & 0x1FF) != 0
        use_psp = bool(control & 2) and not in_handler

        sp = uc.reg_read(UC_ARM_REG_PSP) if use_psp else uc.reg_read(UC_ARM_REG_MSP)
        xpsr = uc.reg_read(UC_ARM_REG_XPSR)

        # 8-byte stack alignment (STKALIGN); record the adjustment in xPSR bit 9.
        aligned_xpsr = xpsr & ~(1 << 9)
        if sp & 4:
            sp -= 4
            aligned_xpsr |= 1 << 9

        sp -= 32
        frame = struct.pack(
            "<8I",
            uc.reg_read(UC_ARM_REG_R0) & 0xFFFFFFFF,
            uc.reg_read(UC_ARM_REG_R1) & 0xFFFFFFFF,
            uc.reg_read(UC_ARM_REG_R2) & 0xFFFFFFFF,
            uc.reg_read(UC_ARM_REG_R3) & 0xFFFFFFFF,
            uc.reg_read(UC_ARM_REG_R12) & 0xFFFFFFFF,
            uc.reg_read(UC_ARM_REG_LR) & 0xFFFFFFFF,
            uc.reg_read(UC_ARM_REG_PC) & 0xFFFFFFFE,
            aligned_xpsr & 0xFFFFFFFF,
        )
        uc.mem_write(sp, frame)

        # Clear ITSTATE. The frame above keeps it, and exception_return puts it
        # back, so an interrupted IT block resumes correctly -- but it must not
        # stay live into the handler.
        #
        # This is not a detail. If it leaks, the handler's first instructions
        # inherit the interrupted block's condition and are silently skipped
        # when it is false. It only ever bites where a translation block starts
        # *inside* an IT block, because a false IT block is folded whole; in
        # this firmware that is the page-crossing `itt hs` in memcpy at
        # 0x00020FFC, whose conditional instructions straddle 0x00021000. An
        # exception landing there swallowed the STIMER handler's
        # `push {r4, lr}`, so its closing `pop {r4, lr}` took LR from the stale
        # frame the first SVCall left at the top of MSP, and the tick handler
        # returned into 0xC0000000 a few instructions later.
        uc.reg_write(UC_ARM_REG_XPSR, xpsr & ~XPSR_IT_MASK)

        if use_psp:
            uc.reg_write(UC_ARM_REG_PSP, sp)
        else:
            uc.reg_write(UC_ARM_REG_MSP, sp)

        # Handler mode always runs on MSP.
        exc_return = 0xFFFFFFF1 if in_handler else (0xFFFFFFFD if use_psp else 0xFFFFFFF9)
        uc.reg_write(UC_ARM_REG_LR, exc_return)
        uc.reg_write(UC_ARM_REG_CONTROL, control & ~2)
        uc.reg_write(UC_ARM_REG_IPSR, exc)
        uc.reg_write(UC_ARM_REG_SP, uc.reg_read(UC_ARM_REG_MSP))

        # Acknowledge the source.
        if exc == EXC_SYSTICK:
            self.icsr &= ~(1 << 26)
        elif exc == EXC_PENDSV:
            self.icsr &= ~(1 << 28)
        elif exc >= EXC_IRQ0:
            irq = exc - EXC_IRQ0
            self.irq_pending &= ~(1 << irq)
            self.irq_active |= 1 << irq

        self.active_stack.append(exc)
        self._active_priority = min(self._active_priority, self._priority_of(exc))
        self.exception_counts[exc] = self.exception_counts.get(exc, 0) + 1
        handler = self._vector(exc)
        if handler in (0, 0xFFFFFFFF):
            raise RuntimeError(
                f"vector for {exception_name(exc)} (exc {exc}) is 0x{handler:08X} "
                f"— VTOR=0x{self.vtor:08X}"
            )
        # Publish the handler address, the same way exception_return publishes the
        # thread address. Entry has just rewritten CONTROL, IPSR and the banked
        # stack pointer, and leaving PC pointing into the interrupted code while
        # those change lets the CPU resume from a stale translation.
        uc.reg_write(UC_ARM_REG_PC, handler & 0xFFFFFFFE)
        # Entry has just consumed the pending bit and pushed an active priority,
        # so the native hook's idea of what to watch for is now stale. Leaving it
        # stale makes the hook stop on the handler's very first block, and the
        # poll that follows re-arms the interrupt before the handler has had a
        # chance to clear its source -- which is every peripheral IRQ taken
        # exactly twice.
        self.refresh_native_pending()
        return handler

    def exception_return(self, exc_return: int) -> int:
        """Unwind an exception; returns the thread address to resume at."""
        uc = self.machine.uc
        if self.active_stack:
            exc = self.active_stack.pop()
            if exc >= EXC_IRQ0:
                self.irq_active &= ~(1 << (exc - EXC_IRQ0))
                # Its line was not sampled while it was active (assert_irq):
                # a source the handler left asserted pends it again now.
                self.machine.resample_irq(exc - EXC_IRQ0)
            self._active_priority = (
                min(self._priority_of(e) for e in self.active_stack) if self.active_stack else 256
            )

        mode = exc_return & 0xF
        return_to_psp = mode == 0xD
        sp = uc.reg_read(UC_ARM_REG_PSP) if return_to_psp else uc.reg_read(UC_ARM_REG_MSP)

        frame = bytes(uc.mem_read(sp, 32))
        r0, r1, r2, r3, r12, lr, pc, xpsr = struct.unpack("<8I", frame)
        sp += 32
        if xpsr & (1 << 9):
            sp += 4  # undo the entry alignment

        uc.reg_write(UC_ARM_REG_R0, r0)
        uc.reg_write(UC_ARM_REG_R1, r1)
        uc.reg_write(UC_ARM_REG_R2, r2)
        uc.reg_write(UC_ARM_REG_R3, r3)
        uc.reg_write(UC_ARM_REG_R12, r12)
        uc.reg_write(UC_ARM_REG_LR, lr)
        uc.reg_write(UC_ARM_REG_XPSR, xpsr & ~(1 << 9))

        control = uc.reg_read(UC_ARM_REG_CONTROL)
        if return_to_psp:
            uc.reg_write(UC_ARM_REG_PSP, sp)
            uc.reg_write(UC_ARM_REG_CONTROL, control | 2)
        else:
            uc.reg_write(UC_ARM_REG_MSP, sp)
            uc.reg_write(UC_ARM_REG_CONTROL, control & ~2)
        uc.reg_write(UC_ARM_REG_SP, sp)
        uc.reg_write(UC_ARM_REG_IPSR, 0 if mode != 0x1 else (self.active_stack[-1] if self.active_stack else 0))
        # Publish the restored PC so the CPU's view matches the caller's, even if
        # the caller never gets as far as resuming execution.
        uc.reg_write(UC_ARM_REG_PC, pc & 0xFFFFFFFE)
        # Popping an active priority can make something deliverable that was not
        # a moment ago, so the native hook has to be told before it runs again.
        self.refresh_native_pending()
        return pc

    # ── reporting ────────────────────────────────────────────────────────────

    def summary(self) -> str:
        if not self.exception_counts:
            return "  exceptions: none taken"
        rows = sorted(self.exception_counts.items(), key=lambda kv: -kv[1])
        out = ["  exceptions taken:"]
        out += [f"    {exception_name(exc):<16} {count}" for exc, count in rows]
        return "\n".join(out)
