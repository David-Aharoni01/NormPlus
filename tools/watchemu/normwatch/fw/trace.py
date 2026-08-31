"""Execution tracing, coverage and stuck-loop detection.

The default failure mode when bringing up a machine model is not a crash — it is a
*hang*: the firmware polls a status bit that our peripheral model never sets, and
spins forever. A plain "it stopped responding" is useless; what is needed is "it is
spinning over 6 addresses in ``ew_drv_qspi.c`` reading ``MSPI.CTRL``".

That is what :meth:`Tracer.check_progress` produces. Coverage is tracked per basic
block (cheap) rather than per instruction, and progress is judged by whether new
blocks are still being discovered.
"""

from __future__ import annotations

import collections
from typing import Optional


from .cortexm import EXC_PENDSV, exception_name


class Tracer:
    def __init__(
        self,
        symbols=None,
        *,
        stall_window: int = 2_000_000,
        recent_size: int = 64,
        watch_for_stall: bool = True,
        log=print,
    ) -> None:
        self.symbols = symbols
        self.log = log
        self.coverage: set[int] = set()
        self.block_count = 0
        self.recent_blocks = collections.deque(maxlen=recent_size)
        self.path: collections.deque[int] = collections.deque(maxlen=recent_size)
        self.exceptions: list[tuple[int, int]] = []

        self.mmio_reads = collections.Counter()
        self.mmio_writes = collections.Counter()
        #: Running totals, so the stall check does not have to scan a list that
        #: grows for the whole run.
        self.mmio_write_count = 0
        self.context_switches = 0
        self._recent_mmio = collections.deque(maxlen=recent_size)

        self._stall_window = stall_window
        #: When false the tracer still records everything but never asks the run
        #: loop to stop. An interactive session must not be killed by a
        #: heuristic: the window would go on saying "running" while the CPU had
        #: quietly ended, which looks exactly like a hang the user caused.
        self._watch_for_stall = watch_for_stall
        self._last_new_coverage_at = 0
        self._coverage_at_checkpoint = 0
        self._switches_at_checkpoint = 0
        self._writes_at_checkpoint = 0
        self._machine = None
        #: Instruction count at which the system settled into its idle loop, or
        #: None if it is still reaching new code.
        self.idle_since: Optional[int] = None

    def attach(self, machine) -> None:
        """Bind to *machine*, which will call :meth:`on_block` for every block.

        Deliberately no hook of its own. Registering a second ``UC_HOOK_BLOCK``
        doubles the number of Python callbacks the emulator makes per basic
        block, and that alone cost 3.8x throughput -- 4.4M instructions/second
        down to 1.2M. The machine already has a block hook for timing, so the
        tracer rides on that one instead.
        """
        self._machine = machine

    # ── hooks ────────────────────────────────────────────────────────────────

    def on_block(self, address: int) -> None:
        self.block_count += 1
        self.recent_blocks.append(address)
        # `path` collapses repeats so a tight loop cannot flush the history that
        # led into it — which is the part worth seeing when something hangs.
        if not self.path or self.path[-1] != address:
            self.path.append(address)
        if address not in self.coverage:
            self.coverage.add(address)

    def on_mmio(self, address: int, size: int, value: int, *, write: bool) -> None:
        if write:
            self.mmio_writes[address] += 1
            self.mmio_write_count += 1
        else:
            self.mmio_reads[address] += 1
        self._recent_mmio.append((address, write))

    def on_exception(self, exc: int, handler: int) -> None:
        self.exceptions.append((exc, handler))
        if exc == EXC_PENDSV:
            self.context_switches += 1

    # ── progress ─────────────────────────────────────────────────────────────

    def check_progress(self, instructions: int) -> Optional[str]:
        """Return a diagnosis if the firmware has stopped making progress.

        "No new code" alone does not mean stuck. Once an RTOS finishes booting it
        settles into an idle loop and stops reaching new code *by design*, which
        is a success, not a hang. So a system that is still taking context
        switches is reported as idle and left running; only one that has stopped
        scheduling entirely is called stuck.

        Context switches alone are not enough of a liveness signal either. The
        firmware brings its hardware up inside one long
        ``am_hal_interrupt_master_disable`` region (0x0003E86A to 0x0003E9B2),
        so for millions of instructions there are no interrupts at all, and
        therefore no PendSV -- while the storage stack is busy mounting the
        resource partition. Driving MMIO counts as progress for exactly that
        case: a genuinely wedged poll loop reads one register over and over and
        writes nothing, whereas a working driver keeps writing.
        """
        if not self._watch_for_stall:
            return None

        if len(self.coverage) > self._coverage_at_checkpoint:
            self._coverage_at_checkpoint = len(self.coverage)
            self._last_new_coverage_at = instructions
            self.idle_since = None
            return None

        if instructions - self._last_new_coverage_at < self._stall_window:
            return None

        if self.context_switches > self._switches_at_checkpoint:
            self._switches_at_checkpoint = self.context_switches
            self._last_new_coverage_at = instructions
            if self.idle_since is None:
                self.idle_since = instructions
            return None

        if self.mmio_write_count > self._writes_at_checkpoint:
            self._writes_at_checkpoint = self.mmio_write_count
            self._last_new_coverage_at = instructions
            return None

        return self.diagnose_stall()

    def diagnose_stall(self) -> str:
        blocks = list(self.recent_blocks)
        unique = sorted(set(blocks))
        lines = [
            f"no new code reached for {self._stall_window} instructions — "
            f"spinning over {len(unique)} basic block(s)"
        ]
        lines.append("    most recent path (repeats collapsed, newest last):")
        for addr in list(self.path)[-14:]:
            lines.append(f"      {self._describe(addr)}")

        polled = collections.Counter(addr for addr, write in self._recent_mmio if not write)
        if polled:
            lines.append("    polling these registers:")
            for addr, count in polled.most_common(5):
                where = self._machine.describe_address(addr) if self._machine else f"0x{addr:08X}"
                lines.append(f"      {where}  (0x{addr:08X}) x{count}")
            lines.append(
                "    -> most likely an unmodelled status bit: give that register a "
                "value that lets the poll exit (see peripherals.py)"
            )
        return "\n".join(lines)

    def _describe(self, address: int) -> str:
        if self.symbols is not None:
            return self.symbols.describe(address)
        return f"0x{address:08X}"

    # ── reporting ────────────────────────────────────────────────────────────

    def module_coverage(self) -> list[tuple[str, int]]:
        if self.symbols is None:
            return []
        counts = collections.Counter()
        for addr in self.coverage:
            module = self.symbols.nearest(addr)
            if module:
                counts[module] += 1
        return counts.most_common()

    def summary(self, limit: int = 20) -> str:
        out = [f"  basic blocks executed: {self.block_count} ({len(self.coverage)} unique)"]
        if self.idle_since is not None:
            out.append(
                f"  reached idle steady state after ~{self.idle_since:,} instructions "
                f"(still scheduling; no new code after that)"
            )
        modules = self.module_coverage()
        if modules:
            out.append("  modules reached (by nearby assert strings):")
            for module, count in modules[:limit]:
                out.append(f"    {module:<40} {count} blocks")
            if len(modules) > limit:
                out.append(f"    ... and {len(modules) - limit} more")
        if self.exceptions:
            counts = collections.Counter(exc for exc, _ in self.exceptions)
            out.append(
                "  exceptions dispatched: "
                + ", ".join(f"{exception_name(e)}x{c}" for e, c in counts.most_common())
            )
        if self.mmio_reads or self.mmio_writes:
            out.append("  hottest MMIO:")
            combined = collections.Counter()
            for addr, count in self.mmio_reads.items():
                combined[addr] += count
            for addr, count in self.mmio_writes.items():
                combined[addr] += count
            for addr, count in combined.most_common(10):
                where = self._machine.describe_address(addr) if self._machine else ""
                out.append(
                    f"    0x{addr:08X} {where:<28} r={self.mmio_reads[addr]} w={self.mmio_writes[addr]}"
                )
        return "\n".join(out)
