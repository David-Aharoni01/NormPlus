"""A live window showing the emulated watch's screen, with the mouse as a finger.

The emulator runs in a worker thread and the window on the main one, because Tk
insists on owning the main thread. Nothing is shared but the framebuffer (read
only, and a torn read is at worst one stale frame) and a revision counter, so the
CPU is never blocked by the UI.

Input goes the other way and must *not* be poked straight into the peripherals:
mouse events are handed to :meth:`Apollo3Machine.post`, which runs them on the
emulator thread between basic blocks, so the touch interrupt is delivered by the
same path the firmware would really see.

Tk is used rather than a graphics library because it is in the standard library
and this has to work from a bare Python with only Unicorn installed. The
framebuffer is handed over as a PPM, which ``PhotoImage`` accepts directly.
"""

from __future__ import annotations

import array
import sys
import threading
import time
import tkinter as tk
from typing import Optional

#: RGB565 -> 3 packed bytes, built once. 65536 entries costs ~40 ms and turns
#: the per-frame conversion into a list lookup, which is the difference between
#: a viewer that keeps up and one that does not.
_RGB565: Optional[list] = None


def _rgb565_table() -> list:
    global _RGB565
    if _RGB565 is None:
        table = []
        for value in range(65536):
            r = (value >> 11) & 0x1F
            g = (value >> 5) & 0x3F
            b = value & 0x1F
            table.append(bytes(((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2))))
        _RGB565 = table
    return _RGB565


def framebuffer_to_ppm(framebuffer, width: int, height: int, *, swap: bool = False) -> bytes:
    """Convert an RGB565 framebuffer to a binary PPM.

    The panel is fed big-endian RGB565 (the high byte first on the wire), so the
    pixels are byte-swapped on a little-endian host before lookup. ``swap``
    inverts that, for a firmware that hands the panel little-endian pixels.
    """
    pixels = array.array("H")
    pixels.frombytes(bytes(framebuffer))
    big_endian_host = sys.byteorder == "big"
    if big_endian_host == bool(swap):
        pixels.byteswap()
    table = _rgb565_table()
    body = b"".join(map(table.__getitem__, pixels))
    return b"P6\n%d %d\n255\n" % (width, height) + body


