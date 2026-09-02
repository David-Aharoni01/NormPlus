"""The Apollo3 Blue machine: memory map, CPU, MMIO bus and the run loop.

Memory map (``docs/firmware.md`` §4)::

    0x00000000 - 0x000FFFFF   1 MB internal flash  (application linked at 0x00020000)
    0x08000000 - 0x08000FFF   boot ROM window      (shimmed, see bootrom.py)
    0x10000000 - 0x1005FFFF   384 KB SRAM
    0x40000000 - 0x4FFFFFFF   APB peripherals
    0x50000000 - 0x5FFFFFFF   AHB peripherals (IOM, BLEIF, MSPI)
    0xE0000000 - 0xE00FFFFF   private peripheral block (see cortexm.py)

The first 128 KB of flash holds a first-stage bootloader we do not have, so
execution starts directly at the application's reset vector.
"""

from __future__ import annotations

import collections
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from unicorn import (
    UC_ARCH_ARM,
    UC_ERR_EXCEPTION,
    UC_HOOK_BLOCK,
    UC_HOOK_INTR,
    UC_HOOK_MEM_UNMAPPED,
    UC_MODE_MCLASS,
    UC_MODE_THUMB,
    Uc,
    UcError,
)
from unicorn.arm_const import (
    UC_ARM_REG_LR,
    UC_ARM_REG_MSP,
    UC_ARM_REG_PC,
    UC_ARM_REG_SP,
    UC_CPU_ARM_CORTEX_M4,
)

from . import image as image_mod
from .bootrom import BootRom
from .cortexm import CortexM, exception_name
from .peripherals import Bus, CtimerBlock, Uart, build_apollo3_bus

FLASH_BASE = 0x00000000
FLASH_SIZE = 0x00100000
SRAM_BASE = 0x10000000
SRAM_SIZE = 0x00060000

MMIO_WINDOWS = (
    (0x40000000, 0x10000000),
    (0x50000000, 0x10000000),
    (0xE0000000, 0x00100000),
    # JEDEC / CoreSight identification block, read by the HAL's device-info call.
    (0xF0000000, 0x00001000),
)

#: The ARMv7-M private peripheral block, handled by CortexM rather than the bus.
PPB_RANGE = (0xE0000000, 0xE0100000)

#: EXC_RETURN values land here when the CPU tries to "branch" to them.
EXC_RETURN_MARKER = 0xFFFFFFF0

#: QEMU's EXCP_EXCEPTION_EXIT, reported through the interrupt hook when an
#: M-profile exception return is executed.
EXCP_EXCEPTION_EXIT = 8

#: ICSR PENDSVSET | PENDSTSET — a cheap test for "an exception may be pending".
_PEND_BITS = (1 << 28) | (1 << 26)


@dataclass
class StopReason:
    kind: str  # "budget" | "fault" | "stuck" | "reset" | "halt"
    detail: str = ""
    pc: int = 0
    faulting_address: Optional[int] = None


@dataclass
class RunStats:
    instructions: int = 0
    blocks: int = 0
    wall_seconds: float = 0.0
    coverage: set[int] = field(default_factory=set)
    stop: Optional[StopReason] = None


class Watchpoint:
    """A memory location being watched, checked once per basic block.

    See :meth:`Apollo3Machine.watch` for why this exists rather than a Unicorn
    memory hook.
    """

    __slots__ = ("address", "size", "label", "on_change", "value", "changes")

    def __init__(self, address: int, size: int, label: str = "", on_change=None):
        self.address = address
        self.size = size
        self.label = label
        self.on_change = on_change
        self.value: Optional[bytes] = None
        #: ``(instructions, block_pc, before, after)`` for each observed change.
        self.changes: list = []

    def __repr__(self) -> str:
        name = f" {self.label}" if self.label else ""
        return (f"<Watchpoint 0x{self.address:08X}+{self.size}{name}, "
                f"{len(self.changes)} changes>")


