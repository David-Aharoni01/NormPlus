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

from .assertsites import AssertMap


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
        #: Assert call sites, which cover the modules whose ``__FILE__`` is
        #: passed by ``ADR`` rather than a literal pool — most of them.
        self.asserts = AssertMap(payload, link_address)

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

    def _nearest_ref(self, address: int, window: int) -> tuple[str | None, int]:
        """Closest literal-pool reference to *address*, and its distance."""
        if not self._ref_addrs:
            return None, window + 1
        i = bisect.bisect_right(self._ref_addrs, address)
        best, best_distance = None, window + 1
        for j in (i - 1, i):
            if 0 <= j < len(self.refs):
                distance = abs(self.refs[j].address - address)
                if distance < best_distance:
                    best, best_distance = self.refs[j], distance
        return (self.short(best.module) if best else None), best_distance

    def nearest(self, address: int, window: int = 0x800) -> str | None:
        """Module owning *address*, from whichever evidence sits closest.

        Two independent sources: literal-pool words pointing at a ``__FILE__``
        string, and the ``ADR`` operands of the assert call sites. The pools
        miss most modules outright, so consulting only them silently attributes
        their code to an unrelated neighbour — which is exactly how the
        low-power dialog came to be reported as ``skipPairingConfirm``.
        """
        pool_module, pool_distance = self._nearest_ref(address, window)
        assert_module = self.asserts.nearest(address, window)
        if assert_module is None:
            return pool_module
        if pool_module is None:
            return assert_module
        i = bisect.bisect_right(self.asserts._addrs, address)
        assert_distance = min(
            (abs(self.asserts.sites[j].address - address)
             for j in (i - 1, i) if 0 <= j < len(self.asserts.sites)),
            default=window + 1,
        )
        return assert_module if assert_distance <= pool_distance else pool_module

    def describe(self, address: int) -> str:
        module = self.nearest(address)
        return f"0x{address:08X}" + (f"  (~{module})" if module else "")

    def modules(self) -> list[str]:
        return sorted({self.short(r.module) for r in self.refs})
