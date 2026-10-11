/* Firmware entry points a patch may call, under our own names.
 *
 * Each symbol's address is defined in firmware.ld and cited there; the
 * declarations here only give the C side a type to check against. The Thumb bit
 * (bit 0 set) lives in the linker definition, not here -- a function pointer in C
 * is just the address. See docs/firmware-patching.md.
 *
 * The addresses are for NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin. A patch built
 * against a different image is refused at its hook sites (the expected bytes do
 * not match), so a stale address here cannot silently mispatch.
 */
#ifndef NORMPLUS_FW_H
#define NORMPLUS_FW_H

#include <stddef.h>
#include <stdint.h>

/* memcpy(dst, src, n) -- 0x00020EC4. The 0xEE handler's own copy. */
void *fw_memcpy(void *dst, const void *src, size_t n);

/* The 0x6F reply builder -- 0x000393B8. Called by the dispatcher at 0x00039F56
 * as reply(2, 1, 0x81, payload, len), with the length passed on the stack. A
 * handler that answers a command builds its reply through this. */
int fw_reply(int a, int b, int cmd, const uint8_t *payload, int len);

#endif /* NORMPLUS_FW_H */
