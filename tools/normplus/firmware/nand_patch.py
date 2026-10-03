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

#: The ``bl memcpy`` at the end of the 0xEE handler -- the call we redirect.
HOOK_ADDRESS = 0x000377CE
#: What must be there: ``bl 0x00020EC4``. Refuse to patch anything else.
HOOK_EXPECTED = bytes.fromhex("e9f779fb")

#: **The storage teardown** (0x00032F78): it powers the NAND down, unregisters the
#: device -- which is what puts 0xFF in the instance field -- and destroys the lock
#: every access takes. Replaced with ``movs r0, #0; bx lr``, so the storage stack,
#: once up, stays up and a dump can run without the watch being touched (#70).
TEARDOWN_ADDRESS = 0x00032F78
TEARDOWN_EXPECTED = bytes.fromhex("10b5ff20")        # push {r4, lr}; movs r0, #0xff
TEARDOWN_REPLACEMENT = bytes.fromhex("00204770")     # movs r0, #0;  bx lr

#: **The lock create** (0x00031BA8), where the only repeatable allocation in all of
#: this lives: it allocates (0x000B9A5C) and stores into ``slot+4`` without ever
#: checking whether the slot already holds a lock. With the teardown gone the
#: storage manager still believes it tore down and calls its bring-up again, so
#: without this guard each cycle would leak one lock. The guard makes it
#: create-if-absent **for the storage slot only**: every other lock in the firmware
#: behaves exactly as shipped. The rest of the bring-up is safe to repeat --
#: 0x00030C50 registers a device by assigning its fields (``str r6, [r5]`` is the
#: instance), not by linking it into anything.
CREATE_ADDRESS = 0x00031BA8
CREATE_EXPECTED = bytes.fromhex("70b51646")          # push {r4,r5,r6,lr}; mov r6, r2
#: Where the guard rejoins the original function, after the two it replaced.
CREATE_RESUME = 0x00031BAC
#: The slot the guard protects: ``{object, allocation}`` for the storage lock.
DRIVER_LOCK = 0x1000186C

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
#: The other tag: "give me a slice of the page already loaded", with no NAND work.
#: Both nibbles name addresses the Apollo3 does not map, so neither changes the
#: meaning of anything 0xEE could be asked for before.
BUFFER_TAG = 0xE

#: **The page buffer, and why this patch is shaped around it.** The driver's read
#: issues a full PAGE READ (command 0x13, at 0x0003E416) on *every* call and
#: then copies out the slice asked for -- so 128-byte reads cost sixteen page reads
#: a page, which is why a chunk takes ~0.28 s on hardware where a CPU read takes
#: 0.03 s. Its length argument is a word and it loops across pages, so one call can
#: fetch a whole page; what it needs is somewhere to put it, and the 0x6F reply
#: builder refuses frames of 255 bytes or more (0x000393B8), so the reply cannot
#: carry one. The OTA's own page-assembly buffer is exactly the right size and sits
#: idle whenever no update is in progress: 2048 bytes at 0x10010C64, and the length
#: is the firmware's own, in three places -- the SET handler zeroes 0x800 of it
#: (``bzero`` at 0x00021122, its length at 0x0003B808), the CRC runs over 0x800 of
#: it (0x0003BD12), and the write descriptor is built as {it, 0x800} (0x0003BE36).
#: The data handler fills it piece by piece (``memcpy`` at 0x0003BA7E), and every
#: one of its five references is in the OTA handlers, which is why it is free
#: whenever no update is running. So a 0xF read loads the page there and
#: answers the slice, and 0xE reads answer later slices with no NAND work at all:
#: one page read a page instead of sixteen.
PAGE_BUFFER = 0x10010C64
PAGE_SIZE = 0x800
#: What a failed read answers with, in a one-byte reply (#70): the storage stack
#: is not up...
DRIVER_DOWN_CODE = 0xFE
#: ...or the slice asked for runs past the end of the page the buffer holds, which
#: only a caller asking wrongly can provoke. ``nanddump`` knows both by name.
CROSSES_PAGE_CODE = 0xFD

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


