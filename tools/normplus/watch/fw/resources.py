"""Images the firmware could not draw, because their resource is not in the emulated NAND.

The watch keeps its images in its SPI NAND, each named by its address (``"0x288b517"``; see
"The resource path" in docs/watch-emulator-internals.md). The emulated NAND holds the
resource image the companion app ships, ``Picture_P03B_NORM2_0.4.bin`` at ``0x0C780000``
(401 KB), and -- mounted by default -- the watch's factory resources read off the physical
watch (``NORM/_nand``, #65): about 40 MB in five ranges between ``0x026DA000`` and
``0x0C556800``. When the firmware asks for a resource outside what is mounted, its pages read
back blank and one of two things happens:

* **Missing**: the header is blank too, LVGL's image decoder fails, and ``lv_draw_img`` draws
  its placeholder, "No / data". That is what the real firmware does on a watch without those
  resources; it is not a rendering bug (#64).
* **Cut short**: the header is there and the pixels run past what is mounted. The decoder
  takes 0xFF for pixels and draws a white rectangle -- the weather screen's second digit
  (#82). The first read-out made each range from the *start* addresses a screen tour saw, so
  the last resource of a range ends past it.

This counts both, so the live window and the boot report can say so, and :meth:`wanted`
turns them into the NAND ranges a follow-up read-out needs (#83).

``lv_draw_img`` is at ``0x000A1504`` (r0 = the area it draws into, r2 = the image source). It
draws "No\\ndata" (the string at ``0x000A15C8``) on two branches: ``0x000A1554`` for a NULL
source, and ``0x000A1524`` when ``lv_img_draw_core`` (``0x000A59B4``) fails -- the one that
fires here.
"""

from __future__ import annotations

import struct
from typing import Optional

from unicorn import UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R0, UC_ARM_REG_R2

#: lv_draw_img's entry, and its first two instructions: push {r4-r6, lr}; sub sp, #0x10.
DRAW_IMG = 0x000A1504
DRAW_IMG_EXPECTED = bytes.fromhex("70b584b0")
#: The branch that logs "Image draw error" and draws "No\ndata" over the image's area.
DRAW_FAILED = 0x000A1524
#: Two failed images further apart than this are reported as separate ranges.
RANGE_GAP = 4 * 1024 * 1024
#: The NAND's page: what a read-out reads, so what a wanted range is rounded to.
PAGE = 2048
#: The panel is 360x360, so no image is bigger than a full screen at three bytes a pixel.
SCREEN = 360
LARGEST_IMAGE = 4 + SCREEN * SCREEN * 3
#: LVGL 5.3 colour formats (``lv_img_cf_t``) at LV_COLOR_DEPTH 16 -> bytes a pixel, for the
#: true-colour ones; the indexed (7-10) and alpha-only (11-14) ones are bits a pixel.
TRUE_COLOR_BYTES = {4: 2, 5: 3, 6: 2}
FORMAT_NAMES = {4: "TRUE_COLOR", 5: "TRUE_COLOR_ALPHA", 6: "TRUE_COLOR_CHROMA_KEYED",
                7: "INDEXED_1BIT", 8: "INDEXED_2BIT", 9: "INDEXED_4BIT", 10: "INDEXED_8BIT",
                11: "ALPHA_1BIT", 12: "ALPHA_2BIT", 13: "ALPHA_4BIT", 14: "ALPHA_8BIT"}


def image_extent(header: bytes) -> Optional[tuple[int, int, int, int]]:
    """(format, w, h, bytes including the header) from an LVGL 5.3 ``lv_img_header_t``
    -- ``cf:5, always_zero:3, reserved:2, w:11, h:11`` -- or None if it is not one."""
    if len(header) < 4:
        return None
    word = struct.unpack_from("<I", header)[0]
    cf, zero, w, h = word & 0x1F, (word >> 5) & 7, (word >> 10) & 0x7FF, (word >> 21) & 0x7FF
    if zero or not w or not h:
        return None
    if cf in TRUE_COLOR_BYTES:
        body = w * h * TRUE_COLOR_BYTES[cf]
    elif 7 <= cf <= 10:  # palette of 4-byte colours, then rows of bpp-bit indices
        bpp = 1 << (cf - 7)
        body = 4 * (1 << bpp) + (w * bpp + 7) // 8 * h
    elif 11 <= cf <= 14:
        bpp = 1 << (cf - 11)
        body = (w * bpp + 7) // 8 * h
    else:
        return None
    return cf, w, h, 4 + body


def _merge(ranges) -> list[tuple[int, int]]:
    out: list[list[int]] = []
    for lo, hi in sorted(ranges):
        if out and lo <= out[-1][1]:
            out[-1][1] = max(out[-1][1], hi)
        else:
            out.append([lo, hi])
    return [tuple(r) for r in out]


