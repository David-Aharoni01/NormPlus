"""Images the firmware could not draw, because their resource is not in the emulated NAND.

The watch keeps its images in its SPI NAND, each named by its address (``"0x288b517"``; see
"The resource path" in docs/watch-emulator-internals.md). The emulated NAND holds only the
resource image the companion app ships, ``Picture_P03B_NORM2_0.4.bin`` at ``0x0C780000`` --
401 KB. The physical watch carries far more: resources programmed at the factory, about
40 MB between ``0x026DA430`` and ``0x0C55619A`` (full-screen animation frames, screen
backgrounds and icons), which no file in the app contains. When the firmware asks for one
of those, its pages read back blank, LVGL's image decoder fails, and ``lv_draw_img`` draws
its placeholder, "No / data". That is what the real firmware does on a watch without those
resources; it is not a rendering bug (#64). This counts those draws, so the live window and
the boot report can say so.

``lv_draw_img`` is at ``0x000A1504`` (r2 = the image source). It draws "No\\ndata" (the
string at ``0x000A15C8``) on two branches: ``0x000A1554`` for a NULL source, and
``0x000A1524`` when ``lv_img_draw_core`` (``0x000A59B4``) fails -- the one that fires here.
"""

from __future__ import annotations

from unicorn import UC_HOOK_CODE
from unicorn.arm_const import UC_ARM_REG_R2

#: lv_draw_img's entry, and its first two instructions: push {r4-r6, lr}; sub sp, #0x10.
DRAW_IMG = 0x000A1504
DRAW_IMG_EXPECTED = bytes.fromhex("70b584b0")
#: The branch that logs "Image draw error" and draws "No\ndata" over the image's area.
DRAW_FAILED = 0x000A1524
#: Two failed images further apart than this are reported as separate ranges.
RANGE_GAP = 4 * 1024 * 1024


class MissingResources:
    """Counts the "No data" placeholders the firmware draws, and what they stood for."""

    def __init__(self, machine) -> None:
        self.uc = machine.uc
        self.placeholders = 0
        #: Resource addresses whose image could not be drawn.
        self.missing: set[int] = set()
        self._source = 0
        self.active = bytes(self.uc.mem_read(DRAW_IMG, 4)) == DRAW_IMG_EXPECTED
        if self.active:
            self.uc.hook_add(UC_HOOK_CODE, self._entered, begin=DRAW_IMG, end=DRAW_IMG)
            self.uc.hook_add(UC_HOOK_CODE, self._failed, begin=DRAW_FAILED, end=DRAW_FAILED)

    def _entered(self, uc, address, size, user_data) -> None:
        self._source = uc.reg_read(UC_ARM_REG_R2)

    def _failed(self, uc, address, size, user_data) -> None:
        self.placeholders += 1
        name = self._name(self._source)
        if name and name.startswith("0x"):
            try:
                self.missing.add(int(name, 16))
            except ValueError:
                pass

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

    def status(self) -> str:
        """For the live window: empty until an image has been missing."""
        if not self.missing:
            return ""
        return f"{len(self.missing)} images not in the NAND"

    def summary(self) -> str:
        if not self.active:
            return "  images: not monitored (a different firmware image)"
        if not self.placeholders:
            return "  images: every image the firmware drew was in the NAND"
        lines = [f"  images: {self.placeholders} drawn as \"No data\" -- {len(self.missing)} "
                 f"resources the emulated NAND does not have (the watch's factory "
                 f"resources; not in the app's resource image):"]
        lines += [f"    0x{lo:08X} - 0x{hi:08X}  {n} image{'s' if n > 1 else ''}"
                  for lo, hi, n in self.ranges()]
        return "\n".join(lines)
