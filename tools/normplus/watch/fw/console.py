"""Capturing the firmware's own diagnostics.

The image contains a printf-family formatter and an ``assert()`` handler that
formats::

    Assert Failed:%s, File:%s:%d [curInterrupt=%d][curPriority=%d][maxPriority=%d]

and then spins in a ``b .`` loop. Hooking the formatter turns every internal
diagnostic — asserts, heap stats, the clock — into readable output, which is by
far the fastest way to find out why a boot stopped: instead of "stuck at
0x0003063A" you get the exact failed expression, source file and line.

The formatter is located by shape rather than by hardcoded address: find the
assert format string, find the literal-pool word that points at it, find the
``ldr`` that loads that literal, then take the target of the next ``bl``.
"""

from __future__ import annotations

import re
import struct
from dataclasses import dataclass
from typing import Optional

from capstone import CS_ARCH_ARM, CS_MODE_THUMB, Cs
from unicorn import UC_HOOK_CODE
from unicorn.arm_const import (
    UC_ARM_REG_R0,
    UC_ARM_REG_R1,
    UC_ARM_REG_R2,
    UC_ARM_REG_R3,
    UC_ARM_REG_SP,
)

ASSERT_FMT_PATTERN = rb"Assert Failed:%s, File:%s:%d"


@dataclass
class FormatterSite:
    formatter: int
    assert_format: int
    load_site: int
    call_site: int


def find_formatter(payload: bytes, link_address: int) -> Optional[FormatterSite]:
    match = re.search(ASSERT_FMT_PATTERN, payload)
    if not match:
        return None
    fmt_addr = link_address + match.start()

    # The literal-pool word holding the format string's address.
    literal_addr = None
    for off in range(0, len(payload) - 3, 4):
        if struct.unpack_from("<I", payload, off)[0] == fmt_addr:
            literal_addr = link_address + off
            break
    if literal_addr is None:
        return None

    # The `ldr rX, [pc, #imm]` that reads it lives just before the literal pool.
    md = Cs(CS_ARCH_ARM, CS_MODE_THUMB)
    window_start = max(link_address, literal_addr - 0x400)
    off = window_start - link_address
    size = literal_addr - window_start
    load_site = None
    for insn in md.disasm(payload[off : off + size], window_start):
        if insn.mnemonic.startswith("ldr") and "[pc" in insn.op_str:
            try:
                imm = int(insn.op_str.rsplit("#", 1)[1].rstrip("]"), 0)
            except (IndexError, ValueError):
                continue
            if (((insn.address + 4) & ~3) + imm) == literal_addr:
                load_site = insn.address
    if load_site is None:
        return None

    # The first `bl` after the load is the formatter call.
    off = load_site - link_address
    for insn in md.disasm(payload[off : off + 0x40], load_site):
        if insn.mnemonic == "bl":
            target = int(insn.op_str.lstrip("#"), 0)
            return FormatterSite(
                formatter=target & ~1,
                assert_format=fmt_addr,
                load_site=load_site,
                call_site=insn.address,
            )
    return None


def mini_printf(fmt: str, args: list, read_cstr) -> str:
    """Enough of printf for the firmware's own diagnostic strings."""
    out: list[str] = []
    i = 0
    argi = 0

    def next_arg():
        nonlocal argi
        value = args[argi] if argi < len(args) else 0
        argi += 1
        return value

    while i < len(fmt):
        ch = fmt[i]
        if ch != "%":
            out.append(ch)
            i += 1
            continue
        i += 1
        if i < len(fmt) and fmt[i] == "%":
            out.append("%")
            i += 1
            continue
        spec = ""
        while i < len(fmt) and fmt[i] in "-+ #0123456789.lhz":
            spec += fmt[i]
            i += 1
        if i >= len(fmt):
            break
        conv = fmt[i]
        i += 1
        width = spec.lstrip("-+ #0").split(".")[0]
        flags = spec[: len(spec) - len(spec.lstrip("-+ #0"))]
        pad_zero = "0" in flags
        left = "-" in flags

        if conv == "s":
            text = read_cstr(next_arg()) or "(null)"
        elif conv in "diu":
            value = next_arg()
            if conv == "d" and value >= 0x80000000:
                value -= 0x100000000
            text = str(value)
        elif conv in "xX":
            value = next_arg()
            text = format(value, conv)
        elif conv == "c":
            text = chr(next_arg() & 0xFF)
        elif conv == "p":
            text = "0x%08X" % next_arg()
        elif conv == "f":
            next_arg()
            text = "<float>"
        else:
            text = "%" + spec + conv

        if width.isdigit():
            n = int(width)
            if len(text) < n:
                text = text.ljust(n) if left else text.rjust(n, "0" if pad_zero else " ")
        out.append(text)
    return "".join(out)


class FirmwareConsole:
    """Hooks the firmware's formatter and renders what it was about to print."""

    MAX_VARARGS = 8

    def __init__(self, machine, site: FormatterSite, log=print, max_lines: int = 500) -> None:
        self.machine = machine
        self.site = site
        self.log = log
        self.max_lines = max_lines
        self.lines: list[str] = []
        self.asserts: list[str] = []
        machine.uc.hook_add(
            UC_HOOK_CODE, self._on_format, begin=site.formatter, end=site.formatter
        )

    def _read_cstr(self, addr: int, limit: int = 200) -> Optional[str]:
        if addr < 0x1000:
            return None
        try:
            raw = bytes(self.machine.uc.mem_read(addr, limit))
        except Exception:
            return None
        end = raw.find(b"\x00")
        return raw[: end if end >= 0 else limit].decode("utf-8", "replace")

    def _on_format(self, uc, address, size, user_data) -> None:
        fmt_ptr = uc.reg_read(UC_ARM_REG_R1)
        fmt = self._read_cstr(fmt_ptr)
        if not fmt:
            return

        sp = uc.reg_read(UC_ARM_REG_SP)
        args = [uc.reg_read(UC_ARM_REG_R2), uc.reg_read(UC_ARM_REG_R3)]
        try:
            stack = bytes(uc.mem_read(sp, 4 * self.MAX_VARARGS))
            args += list(struct.unpack(f"<{self.MAX_VARARGS}I", stack))
        except Exception:
            pass

        try:
            text = mini_printf(fmt, args, self._read_cstr)
        except Exception as exc:  # a malformed format must never kill the run
            text = f"{fmt!r} <unformatted: {exc}>"

        text = text.rstrip("\r\n")
        if len(self.lines) < self.max_lines:
            self.lines.append(text)
        if fmt_ptr == self.site.assert_format:
            self.asserts.append(text)
            self.log(f"  [firmware assert] {text}")
        else:
            self.log(f"  [firmware] {text}")

    def summary(self) -> str:
        if not self.lines:
            return "  firmware console: silent"
        out = ["  firmware console:"]
        out += [f"    | {line}" for line in self.lines[-25:]]
        return "\n".join(out)