class MissingResources:
    """Counts the images drawn without their data, and what they stood for.

    Given the emulated *nand*, it also checks each image's header against what the NAND
    holds, which is the only way to see an image that was cut short: its decode succeeds.
    """

    def __init__(self, machine, nand=None) -> None:
        self.uc = machine.uc
        self.nand = nand
        self.placeholders = 0
        #: Resource addresses whose image could not be drawn.
        self.missing: set[int] = set()
        #: address -> (w, h) of the area it was to be drawn into, for :meth:`wanted`.
        self.areas: dict[int, tuple[int, int]] = {}
        #: Every resource address drawn, there or not: the next one bounds an image
        #: whose own header could not be read.
        self.seen: set[int] = set()
        #: address -> (format, w, h, bytes, bytes the NAND holds) for images whose
        #: header is in the NAND and whose pixels run past what it holds.
        self.truncated: dict[int, tuple[int, int, int, int, int]] = {}
        self._checked: set[int] = set()
        self._source = 0
        self._area = (0, 0)
        self.active = bytes(self.uc.mem_read(DRAW_IMG, 4)) == DRAW_IMG_EXPECTED
        if self.active:
            self.uc.hook_add(UC_HOOK_CODE, self._entered, begin=DRAW_IMG, end=DRAW_IMG)
            self.uc.hook_add(UC_HOOK_CODE, self._failed, begin=DRAW_FAILED, end=DRAW_FAILED)

    def _entered(self, uc, address, size, user_data) -> None:
        self._source = uc.reg_read(UC_ARM_REG_R2)
        try:
            x1, y1, x2, y2 = struct.unpack("<4h", bytes(uc.mem_read(uc.reg_read(UC_ARM_REG_R0), 8)))
            self._area = (x2 - x1 + 1, y2 - y1 + 1)
        except Exception:
            self._area = (0, 0)
        resource = self._address(self._source)
        if resource is not None:
            self.seen.add(resource)
        if self.nand is None or resource is None or resource in self._checked:
            return
        self._checked.add(resource)
        extent = image_extent(self.nand.peek(resource, 4))
        if extent is None or self.nand.holds(resource, extent[3]):
            return  # missing altogether (the decode fails: _failed), or all there
        held = resource
        while self.nand.holds(held - held % PAGE, 1):
            held += PAGE - held % PAGE
        self.truncated[resource] = (*extent, held - resource)

    def _failed(self, uc, address, size, user_data) -> None:
        self.placeholders += 1
        resource = self._address(self._source)
        if resource is not None:
            self.missing.add(resource)
            self.areas[resource] = self._area

    def _address(self, source: int) -> Optional[int]:
        name = self._name(source)
        if name and name.startswith("0x"):
            try:
                return int(name, 16)
            except ValueError:
                return None
        return None

    def _name(self, address: int) -> str | None:
        if not address:
            return None
        try:
            raw = bytes(self.uc.mem_read(address, 16)).split(b"\0", 1)[0]
        except Exception:
            return None
        return raw.decode("latin-1") if raw and all(32 <= b < 127 for b in raw) else None

    def ranges(self) -> list[tuple[int, int, int]]:
        """The missing resources as (lowest, highest, how many), clustered."""
        out: list[list[int]] = []
        for a in sorted(self.missing):
            if out and a - out[-1][1] < RANGE_GAP:
                out[-1][1] = a
                out[-1][2] += 1
            else:
                out.append([a, a, 1])
        return [tuple(r) for r in out]

    def wanted(self) -> list[tuple[int, int]]:
        """Page-aligned NAND ranges (start, end) that would complete every image seen.

        A cut-short image's extent is exact, from its header. A missing one's is not
        known until its header is read, so it is an upper bound: the area it was drawn
        into at three bytes a pixel, the widest format -- or a full screen, because an
        image that fails to decode often reports no usable area (LVGL hands
        ``lv_draw_img`` 2047x2047) -- and never past the next resource drawn.
        """
        spans = []
        for address, (_cf, _w, _h, total, held) in self.truncated.items():
            spans.append((address + held, address + total))
        later = sorted(self.seen)
        for address in self.missing:
            w, h = self.areas.get(address, (0, 0))
            size = 4 + w * h * 3 if 0 < w <= SCREEN and 0 < h <= SCREEN else LARGEST_IMAGE
            following = [a for a in later if a > address]
            end = min(address + size, following[0]) if following else address + size
            spans.append((address, end))
        return _merge((lo - lo % PAGE, -(-hi // PAGE) * PAGE) for lo, hi in spans)

    def status(self) -> str:
        """For the live window: empty until an image has been missing or cut short."""
        parts = []
        if self.missing:
            parts.append(f"{len(self.missing)} images not in the NAND")
        if self.truncated:
            parts.append(f"{len(self.truncated)} cut short")
        return ", ".join(parts)

    def summary(self) -> str:
        if not self.active:
            return "  images: not monitored (a different firmware image)"
        if not self.placeholders and not self.truncated:
            return "  images: every image the firmware drew was in the NAND"
        lines = []
        if self.placeholders:
            lines.append(f"  images: {self.placeholders} drawn as \"No data\" -- "
                         f"{len(self.missing)} resources the emulated NAND does not have "
                         f"(factory resources not mounted, or not read off the watch):")
            lines += [f"    0x{lo:08X} - 0x{hi:08X}  {n} image{'s' if n > 1 else ''}"
                      for lo, hi, n in self.ranges()]
        if self.truncated:
            lines.append(f"  images: {len(self.truncated)} cut short -- the header is in the "
                         f"NAND, the pixels run past it and draw as white:")
            for address, (cf, w, h, total, held) in sorted(self.truncated.items()):
                lines.append(f"    0x{address:08X}  {w}x{h} {FORMAT_NAMES.get(cf, cf)}: "
                             f"{held:,} of {total:,} bytes")
        wanted = self.wanted()
        lines.append(f"  to complete them, read off the watch ({sum(hi - lo for lo, hi in wanted):,} "
                     f"bytes at most): " + " ".join(f"{lo:08X}-{hi:08X}" for lo, hi in wanted))
        return "\n".join(lines)
