/* The transparent dispatcher hook -- the patchkit's own proof (#116).
 *
 * It is installed at the 0x6F dispatcher's fall-through (0x00039F88, a 4-byte
 * `mov.w r1, #0x100`), the point reached when no entry in the command table
 * matched (docs/firmware-patching.md). This version owns no command code: it
 * reproduces the instruction it replaced and hands control straight back to the
 * stock "unknown command" path, so every existing command still answers exactly
 * as it did and the watch is unchanged. What it proves is the machinery -- that a
 * C patch compiles, links into the cave, is spliced in, is branched to, and the
 * watch still boots and runs with the detour in place.
 *
 * #117 turns this into something that answers: it inserts, before the branch
 * back, a call to a C function that inspects the command byte and either builds a
 * reply with fw_reply (and returns to the caller) or falls through to here.
 *
 * The overwritten `mov.w r1, #0x100` sets the length argument for the buffer
 * clear at 0x00021122 that the fall-through path runs next, so it must be
 * reproduced. 0x00039F8C, where we rejoin, reloads r0 itself, so clobbering r0
 * here is safe; r4 and r5 (which the fall-through reply at 0x00039F92 uses) are
 * callee-saved and untouched.
 */
#include "fw.h"

__attribute__((naked, section(".text.entry")))
void patch_entry(void)
{
    __asm__ volatile(
        "movw r1, #0x100\n"                 /* the instruction we replaced */
        "ldr  r0, =fw_dispatch_fallthrough\n"
        "bx   r0\n"                          /* back to the stock path, in Thumb */
    );
}