class WatchWindow:
    """The watch's screen in a window, with click/drag driving the touch panel."""

    #: How often the window looks for a new frame. The watch itself only
    #: repaints a few times a second under emulation, so this is generous.
    REFRESH_MS = 40

    def __init__(
        self,
        machine,
        display,
        *,
        touch=None,
        scale: int = 1,
        title: str = "Norm 2",
        buttons: Optional[dict] = None,
        log=print,
        extra_status=None,
    ) -> None:
        self.machine = machine
        self.display = display
        self.touch = touch
        self.scale = max(1, int(scale))
        self.log = log
        #: Optional callable whose text is appended to the status line (e.g. how many
        #: images the firmware could not draw because the NAND does not have them).
        self.extra_status = extra_status
        #: ``{key: gpio_pin}`` — pressing the key presses that button.
        self.buttons = buttons or {}

        self.root = tk.Tk()
        self.root.title(title)
        self.root.resizable(False, False)
        self.root.configure(background="#101014")

        self.canvas = tk.Canvas(
            self.root,
            width=display.width * self.scale,
            height=display.height * self.scale,
            highlightthickness=0,
            background="#000000",
        )
        self.canvas.pack()
        self.image = tk.PhotoImage(width=display.width, height=display.height)
        self.item = self.canvas.create_image(0, 0, anchor="nw", image=self.image)

        self.status = tk.Label(
            self.root,
            text="starting…",
            anchor="w",
            background="#101014",
            foreground="#8a8a99",
            font=("Consolas", 9),
        )
        self.status.pack(fill="x", padx=6, pady=(2, 4))

        self._revision = -1
        self._frames_shown = 0
        self._started = time.time()
        self._closing = False
        #: Set once the emulator thread has ended. The refresh loop keeps
        #: running after that (so the last frame stays on screen), so it has to
        #: know not to go on claiming the CPU is running.
        self._stopped = False

        self.canvas.bind("<Button-1>", self._on_press)
        self.canvas.bind("<B1-Motion>", self._on_drag)
        self.canvas.bind("<ButtonRelease-1>", self._on_release)
        self.canvas.bind("<Leave>", self._on_release)
        self.root.bind("<KeyPress>", self._on_key_press)
        self.root.bind("<KeyRelease>", self._on_key_release)
        self.root.protocol("WM_DELETE_WINDOW", self.close)

    # -- input ---------------------------------------------------------------

    def _screen_xy(self, event) -> tuple[int, int]:
        x = min(max(int(event.x) // self.scale, 0), self.display.width - 1)
        y = min(max(int(event.y) // self.scale, 0), self.display.height - 1)
        return x, y

    def _on_press(self, event) -> None:
        if self.touch is None:
            return
        x, y = self._screen_xy(event)
        self.machine.post(lambda: self.touch.press(x, y))

    def _on_drag(self, event) -> None:
        if self.touch is None:
            return
        x, y = self._screen_xy(event)
        self.machine.post(lambda: self.touch.move(x, y))

    def _on_release(self, _event) -> None:
        if self.touch is None:
            return
        self.machine.post(self.touch.release)

    def _on_key_press(self, event) -> None:
        pin = self.buttons.get(event.keysym.lower())
        if pin is not None:
            self.machine.post(lambda: self._button(pin, True), f"button pin {pin} down")

    def _on_key_release(self, event) -> None:
        pin = self.buttons.get(event.keysym.lower())
        if pin is not None:
            self.machine.post(lambda: self._button(pin, False), f"button pin {pin} up")

    def _button(self, pin: int, pressed: bool) -> None:
        # Which level means "pressed" is the pin's own business — see
        # Gpio.resting_level. Most of these lines are active-low.
        resting = self.machine.gpio.resting_level(pin)
        self.machine.gpio.set_input(pin, 1 - resting if pressed else resting)
        self.machine.gpio.raise_interrupt(pin)

    # -- output --------------------------------------------------------------

    def _refresh(self) -> None:
        if self._closing:
            return
        display = self.display
        if display.revision != self._revision:
            self._revision = display.revision
            try:
                ppm = framebuffer_to_ppm(display.framebuffer, display.width, display.height)
                new = tk.PhotoImage(data=ppm)
                if self.scale > 1:
                    new = new.zoom(self.scale, self.scale)
                self.canvas.itemconfigure(self.item, image=new)
                self.image = new  # Tk only holds a weak reference
                self._frames_shown += 1
            except tk.TclError:
                return
        self._update_status()
        self.root.after(self.REFRESH_MS, self._refresh)

    def _update_status(self) -> None:
        elapsed = max(time.time() - self._started, 1e-6)
        instructions = getattr(self.machine, "_instructions", 0)
        if self._stopped:
            state = "STOPPED"
            rate = ""
        else:
            state = "running" if not self.machine.stop_requested else "stopping"
            rate = f"{instructions / elapsed / 1e6:.2f}M/s  "
        self.status.configure(
            text=(
                f"{state}  {instructions / 1e6:,.1f}M instr  {rate}"
                f"{self.display.frames} panel frames  {self._frames_shown} shown"
                + (f"  {extra}" if (extra := self.extra_status() if self.extra_status else "") else "")
            ),
            foreground="#c86464" if self._stopped else "#8a8a99",
        )

    # -- lifecycle -----------------------------------------------------------

    def close(self) -> None:
        self._closing = True
        self.machine.stop_requested = True
        try:
            self.root.destroy()
        except tk.TclError:
            pass

    def run(self, worker: threading.Thread) -> None:
        """Show the window until it is closed or *worker* finishes."""

        def watch_worker() -> None:
            if self._closing:
                return
            if not worker.is_alive():
                self._stopped = True
                self._update_status()
                return
            self.root.after(250, watch_worker)

        self.root.after(self.REFRESH_MS, self._refresh)
        self.root.after(250, watch_worker)
        try:
            self.root.mainloop()
        finally:
            self.machine.stop_requested = True
