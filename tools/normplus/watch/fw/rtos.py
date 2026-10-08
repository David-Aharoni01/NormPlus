"""Reading FreeRTOS state out of the running machine.

The firmware has no symbols, but FreeRTOS's own data structures are easy to find
from the outside, and being able to ask "which tasks exist, and where is each one
parked?" is the single most useful view when something stops making progress.

Two anchors make it work without symbols:

* Every task's name is stored *inside* its TCB, so searching RAM for the names
  finds the control blocks. The offset is confirmed per task rather than assumed,
  by checking that the candidate TCB has a plausible stack pointer, a priority
  below ``configMAX_PRIORITIES`` and a stack base below its stack top.
* ``pxCurrentTCB`` is loaded by a literal in ``vPortSVCHandler``, which is the
  SVCall vector — so it can be read straight out of the vector table.
"""

from __future__ import annotations

import re
import struct
from dataclasses import dataclass
from typing import Optional

SRAM_BASE = 0x10000000
SRAM_SIZE = 0x00060000

#: TCB layout (FreeRTOS 9, no MPU / trace facility):
#:   +0  pxTopOfStack, +4 xStateListItem(20), +24 xEventListItem(20),
#:   +44 uxPriority, +48 pxStack, +52 pcTaskName[16]
TCB_NAME_OFFSET_CANDIDATES = range(40, 84, 4)
TCB_PRIORITY = 44
TCB_STACK = 48

#: Words saved by the context switch before the hardware frame: r4-r11 and LR.
SAVED_REGISTERS = 9
#: Position of PC within the hardware exception frame.
FRAME_PC_INDEX = 6


@dataclass
class TaskInfo:
    name: str
    tcb: int
    top_of_stack: int
    priority: int
    stack_base: int
    saved_pc: int
    running: bool


def find_px_current_tcb(machine) -> Optional[int]:
    """Address of ``pxCurrentTCB``, from the literal ``vPortSVCHandler`` loads."""
    handler = machine.image.vector(11) & ~1  # SVCall
    try:
        code = bytes(machine.uc.mem_read(handler, 4))
    except Exception:
        return None
    # `ldr r3, [pc, #imm]` — T1 encoding: 0x4B | imm8 in the low byte.
    if code[1] & 0xF8 != 0x48:
        return None
    imm = code[0] * 4
    literal = ((handler + 4) & ~3) + imm
    try:
        return int.from_bytes(machine.uc.mem_read(literal, 4), "little")
    except Exception:
        return None


def list_tasks(machine) -> list[TaskInfo]:
    """Every FreeRTOS task currently in memory, with where each one is parked."""
    try:
        ram = bytes(machine.uc.mem_read(SRAM_BASE, SRAM_SIZE))
    except Exception:
        return []

    def rd32(addr: int) -> int:
        offset = addr - SRAM_BASE
        if not (0 <= offset <= len(ram) - 4):
            raise ValueError(addr)
        return struct.unpack_from("<I", ram, offset)[0]

    def in_sram(value: int) -> bool:
        return SRAM_BASE <= value < SRAM_BASE + SRAM_SIZE

    current = find_px_current_tcb(machine)
    current_tcb = 0
    if current is not None and in_sram(current):
        try:
            current_tcb = rd32(current)
        except ValueError:
            current_tcb = 0

    tasks: list[TaskInfo] = []
    seen: set[int] = set()
    # Two characters at least: the heart-rate task, made when a measurement
    # starts, is called "HR" (#87).
    for match in re.finditer(rb"[A-Za-z][A-Za-z0-9_ ]{1,15}\x00", ram):
        name_address = SRAM_BASE + match.start()
        for offset in TCB_NAME_OFFSET_CANDIDATES:
            tcb = name_address - offset
            if tcb < SRAM_BASE or tcb in seen:
                continue
            try:
                top, priority, stack = rd32(tcb), rd32(tcb + TCB_PRIORITY), rd32(tcb + TCB_STACK)
            except ValueError:
                continue
            if not (in_sram(top) and in_sram(stack) and stack < top and priority < 16):
                continue
            try:
                saved_pc = rd32(top + 4 * (SAVED_REGISTERS + FRAME_PC_INDEX))
            except ValueError:
                saved_pc = 0
            seen.add(tcb)
            tasks.append(
                TaskInfo(
                    name=match.group()[:-1].decode("latin1"),
                    tcb=tcb,
                    top_of_stack=top,
                    priority=priority,
                    stack_base=stack,
                    saved_pc=saved_pc,
                    running=(tcb == current_tcb),
                )
            )
            break
    tasks.sort(key=lambda t: -t.priority)
    return tasks


def format_tasks(machine, symbols=None) -> str:
    tasks = list_tasks(machine)
    if not tasks:
        return "  no FreeRTOS tasks found (has the scheduler started?)"

    def where(pc: int) -> str:
        if symbols is not None and machine.image.link_address <= pc < machine.image.end_address:
            return symbols.describe(pc)
        return f"0x{pc:08X}"

    lines = [f"  {len(tasks)} FreeRTOS tasks:",
             f"    {'name':<16} {'prio':>4}  {'TCB':<10} {'stack top':<10} parked at"]
    for task in tasks:
        marker = " *" if task.running else "  "
        lines.append(
            f"   {marker}{task.name:<15} {task.priority:>4}  0x{task.tcb:08X} "
            f"0x{task.top_of_stack:08X} {where(task.saved_pc)}"
        )
    lines.append("    (* = currently running; others show their saved resume address)")
    return "\n".join(lines)
