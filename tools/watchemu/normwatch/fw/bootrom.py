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


@dataclass
class BootRomEntry:
    index: int
    address: int
    name: str
    inferred: bool = True


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
        self.calls: dict[str, int] = {}
        self.unknown_calls: list[tuple[str, tuple[int, ...]]] = []

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
            self.entries[addr] = BootRomEntry(index, addr, name, inferred)
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
        entry = self.entries.get(address)
        args = tuple(uc.reg_read(r) for r in (UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3))

        if entry is None:
            # Landed in the ROM window but not on a known entry: the `bx lr`
            # already there will return, so just record it.
            self.calls["<unmapped rom address>"] = self.calls.get("<unmapped rom address>", 0) + 1
            return

        self.calls[entry.name] = self.calls.get(entry.name, 0) + 1
        handler: Optional[Callable] = getattr(self, f"_rom_{entry.name}", None)
        if handler is None:
            if entry.name not in {n for n, _ in self.unknown_calls}:
                self.unknown_calls.append((entry.name, args))
                self.log(
                    f"  [bootrom] unmodelled {entry.name} (index {entry.index}) "
                    f"args=({', '.join('0x%08X' % a for a in args)}) -> returning 0"
                )
            uc.reg_write(UC_ARM_REG_R0, 0)
            return

        result = handler(*args)
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

    def _rom_nv_page_erase(self, _value, instance, page, *_args):
        # Apollo3 flash pages are 8 KB.
        addr = (instance * 0x80000) + page * 0x2000
        self.machine.write_flash(addr, b"\xff" * 0x2000)
        return 0

    def _rom_nv_valid_address(self, addr, *_args):
        return 1 if addr < 0x00100000 else 0

    def summary(self) -> str:
        if not self.calls:
            return "  boot ROM: not called"
        rows = sorted(self.calls.items(), key=lambda kv: -kv[1])
        out = ["  boot ROM helper calls:"]
        out += [f"    {name:<28} {count}" for name, count in rows]
        return "\n".join(out)
