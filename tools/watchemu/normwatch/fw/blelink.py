"""The emulated watch's BLE stack, on a bumble virtual link.

:class:`NationzController` gets the firmware's Cordio host as far as speaking
HCI, but it is not a radio: it answers so the stack can start and no further.
This puts a real controller behind it -- a ``bumble.controller.Controller`` on
a ``bumble.link.LocalLink`` -- so the watch can actually advertise, be
connected to, and carry data, with no hardware anywhere.

Three things about the seam are worth knowing before changing it.

**The download phase never reaches bumble.** The NZ8801 bootloader exchange
(opcodes 0xF1/0xF2/0xF3, see :class:`NationzController`) is not HCI and bumble
would reject every packet of it. It is answered here, exactly as before, and
only once the controller has "booted" does anything get forwarded.

**Vendor commands are answered locally too.** Bumble has no handler for OGF
0x3F and, worse, replies to one by logging an error and emitting *nothing* --
which the firmware sees as a dead controller. The first thing across the link
after the download is the vendor command 0xFD04, so every vendor opcode gets a
benign Command Complete here. That is the same split
``tools/emulator/norm_emu_bridge.py`` already makes for Google's 0xFD53 on the
Android side; the vendor space belongs to whoever is emulating the part, not to
the generic stack.

**Bumble runs on its own thread.** The emulator is synchronous and bumble is
asyncio, and ``Controller.send_hci_packet`` defers through ``call_soon``, so a
reply is never ready by the time ``on_hci_packet`` returns. A command therefore
hands off to the loop and waits briefly for the answer, which makes the
exchange look synchronous to the emulator and removes the race between the
watch's own timeouts and the loop getting a turn. Events the controller raises
on its own -- advertising reports, connection complete -- are queued whenever
they arrive.

One consequence of that last point: while a link is up the machine has to be
paced against the wall clock. ``--idle-skip`` and the deadline quantum both
fast-forward *watch* time past idle, and a controller answering in real time
cannot keep up with a watch whose clock is running ahead of it.
"""

from __future__ import annotations

import asyncio
import threading
from typing import Optional

from .devices import NationzController

#: OGF 0x3F. Every opcode in this space is the part manufacturer's, so it is
#: answered here rather than forwarded.
VENDOR_OGF = 0x3F

#: How long a command waits for its answer before the emulator carries on. The
#: firmware's own patience is far longer -- the receive path retries 5000 times
#: at 16us of watch time -- so this only has to beat the loop's scheduling.
COMMAND_TIMEOUT_S = 0.5


def _opcode_ogf(opcode: int) -> int:
    return (opcode >> 10) & 0x3F


class BumbleController(NationzController):
    """A bumble ``Controller`` behind the NZ8801 bootloader.

    ``link`` lets several of these share one ``LocalLink``, which is how the
    watch and a phone end up able to see each other. Left out, one is made, and
    the watch is alone on it.
    """

    def __init__(self, address: str = "4C:59:80:12:44:F1", *, link=None,
                 name: str = "watch", log=print) -> None:
        super().__init__(log=log)
        self.address = address
        self.name = name
        #: Opcodes answered here rather than by bumble, in order.
        self.vendor_commands: list = []
        self._lock = threading.Lock()
        self._answered = threading.Event()
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._controller = None
        self._link = link
        self._started = threading.Event()
        self._failure: Optional[BaseException] = None
        self._thread = threading.Thread(
            target=self._run, name="bumble-controller", daemon=True)
        self._thread.start()
        self._started.wait(timeout=10)
        if self._failure is not None:
            raise self._failure

    # -- the bumble side, all of it on its own thread -------------------------

    def _run(self) -> None:
        try:
            asyncio.run(self._serve())
        except BaseException as exc:            # surfaced on the caller's thread
            self._failure = exc
            self._started.set()

    async def _serve(self) -> None:
        from bumble.controller import Controller
        from bumble.link import LocalLink

        self._loop = asyncio.get_running_loop()
        if self._link is None:
            self._link = LocalLink()
        self._controller = Controller(
            self.name, host_sink=self, link=self._link,
            public_address=self.address)
        self._started.set()
        # Nothing else to do here: work arrives through call_soon_threadsafe.
        await asyncio.Event().wait()

    def on_packet(self, packet: bytes) -> None:
        """Bumble's host sink: a packet from the controller to the firmware.

        It goes out length-prefixed like anything else the running controller
        says -- bumble produces bare H4 and knows nothing about the framing
        0x0008900C expects.
        """
        self._event(bytes(packet))
        self._answered.set()

    # -- thread-safe queueing -------------------------------------------------

    def to_host(self, data: bytes) -> None:
        with self._lock:
            super().to_host(data)

    def has_data(self) -> bool:
        with self._lock:
            return super().has_data()

    def available(self) -> int:
        with self._lock:
            return super().available()

    def take(self, count: int) -> bytes:
        with self._lock:
            return super().take(count)

    # -- the seam -------------------------------------------------------------

    def from_host(self, packet: bytes) -> None:
        if not self.booted:
            # Still the NZ8801 bootloader; none of this is HCI.
            super().from_host(packet)
            return

        # Record it the way the base class would, then decide where it goes.
        self.sent.append(bytes(packet))
        if len(packet) < 3 or packet[0] != 0x01:
            self._forward(packet)
            return

        opcode = packet[1] | (packet[2] << 8)
        self.hci_commands.append(opcode)
        if _opcode_ogf(opcode) == VENDOR_OGF:
            # Bumble answers a vendor opcode with silence, which the firmware
            # reads as a dead controller.
            self.vendor_commands.append(opcode)
            if opcode == self.OPCODE_READ_MEMORY and len(packet) >= 8:
                address = int.from_bytes(packet[4:8], "little")
                self._event(bytes([0x04, 0x0E, 0x08, 0x01,
                                   opcode & 0xFF, opcode >> 8, 0x00])
                            + self.memory.get(address, 0).to_bytes(4, "little"))
            else:
                self._event(bytes([0x04, 0x0E, 0x04, 0x01,
                                   opcode & 0xFF, opcode >> 8, 0x00]))
            return
        self._forward(packet)

    def _forward(self, packet: bytes) -> None:
        from bumble import hci

        try:
            parsed = hci.HCI_Packet.from_bytes(bytes(packet))
        except Exception as exc:
            if self.log:
                self.log(f"  [ble] bumble could not parse {packet.hex(' ')}: {exc}")
            return
        self._answered.clear()
        self._loop.call_soon_threadsafe(self._controller.on_hci_packet, parsed)
        # Wait for the answer so the exchange looks synchronous to the emulator.
        # Events with no reply (ACL data) simply time out, which costs nothing
        # but the wait.
        self._answered.wait(COMMAND_TIMEOUT_S)

    # -- reporting ------------------------------------------------------------

    @property
    def link(self):
        return self._link

    def summary(self) -> str:
        base = super().summary()
        vendor = len(self.vendor_commands)
        forwarded = len(self.hci_commands) - vendor
        return (f"{base}\n  BLE link: {forwarded} HCI commands to bumble, "
                f"{vendor} vendor answered locally, address {self.address}")
