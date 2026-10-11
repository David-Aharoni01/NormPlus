/* Linker script for a firmware patch: place our code in the code cave after the
 * image, and resolve the firmware functions it calls to their real addresses.
 *
 * The cave's start is the image's own end (link address + payload length, rounded
 * up to 4), which the build passes in as __cave_start:
 *
 *     zig cc ... -Wl,-T,patches/firmware.ld -Wl,--defsym,__cave_start=0x000D6694
 *
 * Everything else is fixed, and cited in docs/firmware.md / docs/firmware-patching.md.
 * Addresses carry the Thumb bit (bit 0) where they name code, because a bx/blx to
 * them must stay in Thumb state.
 */

/* Firmware entry points (see fw.h for the C declarations). */
fw_memcpy = 0x00020EC5;   /* memcpy(dst, src, n)                         0x00020EC4 */
fw_reply  = 0x000393B9;   /* the 0x6F reply builder                      0x000393B8 */

/* Where a transparent hook at the dispatcher's fall-through (0x00039F88) rejoins
 * the stock "unknown command" path. 0x00039F8C is `ldr r0, [pc, #...]`, which is
 * pc-relative and so must run at its own address -- the hook cannot absorb it. */
fw_dispatch_fallthrough = 0x00039F8D;   /* 0x00039F8C | Thumb */

/* The first page above the image that the firmware itself writes (the canned
 * quick replies). The cave is everything between the image's end and here. */
__cave_limit = 0x000DE000;

SECTIONS
{
    . = __cave_start;

    .text : {
        KEEP(*(.text.entry))      /* the hook entry points, never collected */
        *(.text*)
        *(.rodata*)
    }
    __cave_used_end = .;

    /* The cave is flash: a patch that defines writable data has nowhere to put it
     * at run time, so the build must fail rather than silently lose it. */
    .data : { *(.data*) }
    .bss  : { *(.bss*) *(COMMON) }

    ASSERT(SIZEOF(.data) == 0,
        "a patch must not define writable data (.data) -- the cave is flash; borrow an idle firmware buffer instead")
    ASSERT(SIZEOF(.bss) == 0,
        "a patch must not define .bss -- the cave is flash; borrow an idle firmware buffer instead")
    ASSERT(__cave_used_end <= __cave_limit,
        "patch overflows the code cave (ends past 0x000DE000)")

    /DISCARD/ : {
        *(.ARM.exidx*) *(.ARM.extab*)
        *(.comment) *(.note*) *(.eh_frame*)
    }
}
