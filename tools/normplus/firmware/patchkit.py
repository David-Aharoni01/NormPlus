#!/usr/bin/env python3
"""Build a firmware patch written in C and splice it into the stock image.

This is the general form of what ``nand_patch`` does by hand (#68): instead of
hand-assembling Thumb-2, a feature is written in C under ``patches/``, compiled
for the Apollo3's Cortex-M4, and linked into the free flash after the image (the
"code cave", ~31 KB at 0x000D6694). The build then rewrites a short list of
4-byte sites to branch into the cave, and re-seals the image's length and CRC
(``image_tool``) so it validates. See docs/firmware-patching.md for the design.

The compiler is ``ziglang`` from PyPI (clang + ld.lld), pinned in pyproject.toml,
so ``uv sync`` gives every machine the same toolchain and nothing has to be
installed. A C call to a firmware function (``fw_memcpy``, ``fw_reply``) resolves
straight to the real address, because ``patches/firmware.ld`` defines those as
absolute symbols.

A patch is a C source plus a table of hooks. Each hook names a site in the image,
the bytes that must be there (so a wrong or already-patched image is refused), and
the cave symbol to branch to. Nothing in the vendor command table or any vendor
handler is touched; new 0x6F commands arrive through the dispatcher's fall-through
(0x00039F88), which leaves every existing command byte-identical.

    normfw patch NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin -o patched.bin
    normfw patch --list

The result is derived from the vendor's image -- like the image itself it must
never be committed to this repository.
"""

from __future__ import annotations

import argparse
import struct
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

from .. import developer_options, paths
from . import image_tool

#: ``patches/`` at the repository root: our C, the linker script, fw.h.
PATCH_DIR = paths.REPO / "patches"

#: The payload is linked here; the cave begins at its end (see image_tool).
LINK_ADDRESS = 0x00020000
#: The first page above the image that the firmware itself writes -- the cave's
#: hard ceiling. Mirrors ``__cave_limit`` in patches/firmware.ld.
CAVE_LIMIT = 0x000DE000

#: The compile/link flags. Freestanding Thumb-2 for the Cortex-M4F, no PIC (the
#: code runs at the fixed cave address), no libc, functions in their own sections.
TARGET = "thumb-freestanding-eabihf"
CFLAGS = [
    "-target", TARGET, "-mcpu=cortex_m4",
    "-Os", "-ffreestanding", "-fno-builtin", "-fno-pic", "-fno-pie",
    "-ffunction-sections", "-fdata-sections",
    f"-I{PATCH_DIR}",
]
#: The link step takes only what it needs. The optimisation flags above make zig
#: strip the symbol table on link, and the build reads the linked symbols to place
#: the detours, so they are deliberately left off here.
LDFLAGS = ["-target", TARGET, "-mcpu=cortex_m4", "-nostdlib"]


@dataclass(frozen=True)
class Hook:
    """One 4-byte site redirected into the cave.

    *expected* is what the site must hold in the stock image; the build refuses to
    patch anything else. *symbol* is the cave function to branch to, and *call* is
    ``True`` for a ``bl`` (the handler returns) or ``False`` for a ``b.w`` (a tail
    jump that never comes back here).
    """
    site: int
    expected: bytes
    symbol: str
    call: bool = False
    note: str = ""


@dataclass(frozen=True)
class Patch:
    name: str
    doc: str
    sources: tuple[str, ...]
    hooks: tuple[Hook, ...] = field(default_factory=tuple)


#: Every patch the kit can build. ``passthrough`` is the kit's own proof (#116):
#: a transparent detour at the dispatcher fall-through that changes no behaviour.
PATCHES: dict[str, Patch] = {
    "passthrough": Patch(
        name="passthrough",
        doc="a transparent hook at the dispatcher fall-through -- proves the "
            "machinery, changes no behaviour (#116)",
        sources=("passthrough.c",),
        hooks=(
            Hook(site=0x00039F88, expected=bytes.fromhex("4ff48071"),
                 symbol="patch_entry", call=False,
                 note="the dispatcher's `mov.w r1, #0x100` fall-through"),
        ),
    ),
}


