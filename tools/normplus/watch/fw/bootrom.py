"""Shim for the Apollo3 boot ROM helper functions.

The application does not contain these routines — they live in mask ROM at
``0x08000000`` inside the SoC, and are reached through a table of function
pointers that AmbiqSuite calls ``g_am_hal_bootrom_helper``. That table *is* in the
image (a const struct of addresses in the ``0x080000xx`` range), which is what
lets us find it without symbols.

Why the HAL bothers: several Apollo3 registers (notably ``CACHECTRL``) and all
flash programming must be touched from ROM rather than from code executing out of
the flash being modified. So a perfectly ordinary boot walks straight into ROM
within the first few hundred thousand instructions, and an emulator that does not
model it dies with a fetch fault at ``0x080000xx`` — which is exactly what happens
before this module is installed.

Entry semantics are only partly documented publicly. Three are pinned by call-site
analysis in this image:

* **8** is called with ``0x40018004`` (the CACHECTRL config register) and its
  return value is used, so it reads a word.
* **9** is its write counterpart.
* **10** is ``am_hal_flash_delay``: it is the delay inside
  ``am_hal_flash_delay_status_check``, always called with a small iteration count.

The rest are named on the strength of the AmbiqSuite layout and are marked
``inferred``; anything actually reached is logged with its arguments so it can be
identified from the call site rather than guessed at.
"""

from __future__ import annotations

import inspect
import struct
from dataclasses import dataclass
from typing import Callable, Optional

from unicorn import UC_HOOK_CODE
from unicorn.arm_const import (
    UC_ARM_REG_LR,
    UC_ARM_REG_PC,
    UC_ARM_REG_R0,
    UC_ARM_REG_R1,
    UC_ARM_REG_R2,
    UC_ARM_REG_R3,
)

#: The boot ROM window. We map one page and fill it with `bx lr` so that any entry
#: we have not modelled still returns to the caller instead of executing garbage.
BOOTROM_BASE = 0x08000000
BOOTROM_SIZE = 0x1000

_THUMB_BX_LR = b"\x70\x47"

#: The ROM ABI's argument registers, in order.
_ARG_REGISTERS = (UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3)


def _declared_arg_registers(handler) -> tuple:
    """The R0.. registers a handler actually names, ignoring its ``*_args``.

    Every ``_rom_*`` handler ends in ``*_args`` so that it can be called with
    all four ROM argument registers while using only the ones it needs. That
    made the caller read four registers to throw three away. Asking the
    signature how many are real is a one-off cost at install time.
    """
    if handler is None:
        return ()
    positional = 0
    for parameter in inspect.signature(handler).parameters.values():
        if parameter.kind is inspect.Parameter.POSITIONAL_OR_KEYWORD:
            positional += 1
        elif parameter.kind is inspect.Parameter.VAR_POSITIONAL:
            break
    return _ARG_REGISTERS[:positional]


@dataclass
class BootRomEntry:
    index: int
    address: int
    name: str
    inferred: bool = True
    #: Resolved once at install time. Looking the handler up per call meant
    #: building an f-string and a getattr on a path taken millions of times.
    handler: Optional[Callable] = None
    #: The argument registers this handler actually declares, R0 upwards. The
    #: ROM ABI puts arguments in R0-R3 and every handler ends in ``*_args`` to
    #: swallow the rest, so reading all four was reading three values that were
    #: then discarded. ``bootrom_delay_cycles`` takes one and is over 99% of
    #: the calls.
    arg_registers: tuple = ()
    #: How often this entry was called. Counted on the entry rather than in a
    #: dict keyed by name: this is incremented two million times in a boot that
    #: draws the factory resources, and hashing the name each time was a
    #: measurable share of the dispatch (#72). :meth:`BootRom.calls` collects
    #: them when something actually asks.
    count: int = 0


