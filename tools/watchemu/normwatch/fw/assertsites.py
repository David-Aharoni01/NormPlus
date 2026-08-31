"""Exact address-to-source-file mapping, decoded from the assert call sites.

``SymbolMap`` (see ``symbols.py``) finds a module by looking for literal-pool
words that point at a ``__FILE__`` string. That works, but only for the modules
whose compiler output happens to *use* a literal pool. Most of this image passes
``__FILE__`` with a PC-relative ``ADR`` instead, and those modules are invisible
to it — so ``nearest()`` silently attributes their code to whichever unrelated
module happens to own the closest pool entry.

That is not a hypothetical: the low-power notification dialog at 0x00073FC8 was
reported as ``ui_notify_skipPairingConfirm_dlg.c`` (the nearest pool ref, over a
kilobyte away) when its own assert says ``ui_notify_lowpower_dlg.c``.

Every assert in this firmware is a call to the handler at 0x000305B0 with

    r0 = __FILE__       ADR  r0, #imm
    r1 = line number    MOVS/MOVW r1, #imm
    r2 = expression     ADR  r2, #imm

so decoding the ``ADR`` that precedes each of those calls gives an *exact*
(address -> file, line, expression) fact rather than a nearest-neighbour guess.
467 of them cover 106 modules.

This does not replace ``SymbolMap`` — an assert site is still only a point, so
attributing an arbitrary address still means finding the closest one. But the
points are real, there are far more of them, and each one is certain.
"""

from __future__ import annotations

import bisect
import struct
from dataclasses import dataclass

#: The shared ``assert`` failure handler every module calls.
ASSERT_HANDLER = 0x000305B0

#: Thumb ADR (T1): 1010 0 Rd imm8 -- target = align4(pc + 4) + imm8 * 4
_ADR_MASK = 0xF800
_ADR_OPCODE = 0xA000

#: Thumb MOVS Rd, #imm8: 0010 0 Rd imm8
_MOVS_MASK = 0xF800
_MOVS_OPCODE = 0x2000

#: How far back from the call to look for its operand setup.
_LOOKBACK_HALFWORDS = 13


@dataclass(frozen=True)
class AssertSite:
    address: int      #: address of the ``bl`` to the assert handler
    module: str       #: the ``__FILE__`` string, as it appears in the image
    line: int | None  #: the line number, when it was encoded as a MOVS
    expression: str | None  #: the asserted expression, e.g. ``"hParent"``


class AssertMap:
    """Every ``assert()`` call site in the image, with its source file."""

    def __init__(self, payload: bytes, link_address: int,
                 handler: int = ASSERT_HANDLER) -> None:
        self.payload = payload
        self.link_address = link_address
        self.handler = handler
        self.sites: list[AssertSite] = []
        self._addrs: list[int] = []
        self._build()

    # ── decoding helpers ────────────────────────────────────────────────────

    def _halfword(self, offset: int) -> int:
        return struct.unpack_from("<H", self.payload, offset)[0]

    def _cstring(self, address: int, limit: int = 160) -> str | None:
        offset = address - self.link_address
        if not (0 <= offset < len(self.payload)):
            return None
        raw = self.payload[offset:offset + limit].split(b"\0")[0]
        try:
            return raw.decode("ascii")
        except UnicodeDecodeError:
            return None

    def _bl_target(self, offset: int) -> int | None:
        """Decode a Thumb-2 ``BL`` at *offset*; None if it is not one."""
        if offset + 3 >= len(self.payload):
            return None
        hi = self._halfword(offset)
        lo = self._halfword(offset + 2)
        if (hi & 0xF800) != 0xF000 or (lo & 0xD000) != 0xD000:
            return None
        s = (hi >> 10) & 1
        imm10 = hi & 0x3FF
        j1 = (lo >> 13) & 1
        j2 = (lo >> 11) & 1
        imm11 = lo & 0x7FF
        imm = (((1 - (j1 ^ s)) << 23) | ((1 - (j2 ^ s)) << 22)
               | (s << 24) | (imm10 << 12) | (imm11 << 1))
        if s:
            imm -= 1 << 25
        return self.link_address + offset + 4 + imm

    def _adr_target(self, offset: int, register: int) -> int | None:
        """Target of an ``ADR rN, #imm`` at *offset*, if that is what it is."""
        word = self._halfword(offset)
        if (word & _ADR_MASK) != _ADR_OPCODE:
            return None
        if ((word >> 8) & 0x7) != register:
            return None
        pc = self.link_address + offset + 4
        return (pc & ~3) + (word & 0xFF) * 4

    # ── construction ────────────────────────────────────────────────────────

    def _build(self) -> None:
        for offset in range(0, len(self.payload) - 3, 2):
            if self._bl_target(offset) != self.handler:
                continue
            module = expression = line = None
            for back in range(2, _LOOKBACK_HALFWORDS * 2, 2):
                if offset - back < 0:
                    break
                if module is None:
                    target = self._adr_target(offset - back, 0)
                    if target is not None:
                        text = self._cstring(target)
                        if text and ".c" in text:
                            module = text
                if expression is None:
                    target = self._adr_target(offset - back, 2)
                    if target is not None:
                        text = self._cstring(target, 64)
                        if text and ".c" not in text:
                            expression = text
                if line is None:
                    word = self._halfword(offset - back)
                    if (word & _MOVS_MASK) == _MOVS_OPCODE and ((word >> 8) & 7) == 1:
                        line = word & 0xFF
            if module is not None:
                self.sites.append(AssertSite(self.link_address + offset,
                                             module, line, expression))
        self.sites.sort(key=lambda s: s.address)
        self._addrs = [s.address for s in self.sites]

    # ── queries ─────────────────────────────────────────────────────────────

    @staticmethod
    def short(module: str) -> str:
        return module.replace("\\", "/").rsplit("/", 1)[-1]

    def nearest(self, address: int, window: int = 0x800) -> str | None:
        """Module of the assert site closest to *address*, within *window*."""
        if not self._addrs:
            return None
        i = bisect.bisect_right(self._addrs, address)
        best, best_distance = None, window + 1
        for j in (i - 1, i):
            if 0 <= j < len(self.sites):
                distance = abs(self.sites[j].address - address)
                if distance < best_distance:
                    best, best_distance = self.sites[j], distance
        return self.short(best.module) if best else None

    def modules(self) -> list[str]:
        return sorted({self.short(s.module) for s in self.sites})

    def sites_in(self, module: str) -> list[AssertSite]:
        """Every site whose file name ends with *module*."""
        return [s for s in self.sites if self.short(s.module) == module]