class Apollo3Machine:
    def __init__(
        self,
        img: image_mod.FirmwareImage,
        *,
        log=print,
        trace=None,
        chiprev: Optional[int] = None,
    ) -> None:
        self.image = img
        self.log = log
        self.trace = trace
        self.cycles = 0
        self.reset_requested = False
        #: Recent fault descriptions, newest last. Bounded: only the last one is
        #: ever reported, and an unhandled exception that re-fires every
        #: instruction would otherwise grow this list until the process runs out
        #: of memory -- which is exactly how a long run used to die.
        self.faults: collections.deque[str] = collections.deque(maxlen=32)
        #: Number of times the core parked on WFI/WFE waiting for an interrupt.
        self.halts = 0
        #: Scheduled external stimulus: [instruction_count, callable, description].
        self._events: list[list] = []
        #: Stimulus handed in from another thread -- the live viewer runs its UI
        #: on the main thread and must never touch peripheral state while the CPU
        #: is executing. A deque because append/popleft are atomic, so draining it
        #: on the hot path costs no lock.
        self._posted: collections.deque = collections.deque()
        #: Set from outside to bring the run loop down cleanly.
        self.stop_requested = False
        #: Set by the fault hooks. Unicorn does not always raise when a hook
        #: refuses an access, so the run loop checks this on the success path
        #: too -- without it, a branch into unmapped memory left emu_start
        #: returning immediately, forever, at 100% CPU and zero instructions.
        self._faulted: Optional[str] = None
        #: Core cycles between clock updates. This does NOT set interrupt latency
        #: — deliverability is tested every basic block (see _on_block_timing) —
        #: so it can be coarse. Timer interrupts arrive at most this late, against
        #: a ~47000-cycle tick period.
        self.time_quantum = 256

        self.uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
        self.uc.ctl_set_cpu_model(UC_CPU_ARM_CORTEX_M4)

        self.uc.mem_map(FLASH_BASE, FLASH_SIZE)
        self.uc.mem_map(SRAM_BASE, SRAM_SIZE)
        # Erased flash reads 0xFF, and the firmware relies on it: the region
        # below the application at 0x20000 holds the bootloader and a settings
        # area it reads back at boot, and code that checks for a blank record
        # cannot tell "erased" from "all zeros" if the emulator hands it zeros.
        self.uc.mem_write(FLASH_BASE, b"\xff" * FLASH_SIZE)
        self.uc.mem_write(img.link_address, img.payload)

        self.bus: Bus = build_apollo3_bus(self)
        if chiprev is not None:
            self.bus.by_base[0x40020000].chiprev = chiprev
        self.cortexm = CortexM(self, log=log)
        self.bleif = None  # installed by attach_bleif()

        self._install_mmio()

        self.bootrom = BootRom(self, log=log)
        self.bootrom.install(img.payload, img.link_address)

        self._pending_svc = False
        self._svc_return_pc = 0
        self.uc.hook_add(UC_HOOK_INTR, self._on_interrupt)
        self.uc.hook_add(UC_HOOK_MEM_UNMAPPED, self._on_unmapped)
        if trace is not None:
            trace.attach(self)

        # Timing/interrupt-delivery hook. Registered last so it runs after the
        # tracer's block hook.
        self._instructions = 0
        self._quantum_cycles = 0
        self._instruction_budget = 0
        #: Memory locations being watched. See :meth:`watch` -- these are checked
        #: from the block hook because Unicorn's own memory hooks cannot be used
        #: here at all.
        self._watchpoints: list = []
        self.uc.hook_add(UC_HOOK_BLOCK, self._on_block_timing)

        self._timers = [p for p in self.bus.peripherals if hasattr(p, "advance")]
        self.uarts = [p for p in self.bus.peripherals if isinstance(p, Uart)]
        self.ctimer: CtimerBlock = self.bus.by_base[0x40008000]
        self.gpio = self.bus.by_base[0x40010000]
        self._irq_sources = [p for p in self.bus.peripherals if hasattr(p, "pending_irqs")]

    def add_timer(self, source) -> None:
        """Register *source* to be advanced with the clocks.

        Bus peripherals are picked up automatically, but a device hanging off a
        bus is not one -- the display sits behind the MSPI and still has to drive
        its tearing-effect line in real time.
        """
        if source not in self._timers:
            self._timers.append(source)

    # ── memory helpers ───────────────────────────────────────────────────────

    def _is_mmio(self, addr: int) -> bool:
        return any(base <= addr < base + size for base, size in MMIO_WINDOWS)

    @staticmethod
    def _is_ppb(addr: int) -> bool:
        return PPB_RANGE[0] <= addr < PPB_RANGE[1]

    def read32(self, addr: int) -> int:
        if self._is_mmio(addr):
            return self.cortexm.read(addr, 4) if self._is_ppb(addr) else self.bus.read(addr, 4)
        return int.from_bytes(self.uc.mem_read(addr, 4), "little")

    def write32(self, addr: int, value: int) -> None:
        if self._is_mmio(addr):
            if self._is_ppb(addr):
                self.cortexm.write(addr, 4, value)
            else:
                self.bus.write(addr, 4, value)
            return
        self.uc.mem_write(addr, (value & 0xFFFFFFFF).to_bytes(4, "little"))

    def describe_address(self, addr: int) -> str:
        """Human-readable name for an MMIO address, across both buses."""
        if self._is_ppb(addr):
            return self.cortexm.reg_name(addr)
        return self.bus.describe(addr)

    #: Programs that tried to set a bit flash can only clear. Counted rather
    #: than refused, because the interesting case is a firmware bug we want to
    #: see, not one we want to hide.
    flash_programs_without_erase = 0

    def write_flash(self, addr: int, data: bytes, *, erase: bool = False) -> None:
        """Programming path used by the boot ROM flash helpers.

        Programming NOR flash can only clear bits; setting one needs a page
        erase first. Modelling that as a plain overwrite makes a missing erase
        invisible here and a corrupt page on the watch, which is the worst place
        to find out — so a program ANDs with what is already there, and a
        program that needed a bit it could not set is counted.
        """
        if not (FLASH_BASE <= addr < FLASH_BASE + FLASH_SIZE):
            self.log(f"  [flash] refusing a write outside internal flash at 0x{addr:08X}")
            return
        if erase:
            self.uc.mem_write(addr, data)
            return
        current = bytes(self.uc.mem_read(addr, len(data)))
        merged = bytes(c & d for c, d in zip(current, data))
        if merged != bytes(data):
            self.flash_programs_without_erase += 1
            self.log(f"  [flash] program at 0x{addr:08X} needed bits the page does "
                     f"not have set — it was not erased first")
        self.uc.mem_write(addr, merged)

    def request_reset(self) -> None:
        self.reset_requested = True
        self.uc.emu_stop()

    # ── MMIO plumbing ────────────────────────────────────────────────────────

    def _install_mmio(self) -> None:
        for base, size in MMIO_WINDOWS:
            is_ppb = base == PPB_RANGE[0]

            def rd(uc, offset, size_, ud, _base=base, _ppb=is_ppb):
                addr = _base + offset
                value = self.cortexm.read(addr, size_) if _ppb else self.bus.read(addr, size_)
                if self.trace is not None:
                    self.trace.on_mmio(addr, size_, value, write=False)
                return value

            def wr(uc, offset, size_, value, ud, _base=base, _ppb=is_ppb):
                addr = _base + offset
                if _ppb:
                    self.cortexm.write(addr, size_, value)
                else:
                    self.bus.write(addr, size_, value)
                if self.trace is not None:
                    self.trace.on_mmio(addr, size_, value, write=True)

            self.uc.mmio_map(base, size, rd, None, wr, None)

    # ── hooks ────────────────────────────────────────────────────────────────

    def _on_interrupt(self, uc, intno, user_data) -> None:
        # ARM: intno 2 is EXCP_SWI (the `svc` instruction). Everything else is
        # unexpected on this core and is recorded for the triage report.
        if intno == 2:
            self._pending_svc = True
            self._svc_return_pc = uc.reg_read(UC_ARM_REG_PC)
            uc.emu_stop()
            return
        if intno == EXCP_EXCEPTION_EXIT:
            # M-profile exception return. Normal control flow, not a fault: the
            # run loop unwinds it once PC is seen in the EXC_RETURN range.
            uc.emu_stop()
            return
        detail = f"CPU exception intno={intno} at 0x{uc.reg_read(UC_ARM_REG_PC):08X}"
        self.faults.append(detail)
        self._faulted = detail
        uc.emu_stop()

    def _on_unmapped(self, uc, access, address, size, value, user_data) -> bool:
        pc = uc.reg_read(UC_ARM_REG_PC)
        kind = {16: "read", 17: "write", 18: "fetch", 20: "fetch", 21: "read", 22: "write"}.get(
            access, str(access)
        )
        detail = f"unmapped {kind} of 0x{address:08X} from pc=0x{pc:08X}"
        self.faults.append(detail)
        self._faulted = detail
        self._last_fault_address = address
        return False  # stop emulation

    # ── run loop ─────────────────────────────────────────────────────────────

    def post(self, action, description: str = "") -> None:
        """Hand *action* to the emulator thread, to run at the next quantum.

        The thread-safe counterpart to :meth:`schedule`. Input arriving from a
        UI thread goes through here so that the interrupt it raises is delivered
        by the same run-loop path the firmware would really see, rather than
        racing the CPU from outside.
        """
        self._posted.append((action, description))

    def schedule(self, at_instructions: int, action, description: str = "") -> None:
        """Run *action* once the run loop passes *at_instructions*.

        This is how external stimulus gets into the emulated watch: a button
        press, a sensor edge, later a BLE event. It has to go through the run loop
        rather than being poked in from outside, so the interrupt is delivered by
        the same path the firmware would really see.
        """
        self._events.append([at_instructions, action, description])
        self._events.sort(key=lambda e: e[0])

    def watch(self, address: int, size: int = 4, *, label: str = "", on_change=None):
        """Report changes to a memory location, checked once per basic block.

        **Do not use ``UC_HOOK_MEM_WRITE`` on this machine.** Registering one
        over SRAM does not merely miss writes — it derails the run: the firmware
        dies at ~155k instructions on an unmapped fetch of 0x10060000 (one past
        the end of SRAM) with a PC of 0, against a clean 8M-instruction run with
        no hook. It also reports the same ~103,659 writes whatever the length of
        the run, which is what makes it so convincing and so wrong. Two separate
        investigations have concluded "nothing writes this address" from a hook
        that had already killed the run before the write happened.

        This is checked from the block hook instead, which is the machine's own
        and is known good. The cost is one ``mem_read`` per watchpoint per basic
        block, so it is a debugging tool, not something to leave switched on; a
        run with no watchpoints pays nothing. Resolution is a basic block rather
        than an instruction, so a change is attributed to the block that
        contained the store — enough to disassemble and name it.

        Returns a ``Watchpoint`` whose ``changes`` list collects
        ``(instructions, block_pc, before, after)``.
        """
        point = Watchpoint(address, size, label, on_change)
        try:
            point.value = bytes(self.uc.mem_read(address, size))
        except UcError:
            point.value = None
        self._watchpoints.append(point)
        return point

    def unwatch(self, point) -> None:
        if point in self._watchpoints:
            self._watchpoints.remove(point)

    def _check_watchpoints(self, block_pc: int) -> None:
        for point in self._watchpoints:
            try:
                now = bytes(self.uc.mem_read(point.address, point.size))
            except UcError:
                continue
            if now == point.value:
                continue
            before, point.value = point.value, now
            point.changes.append((self._instructions, block_pc, before, now))
            if point.on_change is not None:
                point.on_change(point, block_pc, before, now)

    #: The core runs at 48 MHz, so this many instructions is roughly one second
    #: of watch time. Button holds have to be expressed in these terms: a
    #: power-on long-press is seconds long, and a "press" of a few tens of
    #: milliseconds is silently ignored by the firmware.
    CYCLES_PER_SECOND = 48_000_000

    def press_button(self, pin: int, at_instructions: int, hold: int | None = None) -> None:
        """Schedule a press and release of the button wired to *pin*.

        The direction is taken from the pin's own interrupt configuration rather
        than assumed: a pin the firmware set to interrupt on the falling edge is
        wired active-low and rests high, so driving it *high* to mean "pressed"
        is backwards, and leaves it reading as held down for the whole run. Five
        of this watch's six interrupt pins are that way round; the button on pin
        3 is the exception.

        Default hold is 3 seconds of watch time — long enough to register as a
        power-on long-press rather than a tap.
        """
        if hold is None:
            hold = 3 * self.CYCLES_PER_SECOND
        resting = self.gpio.resting_level(pin)
        active = 1 - resting

        def down():
            self.gpio.set_input(pin, active)
            self.gpio.raise_interrupt(pin)

        def up():
            self.gpio.set_input(pin, resting)
            self.gpio.raise_interrupt(pin)

        self.schedule(at_instructions, down, f"press pin {pin}")
        self.schedule(at_instructions + hold, up, f"release pin {pin}")

    def _on_block_timing(self, uc, address, size, user_data) -> None:
        """Advance the clocks and hand over promptly when an interrupt is due.

        Interrupt *latency* turns out to matter a great deal. Delivering only at
        fixed instruction slices lets a task run far past the point the hardware
        would have preempted it, and the firmware's behaviour degrades visibly:
        coverage falls from ~4900 basic blocks at a 200-instruction slice to ~4100
        at 25000, and collapses entirely at 100000. Checking per basic block keeps
        latency to a few tens of instructions.
        """
        count = size >> 1 or 1
        self._instructions += count
        self._quantum_cycles += count

        if self._watchpoints:
            self._check_watchpoints(address)

        if self.trace is not None:
            self.trace.on_block(address)

        if self._quantum_cycles >= self.time_quantum:
            cycles, self._quantum_cycles = self._quantum_cycles, 0
            self._advance_time(cycles)
            self._fire_due_events(self._instructions)
            if self._instructions >= self._instruction_budget or self.stop_requested:
                uc.emu_stop()
                return

        # Deliverability is checked every block, not once per quantum, because
        # PendSV latency has to be near zero to be correct. FreeRTOS's
        # xTaskResumeAll sets PendSV inside a critical section and expects the
        # switch the instant BASEPRI is released; if delivery is even ~100
        # instructions late the task has already called vTaskSuspendAll again,
        # vTaskSwitchContext early-returns, and the yield is silently lost. The
        # task then re-blocks on a queue it is already queued on, which corrupts
        # the event list into a self-referential node and hangs the system.
        #
        # The guard keeps this cheap: almost every block has nothing pending.
        cortexm = self.cortexm
        if (cortexm.icsr & _PEND_BITS) or (cortexm.irq_pending & cortexm.irq_enabled):
            if cortexm.pending_exception() is not None:
                uc.emu_stop()

    def _fire_due_events(self, instructions: int) -> None:
        while self._events and self._events[0][0] <= instructions:
            _, action, description = self._events.pop(0)
            if description:
                self.log(f"  [input] {description}")
            action()
        posted = self._posted
        while posted:
            try:
                action, description = posted.popleft()
            except IndexError:  # drained by another popleft
                break
            if description:
                self.log(f"  [input] {description}")
            action()

    def reset(self) -> None:
        img = self.image
        self.uc.reg_write(UC_ARM_REG_MSP, img.initial_sp)
        self.uc.reg_write(UC_ARM_REG_SP, img.initial_sp)
        self.cortexm.vtor = img.link_address
        self.reset_requested = False

    def run(self, max_instructions: int = 50_000_000, slice_size: int = 4_000_000) -> RunStats:
        stats = RunStats()
        self.reset()
        pc = self.image.reset_vector
        started = time.time()
        self._last_fault_address = None
        self._faulted = None
        self._instructions = 0
        self._quantum_cycles = 0
        self._instruction_budget = max_instructions

        while self._instructions < max_instructions:
            if self.stop_requested:
                stats.stop = StopReason("stopped", "stopped by the viewer", pc)
                break
            # Deliver the highest-priority pending exception before running on.
            exc = self.cortexm.pending_exception()
            if exc is not None:
                # Exception entry stacks the *current* PC as the return address,
                # and reads it from the CPU. Between slices the authoritative PC
                # lives in this local, so publish it first — otherwise an
                # exception arriving right after an exception return stacks the
                # stale EXC_RETURN value, and unwinding that later pops a frame
                # that was never pushed, desyncing the stack.
                self.uc.reg_write(UC_ARM_REG_PC, pc & 0xFFFFFFFE)
                try:
                    pc = self.cortexm.enter(exc)
                except RuntimeError as e:
                    stats.stop = StopReason("fault", str(e), pc)
                    break
                if self.trace is not None:
                    self.trace.on_exception(exc, pc)

            # The block hook drives the clocks and stops emulation the moment an
            # interrupt is deliverable, so this count is only a safety cap.
            budget = slice_size
            try:
                self.uc.emu_start(pc | 1, 0, count=budget)
                pc = self.uc.reg_read(UC_ARM_REG_PC)

                if self._is_wait_instruction(pc):
                    # Halted on WFI/WFE. Unicorn leaves PC *on* the instruction,
                    # so simply resuming re-executes it and the core never gets
                    # past the idle loop. Run time forward, then retire it.
                    self._instructions += self._wait_for_interrupt()
                    pc = (pc + 2) & 0xFFFFFFFF
                    self.uc.reg_write(UC_ARM_REG_PC, pc)
                    continue
            except UcError as e:
                pc = self.uc.reg_read(UC_ARM_REG_PC)
                if pc < EXC_RETURN_MARKER:
                    if self.reset_requested:
                        stats.stop = StopReason("reset", "firmware requested a system reset", pc)
                        break
                    detail = self.faults[-1] if self.faults else str(e)
                    if e.errno == UC_ERR_EXCEPTION and not self.faults:
                        detail = f"unhandled CPU exception at 0x{pc:08X}"
                    stats.stop = StopReason("fault", detail, pc, self._last_fault_address)
                    break

            # `bx lr` with an EXC_RETURN value in LR: unwind the exception in
            # software. This is checked on *both* paths on purpose — Unicorn does
            # not consistently raise here. Starting emulation at an EXC_RETURN
            # address returns successfully having executed nothing, so handling
            # this only in the `except` branch spins forever making no progress.
            if pc >= EXC_RETURN_MARKER:
                self.faults.clear()
                self._faulted = None
                pc = self.cortexm.exception_return(pc | 1)
                continue

            # A hook that refused an access does not reliably raise, so the fault
            # is picked up here as well. Reached with PC parked on the faulting
            # instruction, which would otherwise be retried for ever.
            if self._faulted is not None:
                stats.stop = StopReason("fault", self._faulted, pc, self._last_fault_address)
                break

            if self._pending_svc:
                self._pending_svc = False
                pc = self._take_svc()
                if pc is None:
                    stats.stop = StopReason("fault", "SVCall vector is unset", self._svc_return_pc)
                    break

            stats.instructions = self._instructions

            if self.reset_requested:
                stats.stop = StopReason("reset", "firmware requested a system reset", pc)
                break

            if self.trace is not None:
                verdict = self.trace.check_progress(self._instructions)
                if verdict is not None:
                    stats.stop = StopReason("stuck", verdict, pc)
                    break

        stats.instructions = self._instructions
        stats.wall_seconds = time.time() - started
        if stats.stop is None:
            stats.stop = StopReason("budget", f"ran the full {max_instructions} instruction budget", pc)
        if self.trace is not None:
            stats.coverage = self.trace.coverage
            stats.blocks = self.trace.block_count
        return stats

    def _take_svc(self) -> Optional[int]:
        """Enter the SVCall handler for an `svc` the CPU just executed."""
        # Unicorn reports the interrupt with PC *already past* the `svc`, so it is
        # the correct return address to stack as-is. (Verified against a 2-byte
        # `svc #0` at a known address; adding the instruction size here silently
        # skips the instruction after every SVC, which derails the FreeRTOS
        # scheduler the first time it starts a task.)
        self.uc.reg_write(UC_ARM_REG_PC, self._svc_return_pc & 0xFFFFFFFF)
        try:
            handler = self.cortexm.enter(11)
        except RuntimeError:
            return None
        if self.trace is not None:
            self.trace.on_exception(11, handler)
        return handler

    #: Thumb encodings of the hint instructions that park the core.
    _WAIT_ENCODINGS = (b"\x30\xbf", b"\x20\xbf")  # WFI, WFE

    def _is_wait_instruction(self, pc: int) -> bool:
        try:
            return bytes(self.uc.mem_read(pc & 0xFFFFFFFE, 2)) in self._WAIT_ENCODINGS
        except UcError:
            return False

    def _wait_for_interrupt(self, step: int = 2_000, limit: int = 4_000) -> int:
        """Run the clocks forward until an exception is pending.

        Returns the cycles skipped, which stand in for the instructions the core
        would not have executed while parked. Bounded so that a WFI with every
        interrupt source disabled cannot hang the emulator.
        """
        skipped = 0
        for _ in range(limit):
            if self.cortexm.pending_exception() is not None:
                break
            self._advance_time(step)
            skipped += step
        self.halts += 1
        return skipped

    def _advance_time(self, cycles: int) -> None:
        self.cycles += cycles
        self.cortexm.advance(cycles)
        for timer in self._timers:
            timer.advance(cycles)
        for source in self._irq_sources:
            for irq in source.pending_irqs():
                self.cortexm.set_pending_irq(irq)
        if self.bleif is not None:
            self.bleif.poll()

    # ── reporting ────────────────────────────────────────────────────────────

    def uart_output(self) -> list[str]:
        out: list[str] = []
        for uart in self.uarts:
            out.extend(uart.lines)
            if uart.tx:
                out.append(uart.tx.decode("utf-8", "replace"))
        return out