#: Names by table index, following the AmbiqSuite ``am_hal_bootrom_helper_t``
#: ordering. Indices 8/9 are confirmed against this image's call sites.
_ENTRY_NAMES = {
    0: ("nv_mass_erase", True),
    1: ("nv_page_erase", True),
    2: ("nv_program_main", True),
    3: ("nv_program_info_area", True),
    4: ("nv_program_main2", True),
    5: ("nv_info_erase", True),
    6: ("nv_recovery", True),
    7: ("nv_valid_address", True),
    8: ("bootrom_util_read_word", False),
    9: ("bootrom_util_write_word", False),
    10: ("bootrom_delay_cycles", False),
    11: ("bootrom_program_word", True),
    12: ("bootrom_program_info", True),
    13: ("bootrom_erase_info", True),
    14: ("bootrom_reset", True),
    15: ("bootrom_util_misc", True),
}


def find_helper_table(payload: bytes, link_address: int) -> Optional[int]:
    """Locate ``g_am_hal_bootrom_helper`` by shape.

    It is a run of consecutive 32-bit words that all point into the boot ROM
    window with the Thumb bit set. Eight in a row is far past coincidence.
    """
    best_addr, best_len = None, 0
    run_start, run_len = None, 0
    for off in range(0, len(payload) - 3, 4):
        word = struct.unpack_from("<I", payload, off)[0]
        is_rom_ptr = (word & 0xFFFFF000) == BOOTROM_BASE and (word & 1) == 1
        if is_rom_ptr:
            if run_start is None:
                run_start, run_len = off, 1
            else:
                run_len += 1
        else:
            if run_len > best_len:
                best_addr, best_len = run_start, run_len
            run_start, run_len = None, 0
    if run_len > best_len:
        best_addr, best_len = run_start, run_len
    if best_addr is None or best_len < 8:
        return None
    return link_address + best_addr