def build_routine(at: int) -> tuple[bytes, list[str], dict]:
    """The appended routine, assembled for *at*, with the source it assembles.

    Entered from the hook with the shipped handler's own arguments -- r0 the
    0x80-byte destination buffer, r1 the address from the payload, r2 the length --
    so an untagged address tail-calls the same ``memcpy`` and is indistinguishable
    from the unpatched firmware.

    A tagged address does one of two things (:data:`PAGE_BUFFER` explains why):

    * ``0xF`` -- read the whole page containing it into the OTA's page buffer, one
      PAGE READ, then answer the slice asked for;
    * ``0xE`` -- answer a slice of whatever is in that buffer, touching no NAND.

    So sixteen 128-byte reads of one page cost one page read rather than sixteen.

    Everything the physical watch taught us is here too (#70): the instance
    argument is a literal 0, because the device object's first word is 0xFF on an
    idle watch and trips ``assert(instance < ( 1 ))``; the driver's lock is checked
    before anything is called, because every assert in this firmware ends in an
    infinite loop; and a read that cannot be done answers one byte with the reason
    rather than a buffer of zeros, which is also what zero data looks like.

    r5 is the caller's reply length, which is how that one byte is reported: the
    handler stores r5 into the frame after this returns (0x000377DA) and never
    reloads it. So r5 is deliberately *not* saved -- the failure paths set it -- and
    every other register this touches is.
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

        def literal(label: str, rt: int = 4) -> bytes:
            """``ldr rT, [pc, #imm]`` for a word in the routine's own pool."""
            imm = ((at + offsets[label] - ((at + pos + 4) & ~3)) >> 2) & 0xFF
            return struct.pack("<H", 0x4800 | (rt << 8) | imm)

        def branch(label: str) -> int:
            return (offsets[label] - (pos + 4)) >> 1

        def cbz(label: str, rn: int) -> bytes:
            offset = offsets[label] - (pos + 4)
            if offsets[label] and not 0 <= offset <= 126 or offset % 2:
                raise AssertionError(f"cbz cannot reach {label} from +0x{pos:X}")
            return struct.pack("<H", 0xB100 | (((offset >> 6) & 1) << 9)
                               | (((offset >> 1) & 0x1F) << 3) | rn)

        def b(label: str) -> bytes:
            return struct.pack("<H", 0xE000 | (branch(label) & 0x7FF))

        # -- which of the three is this? ------------------------------------
        emit(b"\x0b\x0f", "lsrs r3, r1, #0x1c")            # the address's top nibble
        emit(b"\x0f\x2b", "cmp r3, #0xf")
        emit(struct.pack("<H", 0xD000 | (branch("load") & 0xFF)), "beq #load")
        emit(b"\x0e\x2b", "cmp r3, #0xe")
        emit(struct.pack("<H", 0xD000 | (branch("fetch") & 0xFF)), "beq #fetch")
        emit(_bl(at + pos, MEMCPY, link=False), "b.w #memcpy")   # untagged: as shipped

        # -- 0xF: load the page, then answer the slice ----------------------
        # r6 keeps the destination and r7 the length across the driver call; r5 is
        # left alone so a failure can report itself in the reply length.
        emit(b"\xd0\xb5", "push {r4, r6, r7, lr}", "load")
        emit(b"\x06\x46", "mov r6, r0")                    # the handler's buffer
        emit(b"\x17\x46", "mov r7, r2")                    # the length asked for
        emit(b"\x6f\xf3\x1f\x71", "bfc r1, #0x1c, #4")   # r1 = the NAND offset
        # Does the slice fit in the one page this can hold? Asked before
        # anything is done, so a slice that does not costs no page read and
        # cannot be half-answered. (offset + len - 1) >> 11 is zero for
        # exactly the slices that fit; r0 is free here, r6 holds the buffer.
        emit(b"\xc1\xf3\x0a\x00", "ubfx r0, r1, #0, #0xb")
        emit(b"\xc0\x19", "adds r0, r0, r7")
        emit(b"\x01\x38", "subs r0, #1")
        emit(b"\xc0\x0a", "lsrs r0, r0, #0xb")
        emit(struct.pack("<H", 0xD100 | (branch("span") & 0xFF)), "bne #span")
        # Is the driver up? Taking a NULL lock asserts (0x00031B58), and an assert
        # never returns.
        emit(literal("lock"), "ldr r4, [pc, #lock]")
        emit(b"\x24\x68", "ldr r4, [r4]")                  # -> 0x1000186C
        emit(cbz("down", 4), "cbz r4, #down")
        emit(b"\x24\x68", "ldr r4, [r4]")                  # the lock object
        emit(cbz("down", 4), "cbz r4, #down")
        emit(b"\x0c\x46", "mov r4, r1")                    # the offset again
        emit(b"\xcb\x0a", "lsrs r3, r1, #0xb")             # page-align the address
        emit(b"\xd9\x02", "lsls r1, r3, #0xb")
        emit(literal("buffer", 2), "ldr r2, [pc, #buffer]")  # where the page goes
        emit(b"\x40\xf6\x00\x03", "movw r3, #0x800")      # a whole page
        emit(literal("device", 0), "ldr r0, [pc, #device]")
        emit(b"\x00\x68", "ldr r0, [r0]")                  # the device object
        emit(cbz("down", 0), "cbz r0, #down")
        emit(b"\xc0\x68", "ldr r0, [r0, #0xc]")            # ew_dev_spinand's read
        emit(b"\x84\x46", "mov ip, r0")
        emit(b"\x00\x20", "movs r0, #0")                   # instance 0, as it asserts
        emit(b"\xe0\x47", "blx ip")
        emit(cbz("copy", 0), "cbz r0, #copy")                # 0 = it read the page
        emit(b("fail"), "b #fail")                          # anything else is its code

        # -- 0xE: answer from the buffer, no NAND at all --------------------
        emit(b"\xd0\xb5", "push {r4, r6, r7, lr}", "fetch")
        emit(b"\x06\x46", "mov r6, r0")
        emit(b"\x17\x46", "mov r7, r2")
        emit(b"\x6f\xf3\x1f\x71", "bfc r1, #0x1c, #4")
        # Does the slice fit in the one page this can hold? Asked before
        # anything is done, so a slice that does not costs no page read and
        # cannot be half-answered. (offset + len - 1) >> 11 is zero for
        # exactly the slices that fit; r0 is free here, r6 holds the buffer.
        emit(b"\xc1\xf3\x0a\x00", "ubfx r0, r1, #0, #0xb")
        emit(b"\xc0\x19", "adds r0, r0, r7")
        emit(b"\x01\x38", "subs r0, #1")
        emit(b"\xc0\x0a", "lsrs r0, r0, #0xb")
        emit(struct.pack("<H", 0xD100 | (branch("span") & 0xFF)), "bne #span")
        emit(b"\x0c\x46", "mov r4, r1")

        # -- the slice: memcpy(dst, buffer + offset % 0x800, len) -----------
        emit(b"\xc4\xf3\x0a\x04", "ubfx r4, r4, #0, #0xb", "copy")
        emit(literal("buffer", 1), "ldr r1, [pc, #buffer]")
        emit(b"\x21\x44", "add r1, r4")                    # the slice's source
        emit(b"\x30\x46", "mov r0, r6")                    # the handler's buffer
        emit(b"\x3a\x46", "mov r2, r7")                    # the length asked for
        emit(_bl(at + pos, MEMCPY), "bl #memcpy")
        emit(b"\xd0\xbd", "pop {r4, r6, r7, pc}")

        # -- it could not be done, and says which ---------------------------
        emit(struct.pack("<BB", CROSSES_PAGE_CODE, 0x20),
             f"movs r0, #0x{CROSSES_PAGE_CODE:x}", "span")   # it crosses a page
        emit(b("fail"), "b #fail")
        emit(struct.pack("<BB", DRIVER_DOWN_CODE, 0x20),
             f"movs r0, #0x{DRIVER_DOWN_CODE:x}", "down")    # the stack is not up
        emit(b"\x30\x70", "strb r0, [r6]", "fail")         # the reason, in the buffer
        emit(b"\x01\x25", "movs r5, #1")                   # one byte = it failed
        emit(b"\xd0\xbd", "pop {r4, r6, r7, pc}")

        while (pos + 2) % 4:                                 # align the pool
            emit(b"\x00\xbf", "nop")
        emit(b"\x00\xbf", "nop")
        emit(struct.pack("<I", INSTANCE_LOCK), ".word 0x10011CD4", "lock")
        emit(struct.pack("<I", DEVICE_POINTER), ".word 0x10006BCC", "device")
        emit(struct.pack("<I", PAGE_BUFFER), ".word 0x10010C64", "buffer")
        return out

    # The first pass only needs each instruction's WIDTH, so zeroed labels do; the
    # masks keep those dummy encodings in range, and check_routine verifies the
    # real ones against the addresses they have to resolve to.
    labels = ("load", "fetch", "copy", "span", "down", "fail",
              "lock", "device", "buffer")
    offsets, at_offset = {}, 0
    for entry in assemble(dict.fromkeys(labels, 0)):
        if len(entry) == 3:
            offsets[entry[2]] = at_offset
        at_offset += len(entry[0])
    code = assemble(offsets)
    blob = b"".join(entry[0] for entry in code)
    if len(blob) % 4 or offsets["lock"] % 4 or len(blob) != offsets["buffer"] + 4:
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
            if offset >= len(code):                  # in the pool: named pc-relatively
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
    pool = {"lock": INSTANCE_LOCK, "device": DEVICE_POINTER, "buffer": PAGE_BUFFER}
    held = {label: int.from_bytes(blob[offset:offset + 4], "little")
            for label, offset in offsets.items() if offset >= len(code)}
    if held != {label: pool[label] for label in held}:
        raise AssertionError(f"the literals are {[(k, hex(v)) for k, v in held.items()]}")
    if len(held) != words:
        raise AssertionError(f"{words} words in the pool, {len(held)} of them named")


def build_create_guard(at: int) -> tuple[bytes, list[str], dict]:
    """"Create the storage lock only if it is not there already", as a detour.

    Stands in for the two instructions at :data:`CREATE_ADDRESS` and rejoins the
    function at :data:`CREATE_RESUME`. Nothing is saved at a function's entry, so
    the only registers it may touch are the argument ones -- and r3, which this
    function never reads (its body uses r0, r1 and r2 only), so the check needs no
    wide encodings and no stack.
    """
    def assemble(offsets: dict[str, int]) -> list[tuple]:
        out: list[tuple] = []
        pos = 0

        def emit(data: bytes, source: str, label: str | None = None) -> None:
            nonlocal pos
            out.append((data, source) if label is None else (data, source, label))
            pos += len(data)

        def literal(label: str) -> int:
            return ((at + offsets[label] - ((at + pos + 4) & ~3)) >> 2) & 0xFF

        def cbz(label: str, rn: int) -> bytes:
            offset = offsets[label] - (pos + 4)
            if offsets[label] and not 0 <= offset <= 126 or offset % 2:
                raise AssertionError(f"cbz cannot reach {label} from +0x{pos:X}")
            return struct.pack("<H", 0xB100 | (((offset >> 6) & 1) << 9)
                               | (((offset >> 1) & 0x1F) << 3) | rn)

        emit(struct.pack("<H", 0x4B00 | literal("slot")), "ldr r3, [pc, #slot]")
        emit(b"\x99\x42", "cmp r1, r3")                 # is this the storage lock?
        emit(struct.pack("<H", 0xD100 | ((offsets["proceed"] - (pos + 4)) >> 1) & 0xFF),
             "bne #proceed")                            # no: every other lock as shipped
        emit(b"\x0b\x68", "ldr r3, [r1]")               # the slot's object
        emit(cbz("proceed", 3), "cbz r3, #proceed")     # empty: create it, as shipped
        emit(b"\x00\x20", "movs r0, #0")                # there already: success...
        emit(b"\x70\x47", "bx lr")                      # ...and nothing allocated
        emit(b"\x70\xb5", "push {r4, r5, r6, lr}", "proceed")   # the two it replaced
        emit(b"\x16\x46", "mov r6, r2")
        emit(_bl(at + pos, CREATE_RESUME, link=False), "b.w #resume")
        while (pos + 2) % 4:
            emit(b"\x00\xbf", "nop")
        emit(b"\x00\xbf", "nop")
        emit(struct.pack("<I", DRIVER_LOCK), ".word 0x1000186C", "slot")
        return out

    offsets, at_offset = {}, 0
    for entry in assemble(dict.fromkeys(("proceed", "slot"), 0)):
        if len(entry) == 3:
            offsets[entry[2]] = at_offset
        at_offset += len(entry[0])
    code = assemble(offsets)
    blob = b"".join(entry[0] for entry in code)
    if len(blob) % 4 or offsets["slot"] % 4 or len(blob) != offsets["slot"] + 4:
        raise AssertionError(f"guard is {len(blob)} bytes, labels {offsets}")
    return blob, [entry[1] for entry in code], offsets


def check_guard(blob: bytes, at: int, sources: list[str], offsets: dict) -> None:
    """The same read-back check as :func:`check_routine`, for the guard."""
    got = disassemble(blob[:-4], at)
    want = []
    for source, line in zip((s for s in sources if not s.startswith(".")), got):
        here = int(line.split("  ", 1)[0], 16)
        source = source.replace("#slot", f"#0x{(at + offsets['slot']) - ((here + 4) & ~3):x}")
        source = source.replace("#proceed", f"#0x{at + offsets['proceed']:x}")
        want.append(source.replace("#resume", f"#0x{CREATE_RESUME:x}"))
    for source, line in zip(want, got):
        if line.split("  ", 1)[1] != source:
            raise AssertionError(f"assembled {line!r}, meant {source!r}")
    if len(got) != len(want):
        raise AssertionError(f"disassembled {len(got)} instructions, wrote {len(want)}")
    if int.from_bytes(blob[-4:], "little") != DRIVER_LOCK:
        raise AssertionError(f"the guard's literal is {blob[-4:].hex(' ')}")


def apply(image: bytes, *, keep_storage_up: bool = False) -> tuple[bytes, int]:
    """Patch *image*; return the sealed result and the appended routine's address.

    Refuses an image whose sites do not hold the bytes expected, and one that
    already carries the patch.

    *keep_storage_up* adds the two hooks that stop the storage stack being torn
    down. **It crashed the watch** and is off by default: see the warning in the
    module docstring. The read hook on its own is what has run for hours.
    """
    info = image_tool.parse(image)
    if info["link"] != LINK_ADDRESS:
        raise ValueError(f"image links at 0x{info['link']:08X}, not 0x{LINK_ADDRESS:08X}")
    if info["declared_len"] != info["payload_len"]:
        raise ValueError("image length field does not match its payload -- seal it first")

    data = bytearray(image)

    def site(address: int, expected: bytes, what: str) -> int:
        """Where *address* is in the file, once its bytes are what they must be."""
        at = PAYLOAD_OFFSET + (address - LINK_ADDRESS)
        found = bytes(data[at:at + len(expected)])
        if found != expected:
            raise ValueError(
                f"0x{address:08X} ({what}) holds {found.hex(' ')}, expected "
                f"{expected.hex(' ')} -- this is not the image this patch was "
                "derived from, or it is patched already")
        return at

    hook = site(HOOK_ADDRESS, HOOK_EXPECTED, "the 0xEE handler's memcpy call")
    create = teardown = None
    if keep_storage_up:
        create = site(CREATE_ADDRESS, CREATE_EXPECTED, "the lock create")
        teardown = site(TEARDOWN_ADDRESS, TEARDOWN_EXPECTED, "the storage teardown")

    # Both appended blobs go after the payload's last byte, 4-byte aligned.
    end = LINK_ADDRESS + info["payload_len"]
    routine_at = (end + 3) & ~3
    data += b"\xff" * (routine_at - end)
    blob, sources, offsets = build_routine(routine_at)
    check_routine(blob, routine_at, sources, offsets)
    data += blob

    data[hook:hook + 4] = _bl(HOOK_ADDRESS, routine_at)
    if keep_storage_up:
        guard_at = routine_at + len(blob)
        guard, guard_sources, guard_offsets = build_create_guard(guard_at)
        check_guard(guard, guard_at, guard_sources, guard_offsets)
        data += guard
        data[create:create + 4] = _bl(CREATE_ADDRESS, guard_at, link=False)
        data[teardown:teardown + 4] = TEARDOWN_REPLACEMENT

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
    ap.add_argument("--keep-storage-up", action="store_true",
                    help="also stop the storage stack being torn down (two more hooks). "
                         "THIS CRASHED THE WATCH -- the teardown frees a second object "
                         "(0x10001868) whose create is not guarded, so every cycle leaks "
                         "it until the heap runs out. Do not use it until that is fixed")
    args = ap.parse_args(argv)

    image = Path(args.image).read_bytes()
    try:
        patched, routine_at = apply(image, keep_storage_up=args.keep_storage_up)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    before, after = image_tool.parse(image), image_tool.parse(patched)
    print(f"hook      0x{HOOK_ADDRESS:08X}  bl 0x{MEMCPY:08X} -> the read routine")
    if args.keep_storage_up:
        print(f"guard     0x{CREATE_ADDRESS:08X}  the lock create -> create-if-absent "
              f"(the storage slot only)")
        print(f"teardown  0x{TEARDOWN_ADDRESS:08X}  {TEARDOWN_EXPECTED.hex(' ')} -> "
              f"{TEARDOWN_REPLACEMENT.hex(' ')}  (movs r0, #0; bx lr)")
        print("          !! this pair crashed the watch (#70): the leak above")
    else:
        print("          the storage stack is left alone: it goes down when the watch "
              "idles, and a read then answers 0xFE")
    print(f"appended  {after['payload_len'] - before['payload_len']} bytes at "
          f"0x{routine_at:08X}")
    routine, routine_sources, _ = build_routine(routine_at)
    guard_at = routine_at + len(routine)
    guard, guard_sources, _ = build_create_guard(guard_at)
    shown = [("the read", routine, routine_sources, routine_at)]
    if args.keep_storage_up:
        shown.append(("the create guard", guard, guard_sources, guard_at))
    for name, blob, sources, start in shown:
        # The pool is however many ``.word``s the builder put at the end;
        # printing them as words rather than as the instructions a
        # disassembler makes of them, and counting them from the source, so
        # the two cannot disagree.
        words = [source for source in sources if source.startswith(".")]
        code = len(blob) - 4 * len(words)
        print(f"  -- {name}, {len(blob)} bytes at 0x{start:08X}")
        for line in disassemble(blob[:code], start):
            print(f"    {line}")
        for i, source in enumerate(words):
            print(f"    {start + code + i * 4:08X}  {source}")
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
