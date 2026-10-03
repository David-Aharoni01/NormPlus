#!/usr/bin/env python3
"""The NAND read-out patch: one instruction changed, so 0xEE can read the SPI NAND.

Why this exists: the watch's factory resources are tens of megabytes of images in
the SPI NAND (#64, #65), and #66 established there is no free way to read them --
no vendor server has the files, and the firmware's one arbitrary-memory read
(command 0xEE, handler 0x0003779C) reaches only the CPU address space. The NAND is
not memory-mapped, so 0xEE's ``memcpy`` faults on every NAND offset and the watch
never answers. The only route left is to make the firmware call its own NAND
driver, which is this patch (#68).

**What it changes.** One instruction. The 0xEE handler ends with

    0x000377CE  bl 0x00020EC4        ; memcpy(dst, addr, len)

and that call becomes ``bl`` to a routine appended after the image's last byte,
which either tail-calls the same ``memcpy`` or, for a tagged address, calls the
firmware's own NAND read instead. Nothing else in the image is touched: every
other byte, every other handler, and 0xEE's own behaviour for CPU addresses are
exactly as shipped.

**How a NAND read is asked for.** The address field's top nibble selects the
address space: ``0xFnnnnnnn`` means "NAND byte offset ``0xnnnnnnn``" (0-256 MB),
anything else is a CPU address and behaves as before. 0xF0000000 is unmapped on
the Apollo3, so no address that means anything today changes meaning, and the tag
costs no extra payload byte -- which matters because the handler never sees the
payload length (the dispatcher at 0x00039F38 passes it in r2 and the handler
overwrites r2 before the hook is reached), so a sixth payload byte could not be
told apart from a stale one::

    normcmd EE 70 --payload 'f2 6d a4 30 80'     # NAND 0x026DA430, 128 bytes
    normcmd EE 70 --payload '00 02 00 00 10'     # 0x00020000 as before (CPU)

**What the physical watch taught us (#70), and why the routine is 56 bytes and not
40.** The first version passed the device object's first word as the read's
instance argument, because that is what the firmware's own OTA call site does
(0x0005A54E). On the emulated watch that word is 0; on the physical watch, idle, it
is **0xFF** -- so the read hit ``assert(instance < ( 1 ))`` at its very first
branch. Every assert in this firmware ends in ``b .`` (an infinite loop at
0x0003063A, after printing ``Assert Failed:``), so the task that called it never
came back: the watch kept advertising, its OTA task kept answering, and its whole
0x6F channel was dead until it was restarted. So the instance is now a literal
**0**, which is the only value the assert permits.

Reading the watch's own SRAM out over 0xEE then showed the rest of it. On an idle
physical watch the NAND driver is exactly as its init left it -- instance state
byte 0, die cache 0xFF, and **the lock every access takes is NULL** -- while on the
emulated watch all three show a driver that has been opened and used. Taking a NULL
lock asserts as well (0x00031B58), so the routine now **checks that the lock exists
and gives up quietly if it does not**, which is why it cannot wedge a watch
whatever state the driver is in. A read that gives up answers 128 zero bytes, which
is what a lost read looks like too (see #69's retry discipline).

That leaves one thing still open: something has to bring the storage stack up
before a dump. The function that does it (0x00032EC0: it creates the lock at
0x1000186C and registers the three MSPI devices, our NAND at 0x1005D644 among them)
is reached through a registry rather than a direct call, so who calls it, and
whether an OTA step or a UI action is enough, is #70's remaining question.

**The routine it calls** is the vtable read of the device object the firmware keeps
at ``[0x10006BCC]``: ``read(instance, byte_address, destination, length)`` at
``+0x0C``, which is ``..\\..\\Device\\Nand\\ew_dev_spinand.c`` (its asserts
``instance < ( 1 )`` at line 1474 and the non-NULL destination at 1475), the same
call the OTA task makes at 0x0005A54A to read a staged image header back. It takes
the driver's own lock (0x00031B40) and selects the die from ``address >> 20``, so
both dies are reachable and no caller-side locking is needed. The patch reads the
pointer through the vtable, as the firmware does, and does nothing if the device
is not up.

**Reply size is 128 bytes per request**, which is the shipped handler's own limit:
its destination is a 0x80-byte stack buffer (``sub sp, #0x8C``; ``memset`` of 0x80
at 0x000377B4) and the 0x6F reply builder at 0x000393B8 refuses any frame of 255
bytes or more (``cmp #0xFF`` on both of its paths). Raising it to ~240 would mean
editing the handler's own stack frame as well, and that was **decided against**
(#69): a slower dump is worth more than a patch that touches the frame, since
this image is the one that gets flashed to the only watch we have.

Build one with::

    normfw patch-nand NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin -o patched.bin

which appends the routine, rewrites the one call, and re-seals the length and CRC
(``image_tool``, docs/firmware.md section 7) so the image validates. The result is
derived from the vendor's image: like the image itself it must never be committed
to this repository.
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

from . import image_tool

#: The ``bl memcpy`` at the end of the 0xEE handler -- the one instruction we change.
HOOK_ADDRESS = 0x000377CE
#: What must be there: ``bl 0x00020EC4``. Refuse to patch anything else.
HOOK_EXPECTED = bytes.fromhex("e9f779fb")

#: ``memcpy(dst, src, len)``; the patch tail-calls it for every CPU address.
MEMCPY = 0x00020EC4
#: The storage device object the firmware dereferences for every NAND access.
DEVICE_POINTER = 0x10006BCC
#: Its vtable slot for ``read(instance, address, destination, length)``.
READ_SLOT = 0x0C
#: Instance 0's slot in the driver's own table (0x10011CD0 + instance * 48), +4:
#: the pointer whose target is the lock every access takes. NULL until the
#: storage stack is brought up, and taking a NULL lock asserts (#70).
INSTANCE_LOCK = 0x10011CD4
#: Top nibble of the address field that means "this is a NAND offset".
NAND_TAG = 0xF

#: Where the image's payload starts in the file (4-byte OTA address + 44-byte header).
PAYLOAD_OFFSET = image_tool.HEADER_LEN
#: The payload is linked here; the appended routine goes after its last byte.
LINK_ADDRESS = 0x00020000


def _bl(at: int, target: int, *, link: bool = True) -> bytes:
    """Encode a wide Thumb-2 ``bl``/``b.w`` at *at* branching to *target*."""
    offset = target - (at + 4)
    if not -(1 << 24) <= offset < (1 << 24) or offset & 1:
        raise ValueError(f"0x{target:08X} is out of range of a branch at 0x{at:08X}")
    imm = offset >> 1
    s = (imm >> 23) & 1
    i1, i2 = (imm >> 22) & 1, (imm >> 21) & 1
    hw1 = 0xF000 | (s << 10) | ((imm >> 11) & 0x3FF)
    hw2 = (0xD000 if link else 0x9000) | ((1 ^ i1 ^ s) << 13) | ((1 ^ i2 ^ s) << 11) \
        | (imm & 0x7FF)
    return struct.pack("<HH", hw1, hw2)


def build_routine(at: int) -> tuple[bytes, list[str]]:
    """The appended routine, assembled for *at*, with the source it assembles.

    Entered from the hook with the shipped handler's own arguments -- r0 the
    0x80-byte destination buffer, r1 the address from the payload, r2 the length --
    and nothing else disturbed, so the memcpy path is indistinguishable from the
    unpatched firmware. r4 is the only register it touches beyond the argument
    registers, and it is saved.

    Two of its four instructions exist because of what the physical watch turned
    out to do (#70); the module docstring explains both:

    * the instance argument is a literal 0, not the device object's first word,
      which is 0xFF on an idle watch and trips ``assert(instance < ( 1 ))``;
    * it gives up quietly unless the driver's lock object exists, because every
      assert in this firmware ends in an infinite loop.
    """
    # Assembled twice: once to find where the labels land, once for real. Each
    # instruction's own position comes from what has been emitted before it rather
    # than from a number written here, because a stale offset in hand-assembled
    # Thumb is a watch that has to be flashed again.
    def assemble(offsets: dict[str, int]) -> list[tuple]:
        out: list[tuple] = []
        pos = 0

        def emit(data: bytes, source: str, label: str | None = None) -> None:
            nonlocal pos
            out.append((data, source) if label is None else (data, source, label))
            pos += len(data)

        def literal(label: str) -> int:
            """``ldr rX, [pc, #imm]``'s imm8, from where this instruction sits."""
            return ((at + offsets[label] - ((at + pos + 4) & ~3)) >> 2) & 0xFF

        def branch(label: str) -> int:
            return (offsets[label] - (pos + 4)) >> 1

        def cbz(label: str, rn: int = 4) -> bytes:
            """``cbz rN, label``: imm5 holds bits 5:1 of the offset, i holds bit 6."""
            offset = offsets[label] - (pos + 4)
            if offsets[label] and not 0 <= offset <= 126 or offset % 2:
                raise AssertionError(f"cbz cannot reach {label} from +0x{pos:X}")
            return struct.pack("<H", 0xB100 | (((offset >> 6) & 1) << 9)
                               | (((offset >> 1) & 0x1F) << 3) | rn)

        emit(b"\x0b\x0f", "lsrs r3, r1, #0x1c")             # the address's top nibble
        emit(b"\x0f\x2b", "cmp r3, #0xf")                   # tagged as a NAND offset?
        emit(struct.pack("<H", 0xD000 | (branch("nand") & 0xFF)), "beq #nand")
        emit(_bl(at + pos, MEMCPY, link=False), "b.w #memcpy")      # no: as shipped
        emit(b"\x10\xb5", "push {r4, lr}", "nand")
        emit(b"\x6f\xf3\x1f\x71", "bfc r1, #0x1c, #4")      # r1 = the NAND byte offset
        emit(b"\x13\x46", "mov r3, r2")                     # r3 = length
        emit(b"\x02\x46", "mov r2, r0")                     # r2 = destination
        # Is the driver even up? [[instance 0 + 4]] is the lock every access takes,
        # and taking a NULL one asserts (0x00031B58) -- which never returns.
        emit(struct.pack("<H", 0x4C00 | literal("lock")), "ldr r4, [pc, #lock]")
        emit(b"\x24\x68", "ldr r4, [r4]")                   # -> 0x1000186C
        emit(cbz("done"), "cbz r4, #done")
        emit(b"\x24\x68", "ldr r4, [r4]")                   # the lock object itself
        emit(cbz("done"), "cbz r4, #done")                  # not up: answer nothing
        emit(struct.pack("<H", 0x4C00 | literal("device")), "ldr r4, [pc, #device]")
        emit(b"\x24\x68", "ldr r4, [r4]")                   # the device object
        emit(cbz("done"), "cbz r4, #done")
        emit(struct.pack("<H", 0x6800 | ((READ_SLOT >> 2) << 6) | (4 << 3) | 4),
             "ldr r4, [r4, #0xc]")                          # ew_dev_spinand's read
        emit(b"\x00\x20", "movs r0, #0")                    # instance 0, as it asserts
        emit(b"\xa0\x47", "blx r4")
        emit(b"\x10\xbd", "pop {r4, pc}", "done")
        while (pos + 2) % 4:                                # the literals must be aligned
            emit(b"\x00\xbf", "nop")
        emit(b"\x00\xbf", "nop")
        emit(struct.pack("<I", INSTANCE_LOCK), ".word 0x10011CD4", "lock")
        emit(struct.pack("<I", DEVICE_POINTER), ".word 0x10006BCC", "device")
        return out

    # The first pass only needs each instruction's WIDTH, so zeroed labels do; the
    # masks keep those dummy encodings in range, and check_routine verifies the
    # real ones against the addresses they have to resolve to.
    offsets, at_offset = {}, 0
    for entry in assemble(dict.fromkeys(("nand", "done", "lock", "device"), 0)):
        if len(entry) == 3:          # this instruction carries a label
            offsets[entry[2]] = at_offset
        at_offset += len(entry[0])
    code = assemble(offsets)
    blob = b"".join(entry[0] for entry in code)
    if len(blob) % 4 or len(blob) != offsets["device"] + 4 or offsets["lock"] % 4:
        raise AssertionError(f"routine is {len(blob)} bytes, labels {offsets}")
    return blob, [entry[1] for entry in code], offsets


def disassemble(blob: bytes, at: int) -> list[str]:
    """``blob`` as text, for the self-check and for ``--print``."""
    import capstone

    md = capstone.Cs(capstone.CS_ARCH_ARM, capstone.CS_MODE_THUMB)
    return [f"{i.address:08X}  {i.mnemonic} {i.op_str}".rstrip()
            for i in md.disasm(blob, at)]


def check_routine(blob: bytes, at: int, sources: list[str], offsets: dict) -> None:
    """Assert the bytes really are the instructions they are meant to be.

    Hand-assembled Thumb-2 is exactly the kind of thing that is wrong in a way
    nothing notices until a watch does not boot, so every instruction is read back
    with the same disassembler the firmware was read with and compared against the
    source line it came from, with every label resolved to the address it must be.
    A `ldr rX, [pc, #n]` is checked against where *n* actually lands, so a literal
    that drifted out of word alignment or out of reach cannot pass.
    """
    words = sum(1 for source in sources if source.startswith("."))
    code = blob[:-4 * words] if words else blob
    got = disassemble(code, at)
    want = []
    for source, line in zip((s for s in sources if not s.startswith(".")), got):
        here = int(line.split("  ", 1)[0], 16)       # where this instruction really is
        for label, offset in offsets.items():
            if f"#{label}" not in source:
                continue
            if label in ("lock", "device"):          # a literal, named pc-relatively
                source = source.replace(
                    f"#{label}", f"#0x{(at + offset) - ((here + 4) & ~3):x}")
            else:
                source = source.replace(f"#{label}", f"#0x{at + offset:x}")
        want.append(source.replace("#memcpy", f"#0x{MEMCPY:x}"))
    for source, line in zip(want, got):
        if line.split("  ", 1)[1] != source:
            raise AssertionError(f"assembled {line!r}, meant {source!r}")
    if len(got) != len(want):
        raise AssertionError(f"disassembled {len(got)} instructions, wrote {len(want)}")
    # ...and the literals hold what they are supposed to.
    held = [int.from_bytes(blob[-4 * words:][i * 4:i * 4 + 4], "little") for i in range(words)]
    if held != [INSTANCE_LOCK, DEVICE_POINTER]:
        raise AssertionError(f"the literals are {[hex(v) for v in held]}")


def apply(image: bytes) -> tuple[bytes, int]:
    """Patch *image*; return the sealed result and the appended routine's address.

    Refuses an image whose 0xEE handler does not hold the expected call, and one
    that already carries the patch.
    """
    info = image_tool.parse(image)
    if info["link"] != LINK_ADDRESS:
        raise ValueError(f"image links at 0x{info['link']:08X}, not 0x{LINK_ADDRESS:08X}")
    if info["declared_len"] != info["payload_len"]:
        raise ValueError("image length field does not match its payload -- seal it first")

    data = bytearray(image)
    at = PAYLOAD_OFFSET + (HOOK_ADDRESS - LINK_ADDRESS)
    found = bytes(data[at:at + 4])
    if found != HOOK_EXPECTED:
        raise ValueError(
            f"0x{HOOK_ADDRESS:08X} holds {found.hex(' ')}, expected "
            f"{HOOK_EXPECTED.hex(' ')} (bl 0x{MEMCPY:08X}) -- this is not the image "
            "this patch was derived from, or it is patched already")

    # The routine goes after the payload's last byte, 4-byte aligned.
    end = LINK_ADDRESS + info["payload_len"]
    routine_at = (end + 3) & ~3
    data += b"\xff" * (routine_at - end)
    blob, sources, offsets = build_routine(routine_at)
    check_routine(blob, routine_at, sources, offsets)
    data += blob
    data[at:at + 4] = _bl(HOOK_ADDRESS, routine_at)

    payload = bytes(data[PAYLOAD_OFFSET:])
    struct.pack_into("<I", data, image_tool.OFF_LEN, len(payload))
    struct.pack_into("<I", data, image_tool.OFF_CRC, image_tool.bootloader_crc32(payload))
    return bytes(data), routine_at


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog="normfw patch-nand", description=__doc__.split("\n\n")[0],
        epilog="The patched image is vendor-derived: never commit it.")
    ap.add_argument("image", help="the stock Apollo image (NORM/assets/Apollo3_*.bin)")
    ap.add_argument("-o", "--output", help="where to write the patched image")
    ap.add_argument("--print", action="store_true", dest="print_only",
                    help="disassemble the routine and stop, writing nothing")
    args = ap.parse_args(argv)

    image = Path(args.image).read_bytes()
    try:
        patched, routine_at = apply(image)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    before, after = image_tool.parse(image), image_tool.parse(patched)
    print(f"hook      0x{HOOK_ADDRESS:08X}  bl 0x{MEMCPY:08X} -> bl 0x{routine_at:08X}")
    print(f"routine   0x{routine_at:08X}  {after['payload_len'] - before['payload_len']} "
          f"bytes appended")
    blob = patched[PAYLOAD_OFFSET + routine_at - LINK_ADDRESS:]
    for line in disassemble(blob[:-8], routine_at):
        print(f"  {line}")
    for i, value in enumerate((INSTANCE_LOCK, DEVICE_POINTER)):
        print(f"  {routine_at + len(blob) - 8 + i * 4:08X}  .word 0x{value:08X}")
    print(f"length    {before['declared_len']} -> {after['declared_len']}")
    print(f"image CRC 0x{before['declared_crc']:08X} -> 0x{after['declared_crc']:08X}")
    print(f"transport CRC-16 is now 0x{after['transport_crc16']:04X}")

    if args.print_only:
        return 0
    if not args.output:
        print("error: pass -o OUTPUT (or --print to only disassemble)", file=sys.stderr)
        return 2
    Path(args.output).write_bytes(patched)
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