class BootRom:
    """Installs `bx lr` stubs at the ROM entry points and services them in Python."""

    def __init__(self, machine, log=print) -> None:
        self.machine = machine
        self.log = log
        self.entries: dict[int, BootRomEntry] = {}  # address -> entry
        self.table_address: Optional[int] = None
        #: Calls to an address in the ROM window that is not a known entry.
        #: The known ones are counted on the entries themselves; :attr:`calls`
        #: puts the two together.
        self._stray: dict[str, int] = {}
        self.unknown_calls: list[tuple[str, tuple[int, ...]]] = []
        #: The same fast register reader CortexM uses -- the same uc_reg_read,
        #: without the binding's per-call register-class lookup and ctypes
        #: marshalling. CortexM is built before BootRom, so it is already there.
        self._reg = machine.cortexm.read_register

    # ── installation ─────────────────────────────────────────────────────────

    def install(self, payload: bytes, link_address: int) -> bool:
        uc = self.machine.uc
        uc.mem_map(BOOTROM_BASE, BOOTROM_SIZE)
        uc.mem_write(BOOTROM_BASE, _THUMB_BX_LR * (BOOTROM_SIZE // 2))

        table = find_helper_table(payload, link_address)
        self.table_address = table
        if table is None:
            self.log("  [bootrom] g_am_hal_bootrom_helper not found — ROM calls will just return")
            return False

        off = table - link_address
        index = 0
        while off + 4 <= len(payload):
            word = struct.unpack_from("<I", payload, off)[0]
            if (word & 0xFFFFF000) != BOOTROM_BASE or not (word & 1):
                break
            addr = word & ~1
            name, inferred = _ENTRY_NAMES.get(index, (f"rom_entry_{index}", True))
            entry = BootRomEntry(index, addr, name, inferred)
            entry.handler = getattr(self, f"_rom_{name}", None)
            entry.arg_registers = _declared_arg_registers(entry.handler)
            self.entries[addr] = entry
            index += 1
            off += 4

        self.log(
            f"  [bootrom] helper table at 0x{table:08X} with {index} entries "
            f"-> stubbed in the 0x{BOOTROM_BASE:08X} window"
        )
        uc.hook_add(
            UC_HOOK_CODE,
            self._on_execute,
            begin=BOOTROM_BASE,
            end=BOOTROM_BASE + BOOTROM_SIZE - 1,
        )
        return True

    # ── dispatch ─────────────────────────────────────────────────────────────

    def _on_execute(self, uc, address, size, user_data) -> None:
        # This is a hot path, not a bookkeeping one. A boot without the radio
        # enters ROM 190k times; with it, ``am_util_delay_us`` inside the BLE
        # HAL's retry loop takes it to 4.3M, and it was reading four registers
        # through the Unicorn binding and rebuilding an f-string on every one.
        # Reading only what the handler declares, through the fast reader
        # (cortexm._make_fast_reg_reader), and resolving the handler at install
        # time took it from ~45% of a BLE-on run to a few percent.
        entry = self.entries.get(address)
        if entry is None:
            # Landed in the ROM window but not on a known entry: the `bx lr`
            # already there will return, so just record it.
            key = "<unmapped rom address>"
            self._stray[key] = self._stray.get(key, 0) + 1
            return

        entry.count += 1
        handler = entry.handler
        read = self._reg
        if handler is None:
            if entry.name not in {n for n, _ in self.unknown_calls}:
                args = (read(UC_ARM_REG_R0), read(UC_ARM_REG_R1),
                        read(UC_ARM_REG_R2), read(UC_ARM_REG_R3))
                self.unknown_calls.append((entry.name, args))
                self.log(
                    f"  [bootrom] unmodelled {entry.name} (index {entry.index}) "
                    f"args=({', '.join('0x%08X' % a for a in args)}) -> returning 0"
                )
            uc.reg_write(UC_ARM_REG_R0, 0)
            return

        registers = entry.arg_registers
        if len(registers) == 1:  # bootrom_delay_cycles and friends
            result = handler(read(registers[0]))
        else:
            result = handler(*[read(r) for r in registers])
        if result is not None:
            uc.reg_write(UC_ARM_REG_R0, result & 0xFFFFFFFF)

    # ── modelled entries ─────────────────────────────────────────────────────
    # These take the ROM's C signatures. Reads and writes go through the machine
    # so that a ROM-mediated peripheral access hits the same register models as a
    # direct one — which is the entire reason the HAL routes through ROM.

    def _rom_bootrom_util_read_word(self, addr, *_args):
        return self.machine.read32(addr)

    def _rom_bootrom_util_write_word(self, addr, value, *_args):
        self.machine.write32(addr, value)
        return None

    def _rom_bootrom_delay_cycles(self, cycles, *_args):
        # Time in the emulator is driven by the instruction count, so a busy-wait
        # in ROM would only burn wall clock. Account for it and return.
        self.machine.cycles += cycles
        return None

    def _rom_nv_program_main(self, _value, src, dst, n_words, *_args):
        data = self.machine.uc.mem_read(src, n_words * 4)
        self.machine.write_flash(dst, bytes(data))
        return 0

    def _rom_nv_program_main2(self, _value, n_words, src, dst, *_args):
        data = self.machine.uc.mem_read(src, n_words * 4)
        self.machine.write_flash(dst, bytes(data))
        return 0

    #: Apollo3 flash pages are 8 KB and each instance covers 512 KB. Confirmed
    #: against this image rather than assumed: every ``nv_program_main``
    #: destination in a boot lands exactly on a page a preceding
    #: ``nv_page_erase`` cleared -- (0, 0x0E) then 0x1C000, (0, 0x0C) then
    #: 0x18000, (1, 0x3D) then 0xFA000.
    PAGE_SIZE = 0x2000
    INSTANCE_SIZE = 0x80000

    def _rom_nv_page_erase(self, _value, instance, page, *_args):
        addr = (instance * self.INSTANCE_SIZE) + page * self.PAGE_SIZE
        self.machine.write_flash(addr, b"\xff" * self.PAGE_SIZE, erase=True)
        return 0

    def _rom_nv_valid_address(self, addr, *_args):
        return 1 if addr < 0x00100000 else 0

    @property
    def calls(self) -> dict[str, int]:
        """What was called and how often, collected when asked rather than kept."""
        counted: dict[str, int] = {}
        for entry in self.entries.values():
            # By name, as the dict this replaces was keyed: two addresses can
            # carry the same helper, and they counted as one.
            if entry.count:
                counted[entry.name] = counted.get(entry.name, 0) + entry.count
        for name, n in self._stray.items():
            counted[name] = counted.get(name, 0) + n
        return counted

    def summary(self) -> str:
        if not self.calls:
            return "  boot ROM: not called"
        rows = sorted(self.calls.items(), key=lambda kv: -kv[1])
        out = ["  boot ROM helper calls:"]
        out += [f"    {name:<28} {count}" for name, count in rows]
        return "\n".join(out)