def encode_branch(at: int, target: int, *, call: bool) -> bytes:
    """A wide Thumb-2 ``bl`` (call) or ``b.w`` (jump) at *at* to *target*.

    Same encoding as ``nand_patch._bl``; kept here so the kit stands alone. The
    target carries no Thumb bit (both ends are Thumb); the caller passes a plain
    even address.
    """
    offset = target - (at + 4)
    if not -(1 << 24) <= offset < (1 << 24) or offset & 1:
        raise ValueError(f"0x{target:08X} is out of branch range of 0x{at:08X}")
    imm = offset >> 1
    s = (imm >> 23) & 1
    i1, i2 = (imm >> 22) & 1, (imm >> 21) & 1
    hw1 = 0xF000 | (s << 10) | ((imm >> 11) & 0x3FF)
    hw2 = (0xD000 if call else 0x9000) | ((1 ^ i1 ^ s) << 13) \
        | ((1 ^ i2 ^ s) << 11) | (imm & 0x7FF)
    return struct.pack("<HH", hw1, hw2)


def zig(*args: str) -> None:
    """Run the bundled ``zig`` (clang/ld.lld) through the active interpreter."""
    proc = subprocess.run([sys.executable, "-m", "ziglang", *args],
                          capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(
            "zig " + " ".join(args) + "\n" + proc.stdout + proc.stderr)


def compile_and_link(patch: Patch, cave_start: int, work: Path) -> tuple[bytes, dict[str, int]]:
    """Compile *patch* and link it at *cave_start*; return the blob and its symbols.

    The linker script's ``__cave_start`` is set here, so the code is linked at the
    address it is actually spliced to, and its own ASSERTs (no ``.data``/``.bss``,
    fits the cave) fail the build before anything is written to an image.
    """
    from elftools.elf.elffile import ELFFile

    objects = []
    for source in patch.sources:
        obj = work / (Path(source).stem + ".o")
        zig("cc", *CFLAGS, "-c", str(PATCH_DIR / source), "-o", str(obj))
        objects.append(str(obj))

    # zig's linker driver rejects --defsym, so the cave start is baked into a
    # generated copy of the committed script (a leading definition is in scope for
    # the `. = __cave_start;` that follows).
    script = work / "link.ld"
    script.write_text(f"__cave_start = 0x{cave_start:08X};\n"
                      + (PATCH_DIR / "firmware.ld").read_text())

    elf = work / f"{patch.name}.elf"
    zig("cc", *LDFLAGS, "-Wl,-T," + str(script), *objects, "-o", str(elf))

    blob = work / f"{patch.name}.bin"
    zig("objcopy", "-O", "binary", str(elf), str(blob))

    symbols: dict[str, int] = {}
    with open(elf, "rb") as fh:
        symtab = ELFFile(fh).get_section_by_name(".symtab")
        for sym in symtab.iter_symbols():
            if sym.name and sym["st_value"]:
                symbols[sym.name] = sym["st_value"]
    return blob.read_bytes(), symbols


def apply(image: bytes, patch: Patch, *, work: Path | None = None) -> dict:
    """Patch *image* with *patch*; return the sealed image and what the build did.

    Refuses an image whose link address is not the expected one, whose length
    field does not match its payload (seal it first), or whose hook sites do not
    hold the bytes the patch was built against.
    """
    info = image_tool.parse(image)
    if info["link"] != LINK_ADDRESS:
        raise ValueError(f"image links at 0x{info['link']:08X}, not 0x{LINK_ADDRESS:08X}")
    if info["declared_len"] != info["payload_len"]:
        raise ValueError("image length field does not match its payload -- seal it first")

    end = LINK_ADDRESS + info["payload_len"]
    cave_start = (end + 3) & ~3

    work = work or Path(tempfile.mkdtemp(prefix=f"patch-{patch.name}-"))
    blob, symbols = compile_and_link(patch, cave_start, work)

    if (cave_start + len(blob)) > CAVE_LIMIT:
        raise ValueError(
            f"patch is {len(blob)} bytes at 0x{cave_start:08X}; it would end at "
            f"0x{cave_start + len(blob):08X}, past the cave ceiling 0x{CAVE_LIMIT:08X}")

    data = bytearray(image)
    data += b"\xff" * (cave_start - end)       # pad the gap to the cave, then the code
    data += blob

    applied = []
    for hook in patch.hooks:
        if hook.symbol not in symbols:
            raise ValueError(f"patch {patch.name}: no symbol {hook.symbol!r} in the build")
        off = image_tool.HEADER_LEN + (hook.site - LINK_ADDRESS)
        found = bytes(data[off:off + len(hook.expected)])
        if found != hook.expected:
            raise ValueError(
                f"0x{hook.site:08X} ({hook.note or hook.symbol}) holds {found.hex(' ')}, "
                f"expected {hook.expected.hex(' ')} -- not the image this patch was built "
                "against, or it is patched already")
        # Thumb function symbols carry bit 0; the branch encoding takes the even
        # address (the core switches to Thumb on a bl/b.w regardless).
        branch = encode_branch(hook.site, symbols[hook.symbol] & ~1, call=hook.call)
        data[off:off + len(branch)] = branch
        applied.append((hook, symbols[hook.symbol], branch))

    payload = bytes(data[image_tool.HEADER_LEN:])
    struct.pack_into("<I", data, image_tool.OFF_LEN, len(payload))
    struct.pack_into("<I", data, image_tool.OFF_CRC, image_tool.bootloader_crc32(payload))
    return {
        "image": bytes(data),
        "cave_start": cave_start,
        "blob": blob,
        "symbols": symbols,
        "applied": applied,
        "cave_free": CAVE_LIMIT - (cave_start + len(blob)),
    }


def disassemble(blob: bytes, at: int) -> list[str]:
    import capstone

    md = capstone.Cs(capstone.CS_ARCH_ARM, capstone.CS_MODE_THUMB)
    return [f"{i.address:08X}  {i.mnemonic} {i.op_str}".rstrip()
            for i in md.disasm(blob, at)]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        prog="normfw patch", description=__doc__.split("\n\n")[0],
        epilog="The patched image is vendor-derived: never commit it.")
    ap.add_argument("image", nargs="?", help="the stock Apollo image (NORM/assets/Apollo3_*.bin)")
    ap.add_argument("-o", "--output", help="where to write the patched image")
    ap.add_argument("--patch", default="passthrough",
                    help="which patch to build (default: passthrough; --list shows all)")
    ap.add_argument("--list", action="store_true", help="list the patches and stop")
    dev = developer_options(ap)
    dev.add_argument("--print", action="store_true", dest="print_only",
                     help="disassemble the appended code and stop, writing nothing")
    args = ap.parse_args(argv)

    if args.list:
        for name, patch in PATCHES.items():
            print(f"{name:14s}  {patch.doc}")
        return 0
    if args.patch not in PATCHES:
        print(f"error: no patch {args.patch!r} (try --list)", file=sys.stderr)
        return 2
    if not args.image:
        print("error: give the image path (or --list)", file=sys.stderr)
        return 2

    patch = PATCHES[args.patch]
    image = Path(args.image).read_bytes()
    try:
        result = apply(image, patch)
    except (ValueError, RuntimeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    before, after = image_tool.parse(image), image_tool.parse(result["image"])
    print(f"patch     {patch.name}: {patch.doc}")
    print(f"appended  {len(result['blob'])} bytes at 0x{result['cave_start']:08X}  "
          f"({result['cave_free']} bytes of cave left)")
    for hook, target, branch in result["applied"]:
        print(f"hook      0x{hook.site:08X}  {hook.expected.hex(' ')} -> "
              f"{branch.hex(' ')}  ({'bl' if hook.call else 'b.w'} 0x{target & ~1:08X} "
              f"{hook.symbol})")
    print(f"length    {before['declared_len']} -> {after['declared_len']}")
    print(f"image CRC 0x{before['declared_crc']:08X} -> 0x{after['declared_crc']:08X}")
    print(f"transport CRC-16 is now 0x{after['transport_crc16']:04X}")
    for line in disassemble(result["blob"], result["cave_start"]):
        print(f"    {line}")

    if args.print_only:
        return 0
    if not args.output:
        print("error: pass -o OUTPUT (or --print to only disassemble)", file=sys.stderr)
        return 2
    Path(args.output).write_bytes(result["image"])
    print(f"wrote {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
