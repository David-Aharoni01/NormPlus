# Adding code to the watch firmware -- what is known

Research toward patching new code and features into the stock Apollo3 image, beyond the
one hand-assembled NAND read-out patch (`docs/firmware.md` section 11). Findings from
2026-10-11. Every address is in `NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin`.

## Where things stand

- The one patch that has been flashed, `normfw patch-nand`
  (`tools/normplus/firmware/nand_patch.py`), is Thumb-2 assembled by hand in Python and
  read back with capstone. It worked on the watch (#70), but it took about 600 lines to
  make 144 bytes.
- The image format is solved: `normfw seal` re-seals a patched image (`docs/firmware.md`
  section 7). The watch's first-stage bootloader has accepted a patched type-1 image and
  booted it (#70).

## Findings

**A C cross compiler from PyPI.** The `ziglang` package (0.16.0) bundles clang and ld.lld,
and needs no system toolchain. None is installed on this machine. Run with
`uv run --with ziglang python -m ziglang cc -target thumb-freestanding-eabihf
-mcpu=cortex_m4`, it compiled a C function. `ld.lld` then linked the function at
`0x000D6694` using a linker script. The linker resolved a call into the firmware's
`memcpy` (`0x00020EC4`) from a symbol defined in that script (`fw_memcpy = 0x00020EC5`,
with the Thumb bit set).

**About 31 KB of free internal flash after the image.** The application ends at
`0x000D6693`. The next page the firmware writes is `0x000DE000`, which holds the canned
quick replies (`docs/watch-emulator-internals.md`, "The watch keeps what it writes"). A
scan of the image for page-aligned literals found references to `0xDE000`,
`0xE0000`-`0xF8000`, `0xFA000`, `0xFC000` and `0xFF000`, and none to `0xD8000`-`0xDDFFF`.
That leaves `0x000D6694`-`0x000DDFFF`, 31,084 bytes, free.

One caveat. Every image flashed so far grew only inside the `0xD6000` page. The first
image that reaches `0xD8000` will also be the first time the bootloader writes a page it
has never written before.

**Where an unknown 0x6F command ends up.** The walker at `0x00039F1C` scans the 87-entry
`[code][handler]` table at `0x000CCD60` (`cmp r0, #0x57` at `0x00039F84`). When no entry
matches, it falls through to `0x00039F88`, which clears the receive buffer
(`mov.w r1, #0x100`, then `bl 0x00021122`). That is the point where a command the table
does not know could be offered to added code first, without changing the table.

**New code has no RAM of its own.** The free space is flash. The only working example of
writable space for added code is the one `patch-nand` uses: it borrows the OTA page
buffer at `0x10010C64` while no update is running. The firmware's allocator is probably
`0x000B9A5C` (called from the lock create at `0x00031BA8`), but that is unverified.

## The proposed method: C patches linked against the stock image

This is a design, not yet built. It keeps the shape `patch-nand` proved -- append a routine
after the image, redirect a handful of 4-byte sites to it, re-seal -- but moves the body
from hand-assembled bytes into C, so a feature is written once in a language that scales.
The verification discipline does not change, and neither do the OTA safety rules.

### The shape

```
patches/                     our source only -- public-safe, no vendor bytes
  fw.h                       firmware functions and structs we call, under our own names
  firmware.ld                the cave region, and every firmware address as an absolute symbol
  <feature>.c                one feature's handlers
  hooks.py                   the detour table: (site, bytes-expected, cave symbol)
```

A patch is one or more C functions plus a short list of detour sites. The build compiles the
C, links it into the cave, splices the linked blob after the image's last byte, overwrites
each detour site with a branch into the cave, and re-seals (`image_tool`). The result is the
vendor image with our bytes in it -- built on demand, **never committed** (`CLAUDE.md`, "This
repository is public").

### Building

`ziglang` is pinned in `pyproject.toml`, so `uv sync` gives every machine the same compiler:

```
zig cc -target thumb-freestanding-eabihf -mcpu=cortex_m4 \
       -Os -ffreestanding -fno-builtin -nostdlib -ffunction-sections -c <feature>.c
zig ld.lld -T firmware.ld <feature>.o -o patch.elf
zig objcopy -O binary patch.elf patch.bin
```

`firmware.ld` carries two things:

- a `MEMORY` region `cave : ORIGIN = 0x000D6694, LENGTH = 0x000DE000 - 0x000D6694` (31,084
  bytes), with `.text` and `.rodata` placed there, and
- the firmware's own entry points as absolute symbols, each a cited address with the Thumb
  bit set -- `fw_memcpy = 0x00020EC5` (the `memcpy` at `0x00020EC4`), `fw_reply = 0x000393B9`
  (the 0x6F reply builder at `0x000393B8`), and so on. The linker resolves a C call to one of
  these straight to a `bl` at the real address. (Confirmed working: a POC compiled and linked
  a call to `fw_memcpy` at the cave on 2026-10-11.)

`fw.h` declares each with its real signature so the C is type-checked. The reply builder's
convention, read off the walker's own call sites (`0x00039F56`), is `reply(2, 1, 0x81, ptr,
len)` with the length passed on the stack -- `fw.h` is where that is written down once.

### Hooking

Each hook replaces one 4-byte instruction at a known site with a wide `b.w`/`bl` into the
cave, exactly as `patch-nand` replaces the `bl memcpy` at `0x000377CE`. The cave is within
branch range of the whole image. Two rules, both from `patch-nand`:

- **The site's own bytes are asserted before patching** (`HOOK_EXPECTED` there). A site that
  does not hold what the patch was built against is refused -- a wrong image, or already
  patched.
- **Whatever the clobbered instruction did must be preserved** -- re-executed in the cave
  routine's prologue, or the site chosen so its effect is recoverable. A detour that drops a
  side effect is a silent corruption.

**The extension point for new 0x6F commands** is the dispatch fall-through. The walker at
`0x00039F1C` scans the 87-entry table at `0x000CCD60`; when nothing matches it reaches
`0x00039F88` (`mov.w r1, #0x100`, a 4-byte site) and clears the receive buffer. A detour
there offers the frame to our dispatcher first: the cave code inspects the command byte, and
either answers it (build the reply with `fw_reply`, then branch to the buffer-clear and
return) or, for a byte we do not own, re-runs the `mov.w` it replaced and falls through to the
stock path. This adds commands **without touching the vendor table or any vendor handler** --
every existing command stays byte-identical.

### State, because the cave is flash

New code has no writable storage of its own; the build enforces this by `DISCARD`-ing
`.data`/`.bss` and failing if a patch emits either. A patch that needs RAM has two options,
in order of preference:

1. **Borrow an idle firmware buffer**, the way `patch-nand` borrows the 2 KB OTA page buffer
   at `0x10010C64` while no update runs. Cite why the buffer is free for the window used.
2. **The firmware allocator** (probably `pvPortMalloc` at `0x000B9A5C`, unverified) -- only
   once that is confirmed, and with the same lifetime audit the OTA rules demand (`CLAUDE.md`,
   "A patch that changes when something is freed").

### Verifying -- unchanged

- **Read every patched and appended byte back with capstone** and compare to the source, as
  `check_routine` does in `patch-nand`. Hand-placed branches and borrowed buffers are exactly
  what is wrong invisibly until a watch does not boot.
- **Rehearse on the emulated watch** (`normtest`, a new test per patch), then the full
  `:app` path through `normphone start --watch`.
- **On hardware, read the running firmware back over 0xEE** and confirm it is the image the
  patch was built from before trusting anything, per the OTA safety rules. A type-1 flash is
  one-shot until SWD is confirmed (#14), and goes only with the owner's go-ahead.

### Cave budget and composition

Patches bump-allocate from `0x000D6694`; the build reports bytes used against the 31,084
available and refuses to overflow into `0x000DE000`. The first image that reaches `0x000D8000`
is also the first to make the bootloader write a page it has never written -- low risk (it
must erase each page it copies to), but new, and worth stating on the issue that crosses it.

### Where to start

The smallest real feature that exercises the whole method end to end is a **new read-only
0x6F command** through the fall-through hook -- no vendor site clobbered except the one
detour, no persistent state, a reply built with `fw_reply`. It proves the toolchain, the
linker script, the hook and the verification against the emulated watch before any feature
that writes state or touches a vendor handler. File it as its own Firmware issue when it
starts.
