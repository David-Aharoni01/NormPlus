"""Recovering a module map from the firmware's ``assert()`` strings.

The image has no symbols, but it does keep the ``__FILE__`` argument of every
``assert()`` — 130 distinct source paths that name the whole ODM source tree
(``docs/firmware.md`` §3). Each of those strings is referenced from a literal pool
next to the code that asserts, so the *addresses of the literals* give a rough
address-to-module map for free.

It is a heuristic, not a symbol table: it says "the nearest assert reference below
this PC belongs to ``ew_dev_spinand.c``", which is enough to turn "stopped at
0x0008AAAA" into "stopped in the SPI NAND driver".
"""

from __future__ import annotations

import bisect
import re
import struct
from dataclasses import dataclass


@dataclass
class ModuleRef:
    address: int  # address of the literal-pool word that points at the string
    module: str


class SymbolMap:
    def __init__(self, payload: bytes, link_address: int) -> None:
        self.payload = payload
        self.link_address = link_address
        self.strings: dict[int, str] = {}  # address -> string
        self.refs: list[ModuleRef] = []
        self._ref_addrs: list[int] = []
        self._build()

    def _build(self) -> None:
        # 1. Every printable run that looks like a source path from the ODM tree.
        for match in re.finditer(rb"[\x20-\x7e]{6,}", self.payload):
            text = match.group().decode("ascii", "replace")
            if ".c" in text and ("\\" in text or "/" in text):
                self.strings[self.link_address + match.start()] = text

        # 2. Literal-pool words pointing anywhere *inside* one of those strings.
        #    Matching the start address alone misses the common `__FILE__ + k`
        #    forms the compiler emits when it shares a suffix between modules.
        spans: list[tuple[int, int, str]] = sorted(
            (addr, addr + len(text), text) for addr, text in self.strings.items()
        )
        starts = [s for s, _, _ in spans]
        for off in range(0, len(self.payload) - 3, 4):
            word = struct.unpack_from("<I", self.payload, off)[0]
            i = bisect.bisect_right(starts, word) - 1
            if i >= 0:
                start, end, text = spans[i]
                if start <= word < end:
                    self.refs.append(ModuleRef(self.link_address + off, text))
        self.refs.sort(key=lambda r: r.address)
        self._ref_addrs = [r.address for r in self.refs]

    @staticmethod
    def short(module: str) -> str:
        return module.replace("\\", "/").rsplit("/", 1)[-1]

    def nearest(self, address: int, window: int = 0x800) -> str | None:
        """Module whose assert reference is closest below *address*."""
        if not self._ref_addrs:
            return None
        i = bisect.bisect_right(self._ref_addrs, address)
        best, best_distance = None, window + 1
        for j in (i - 1, i):
            if 0 <= j < len(self.refs):
                distance = abs(self.refs[j].address - address)
                if distance < best_distance:
                    best, best_distance = self.refs[j], distance
        return self.short(best.module) if best else None

    def describe(self, address: int) -> str:
        module = self.nearest(address)
        return f"0x{address:08X}" + (f"  (~{module})" if module else "")

    def modules(self) -> list[str]:
        return sorted({self.short(r.module) for r in self.refs})
