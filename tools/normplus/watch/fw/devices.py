"""The chips wired to the MSPI bus: SPI NAND, PSRAM and the AMOLED display.

The Apollo3 drives three parts over one shared bus, selected by GPIO chip selects
(active low). Which pin belongs to which part was read off the running firmware
rather than assumed — each device's traffic is unmistakable once the bus is
decoded:

===========  ===  ================================================================
Device       Pin  Opening traffic
===========  ===  ================================================================
PSRAM        12   ``F5``, ``66`` (reset enable), ``99`` (reset), ``9F`` (read id)
Display      19   ``02 00 FE 00 01``, ``02 00 36 00 C0``, ``02 00 3A 00 75`` …
SPI NAND      7   ``FF`` (reset), then ``0F C0`` (get feature: status)
===========  ===  ================================================================

The board asserts several of these lines together, so selection is priority
ordered (most specific first) in :func:`attach_mspi_devices`.

**Transaction shape.** The MSPI model issues a command transfer (bytes out) and
then, separately, a data transfer (bytes in). So a device here treats a call with
outbound bytes as "a new command starts" and a subsequent read-only call as
"continue returning this command's data".
"""

from __future__ import annotations

import collections
import hashlib
import math
from pathlib import Path
from typing import Optional

from .peripherals import (BitBangI2cBus, BleController, I2cDevice, SpiDevice)

# ── SPI NAND ─────────────────────────────────────────────────────────────────

CMD_RESET = 0xFF
CMD_READ_ID = 0x9F
CMD_GET_FEATURE = 0x0F
CMD_SET_FEATURE = 0x1F
CMD_WRITE_ENABLE = 0x06
CMD_WRITE_DISABLE = 0x04
CMD_PAGE_READ = 0x13
CMD_READ_CACHE = 0x03
CMD_READ_CACHE_FAST = 0x0B
CMD_READ_CACHE_X2 = 0x3B
CMD_READ_CACHE_X4 = 0x6B
CMD_PROGRAM_LOAD = 0x02
CMD_PROGRAM_LOAD_X4 = 0x32
CMD_PROGRAM_LOAD_RANDOM = 0x84
CMD_PROGRAM_EXECUTE = 0x10
CMD_BLOCK_ERASE = 0xD8
#: Die select — this part is two 1 Gbit dies in one package, and the driver
#: switches between them, so row addresses are relative to the selected die.
CMD_DIE_SELECT = 0xC2

#: Feature register addresses.
FEATURE_BLOCK_LOCK = 0xA0
FEATURE_CONFIG = 0xB0
FEATURE_STATUS = 0xC0
FEATURE_DIE_SELECT = 0xD0

#: Status register bits. OIP is the one that matters most: the driver polls
#: ``GET FEATURE 0xC0`` in a loop and only proceeds once it reads back clear.
STATUS_OIP = 0x01  # operation in progress
STATUS_WEL = 0x02  # write enable latch
STATUS_E_FAIL = 0x04
STATUS_P_FAIL = 0x08


#: Fill byte of a bit-expanded command frame: nibble 0b1110 holds DQ1..DQ3
#: high and drives DQ0 low.
EXPANDED_FILL = 0xEE


def is_expanded_frame(tx: bytes) -> bool:
    """True if *tx* is a command bit-expanded for a quad-configured bus.

    The storage driver keeps the MSPI in quad mode, so it cannot clock a plain
    one-lane command; it expands each command bit into a nibble instead. The
    packer is at 0x00053788 in the firmware: fill the frame with 0xEE, then OR
    the bit into bit 0 of each nibble, most significant bit first. 0xEE holds
    DQ1..DQ3 high and puts the bit on DQ0/SI -- exactly what a standard SPI
    slave samples -- so the part still sees an ordinary command.

    Every byte of such a frame is therefore one of 0xEE/0xEF/0xFE/0xFF and the
    frame is a whole number of commands long. No NAND opcode is 0xEE or 0xFE, so
    this cannot be confused with a raw command.
    """
    return bool(tx) and len(tx) % 4 == 0 and all(b & EXPANDED_FILL == EXPANDED_FILL for b in tx)


def unexpand_command(tx: bytes) -> bytes:
    """Recover the real command bytes from a bit-expanded frame."""
    out = bytearray()
    for i in range(0, len(tx), 4):
        value = 0
        for byte in tx[i : i + 4]:
            value = (value << 2) | (((byte >> 4) & 1) << 1) | (byte & 1)
        out.append(value)
    return bytes(out)


def expand_reply(data: bytes) -> bytes:
    """Put *data* on the wire the way a one-lane reply reaches the controller.

    A standard SPI slave answers on DQ1/SO, so each output bit lands in bit 1 of
    its nibble. The firmware's own un-packer at 0x000534EC confirms the layout:
    it rebuilds a byte from bit 5 and bit 1 of four consecutive received bytes,
    most significant bit first.
    """
    out = bytearray()
    for byte in data:
        for shift in (6, 4, 2, 0):
            pair = (byte >> shift) & 3
            out.append(((pair >> 1) << 5) | ((pair & 1) << 1))
    return bytes(out)


class SpiNand(SpiDevice):
    """A SPI NAND flash holding the watch's resources and OTA staging area.

    Geometry defaults to a 2 Gbit part with 2048-byte pages plus 64 spare bytes,
    64 pages per block — which covers the addresses the firmware and the OTA
    protocol use (resources at ``0x0C780000``, OTA staging at ``0x0FC00000``).

    Storage is sparse: only pages that are written (or preloaded) are held, and
    everything else reads back as erased (``0xFF``), exactly like real flash.
    """

    name = "SPI NAND"

    #: Manufacturer / device ID returned by READ ID.
    #:
    #: Not a guess: the firmware carries a table of the three parts it supports at
    #: 0x000CDF88, and the driver rejects anything else. The entries are
    #: ``EF AA 21`` (1 Gbit), ``EF AB 21`` (2 Gbit) and ``E5 72 E5``. This is the
    #: 2 Gbit one, which is the only size that fits the addresses the firmware
    #: actually uses — OTA staging at 0x0FC00000 is 252 MB in.
    DEFAULT_ID = bytes([0xEF, 0xAB, 0x21])

    def __init__(
        self,
        *,
        page_size: int = 2048,
        spare_size: int = 64,
        pages_per_block: int = 64,
        blocks: int = 2048,  # 2048 x 64 x 2048 = 256 MB, matching the table entry
        device_id: bytes = DEFAULT_ID,
        dies: int = 2,
        log=print,
    ) -> None:
        self.page_size = page_size
        self.spare_size = spare_size
        self.pages_per_block = pages_per_block
        self.blocks = blocks
        self.device_id = device_id
        self.log = log

        self.pages: dict[int, bytearray] = {}
        self.features = {
            FEATURE_BLOCK_LOCK: 0x00,  # all blocks unlocked
            FEATURE_CONFIG: 0x10,  # ECC enabled
            FEATURE_STATUS: 0x00,  # ready, no errors
            FEATURE_DIE_SELECT: 0x00,
        }
        self.cache = bytearray(b"\xff" * (page_size + spare_size))
        self.write_enabled = False
        self.dies = dies
        self.die = 0
        self.response = bytearray()
        self.command_count = 0
        self.pages_read = 0
        self.pages_written = 0
        #: page -> how many times it was read holding nothing: never mounted, never
        #: programmed. In the resource area that is a resource the emulated part does
        #: not have, which an image header alone cannot show when the header was
        #: dumped and the pixels were not (#82).
        self.blank_reads: collections.Counter = collections.Counter()
        #: Pages programmed or erased (or put back by a saved flash state).
        #: What ``--flash-state`` keeps between runs; see flashstate.py.
        self.dirty: set[int] = set()
        #: What was mounted at start, as (byte address, sha256 of the data):
        #: a saved flash state is a difference from exactly this.
        self.origin: list[tuple[int, str]] = []
        self.unknown_commands: dict[int, int] = {}
        # Partially received command, for opcodes whose arguments arrive in a
        # later transfer (see _feed).
        self._opcode: Optional[int] = None
        self._args = bytearray()
        #: Set while the current command arrived bit-expanded, which also means
        #: the controller is clocking four lanes at a part answering on one.
        self.expanded = False
        #: Opcode of the last command executed, used to tell a one-lane reply
        #: (GET FEATURE, READ ID) from a genuine quad data phase (0x6B).
        self._last_opcode: Optional[int] = None
        self.expanded_frames = 0
        #: Column a PROGRAM LOAD's data goes to when it arrived without any:
        #: the next transfer is that data. See the PROGRAM LOAD branch.
        self._loading: Optional[int] = None

    # ── geometry helpers ─────────────────────────────────────────────────────

    @property
    def total_pages(self) -> int:
        return self.blocks * self.pages_per_block

    @property
    def capacity(self) -> int:
        return self.total_pages * self.page_size

    @property
    def pages_per_die(self) -> int:
        return self.total_pages // self.dies

    def _row_to_page(self, row: int) -> int:
        """Absolute page number for a die-relative row address."""
        return (self.die * self.pages_per_die + row) % self.total_pages

    def _page(self, number: int) -> bytearray:
        page = self.pages.get(number)
        if page is None:
            page = bytearray(b"\xff" * (self.page_size + self.spare_size))
            self.pages[number] = page
        return page

    def holds(self, byte_address: int, length: int = 1) -> bool:
        """Whether every page under these bytes holds something (mounted or programmed)."""
        first = byte_address // self.page_size
        last = (byte_address + max(length, 1) - 1) // self.page_size
        return all(n in self.pages for n in range(first, last + 1))

    def peek(self, byte_address: int, length: int) -> bytes:
        """Data-area bytes as a read would return them (0xFF where nothing is held),
        without reading: no counters move and nothing is allocated."""
        out = bytearray()
        while len(out) < length:
            number, column = divmod(byte_address + len(out), self.page_size)
            take = min(length - len(out), self.page_size - column)
            page = self.pages.get(number)
            out += page[column:column + take] if page is not None else b"\xff" * take
        return bytes(out)

    # ── loading real content ─────────────────────────────────────────────────

    def load(self, data: bytes, byte_address: int) -> None:
        """Place *data* at a byte offset in the data area (spare bytes untouched).

        This is how the watch's genuine resource blob gets mounted: the addresses
        come from the OTA protocol's own header, so the emulated part is laid out
        the way the real one is.
        """
        if byte_address % self.page_size:
            raise ValueError(f"0x{byte_address:08X} is not page aligned")
        first = byte_address // self.page_size
        for i in range(0, len(data), self.page_size):
            chunk = data[i : i + self.page_size]
            page = self._page(first + i // self.page_size)
            page[: len(chunk)] = chunk
        self.origin.append((byte_address, hashlib.sha256(data).hexdigest()))
        self.log(
            f"  [nand] loaded {len(data)} bytes at 0x{byte_address:08X} "
            f"(pages {first}..{first + (len(data) - 1) // self.page_size})"
        )

    # ── the wire protocol ────────────────────────────────────────────────────

    #: Bytes of argument each command takes after the opcode. Commands not listed
    #: take a variable-length payload and are executed with whatever has arrived.
    ARG_LENGTHS = {
        CMD_RESET: 0,
        CMD_READ_ID: 0,
        CMD_WRITE_ENABLE: 0,
        CMD_WRITE_DISABLE: 0,
        CMD_GET_FEATURE: 1,
        CMD_SET_FEATURE: 2,
        CMD_DIE_SELECT: 1,
        CMD_PAGE_READ: 3,
        CMD_READ_CACHE: 3,
        CMD_READ_CACHE_FAST: 3,
        CMD_READ_CACHE_X2: 3,
        CMD_READ_CACHE_X4: 3,
        CMD_PROGRAM_EXECUTE: 3,
        CMD_BLOCK_ERASE: 3,
    }

    #: Opcodes whose data phase really is four-lane, so the part drives all
    #: of DQ0..DQ3 and the reply needs no expansion.
    QUAD_DATA_COMMANDS = frozenset({CMD_READ_CACHE_X4, CMD_PROGRAM_LOAD_X4})

    def exchange(self, tx: bytes, rx_len: int) -> bytes:
        if tx and self._loading is not None and self._opcode is None:
            # The data phase of the PROGRAM LOAD just sent, not a new command.
            self.cache[self._loading:self._loading + len(tx)] = tx
            self._loading = None
            tx = b""
        if tx:
            if is_expanded_frame(tx):
                self.expanded = True
                self.expanded_frames += 1
                self._feed(unexpand_command(tx))
            else:
                self.expanded = False
                self._feed(tx)
        if not rx_len:
            return b""
        if self.expanded and self._last_opcode not in self.QUAD_DATA_COMMANDS:
            # A one-lane reply on a four-lane clock: four bytes arrive per
            # real byte. Round up so a request that is not a multiple of
            # four still gets the leading bytes it expects.
            need = (rx_len + 3) // 4
            raw = bytes(self.response[:need]).ljust(need, b"\xff")
            del self.response[:need]
            return expand_reply(raw)[:rx_len].ljust(rx_len, b"\x00")
        out = bytes(self.response[:rx_len]).ljust(rx_len, b"\xff")
        del self.response[:rx_len]
        return out

    def _feed(self, tx: bytes) -> None:
        """Accept command bytes, which may be split across several transfers.

        The driver routinely sends the opcode in one transfer and its argument in
        the next — ``1F A0`` followed by ``00`` is a SET FEATURE with the value in
        a second transfer. Treating every transfer as a fresh command turns those
        continuation bytes into bogus opcodes, so arguments accumulate here until
        the command has as many as it needs.
        """
        if self._opcode is None:
            self.command_count += 1
            self._opcode = tx[0]
            self._args = bytearray(tx[1:])
            self.response.clear()
        else:
            self._args.extend(tx)

        needed = self.ARG_LENGTHS.get(self._opcode)
        if needed is not None and len(self._args) < needed:
            return  # still waiting for the rest

        opcode, args = self._opcode, bytes(self._args)
        self._opcode, self._args = None, bytearray()
        self._command(opcode, args)

    def _command(self, opcode: int, args: bytes) -> None:
        self._last_opcode = opcode

        if opcode == CMD_RESET:
            self.features[FEATURE_STATUS] = 0x00
            self.write_enabled = False
            return

        if opcode == CMD_READ_ID:
            # The driver issues a bare 0x9F, reads four bytes, discards the first
            # as a dummy cycle and packs the remaining three little-endian:
            # ``b1 | b2<<8 | b3<<16``. So the reply is [dummy][mfg][dev_h][dev_l],
            # which for this part gives 0x0021ABEF — the second entry of the
            # firmware's own part table at 0x000CDF88.
            self.response.extend(b"\x00" + self.device_id)
            return

        if opcode == CMD_GET_FEATURE:
            address = args[0] if args else FEATURE_STATUS
            self.response.append(self.features.get(address, 0x00))
            return

        if opcode == CMD_SET_FEATURE:
            if len(args) >= 2:
                self.features[args[0]] = args[1]
            return

        if opcode == CMD_WRITE_ENABLE:
            self.write_enabled = True
            self.features[FEATURE_STATUS] |= STATUS_WEL
            return

        if opcode == CMD_WRITE_DISABLE:
            self.write_enabled = False
            self.features[FEATURE_STATUS] &= ~STATUS_WEL
            return

        if opcode == CMD_PAGE_READ:
            # 24-bit row (page) address, big endian, and relative to the
            # selected die: this part is two 1 Gbit dies and the resources
            # live on the second one (0x0C780000). Decoding the row without
            # _row_to_page reads the wrong half of the part, which comes
            # back erased, so every image decode fails.
            row = int.from_bytes(args[:3].rjust(3, b"\x00"), "big")
            number = self._row_to_page(row)
            page = self.pages.get(number)
            if page is None:
                # Erased, as real flash reads -- and not stored, so that `pages`
                # stays what was mounted or programmed.
                self.cache = bytearray(b"\xff" * (self.page_size + self.spare_size))
                self.blank_reads[number] += 1
            else:
                self.cache = bytearray(page)
            self.pages_read += 1
            # The operation completes instantly here, so status stays "ready" and
            # the driver's OIP poll exits on its first read.
            self.features[FEATURE_STATUS] &= ~STATUS_OIP
            return

        if opcode in (CMD_READ_CACHE, CMD_READ_CACHE_FAST, CMD_READ_CACHE_X2, CMD_READ_CACHE_X4):
            # 16-bit column address then at least one dummy byte.
            column = int.from_bytes(args[:2].rjust(2, b"\x00"), "big") & 0x0FFF
            self.response.extend(self.cache[column:])
            return

        if opcode in (CMD_PROGRAM_LOAD, CMD_PROGRAM_LOAD_X4, CMD_PROGRAM_LOAD_RANDOM):
            column = int.from_bytes(args[:2].rjust(2, b"\x00"), "big") & 0x0FFF
            payload = args[2:]
            if opcode != CMD_PROGRAM_LOAD_RANDOM:
                self.cache = bytearray(b"\xff" * (self.page_size + self.spare_size))
            self.cache[column : column + len(payload)] = payload
            # The driver sends a PROGRAM LOAD as two MSPI transfers: the opcode
            # and column (``02 00 00``), then the page data on its own (2048
            # bytes, PIO, CTRL 0x08000401). It is the mirror of how it reads a
            # feature -- ``0F C0`` and then a separate one-byte receive -- and
            # the chip select (GPIO 7) does not move between them. Taken as a
            # new command, the data's first byte (0x04) read as WRITE DISABLE
            # and every page programmed came out 0xFF: found in an OTA of the
            # resource partition, the first time this firmware ever wrote here.
            self._loading = column if not payload else None
            return

        if opcode == CMD_PROGRAM_EXECUTE:
            row = self._row_to_page(int.from_bytes(args[:3].rjust(3, b"\x00"), "big"))
            if self.write_enabled:
                self._page(row)[:] = self.cache
                self.pages_written += 1
                self.dirty.add(row)
            self.features[FEATURE_STATUS] &= ~(STATUS_OIP | STATUS_WEL)
            self.write_enabled = False
            return

        if opcode == CMD_DIE_SELECT:
            self.die = (args[0] if args else 0) % max(1, self.dies)
            self.features[FEATURE_DIE_SELECT] = self.die
            return

        if opcode == CMD_BLOCK_ERASE:
            row = self._row_to_page(int.from_bytes(args[:3].rjust(3, b"\x00"), "big"))
            if self.write_enabled:
                first = (row // self.pages_per_block) * self.pages_per_block
                for page in range(first, first + self.pages_per_block):
                    self.pages.pop(page, None)
                self.dirty.update(range(first, first + self.pages_per_block))
            self.features[FEATURE_STATUS] &= ~(STATUS_OIP | STATUS_WEL)
            self.write_enabled = False
            return

        self.unknown_commands[opcode] = self.unknown_commands.get(opcode, 0) + 1

    def summary(self) -> str:
        out = [
            f"  SPI NAND: {self.command_count} commands, "
            f"{self.pages_read} page reads, {self.pages_written} page writes, "
            f"{len(self.pages)} pages resident, die {self.die}, "
            f"{self.expanded_frames} bit-expanded frames"
        ]
        if self.unknown_commands:
            unknown = ", ".join(f"0x{op:02X}x{n}" for op, n in self.unknown_commands.items())
            out.append(f"    unhandled commands: {unknown}")
        return "\n".join(out)


# ── PSRAM ────────────────────────────────────────────────────────────────────


class Psram(SpiDevice):
    """External PSRAM — LVGL's frame buffer lives here.

    Kept deliberately simple: it is a big writable array with a handful of
    identification commands, which is all the driver needs from it.
    """

    name = "PSRAM"

    #: APS/IPUS-style identification. The driver reads 8 bytes after ``9F``.
    DEFAULT_ID = bytes([0x0D, 0x5D, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00])

    CMD_READ = 0x03
    CMD_FAST_READ = 0x0B
    CMD_WRITE = 0x02
    CMD_QUAD_READ = 0xEB
    CMD_QUAD_WRITE = 0x38
    CMD_RESET_ENABLE = 0x66
    CMD_RESET = 0x99
    CMD_READ_ID = 0x9F
    CMD_ENTER_QUAD = 0x35
    CMD_EXIT_QUAD = 0xF5
    CMD_MODE_REGISTER = 0xC0

    def __init__(self, size: int = 8 << 20, device_id: bytes = DEFAULT_ID, log=print) -> None:
        self.size = size
        self.device_id = device_id
        self.log = log
        self.data = bytearray(size)
        self.response = bytearray()
        self.command_count = 0
        self.bytes_written = 0
        self.unknown_commands: dict[int, int] = {}

    def exchange(self, tx: bytes, rx_len: int) -> bytes:
        if tx:
            self._command(tx)
        if rx_len:
            out = bytes(self.response[:rx_len]).ljust(rx_len, b"\x00")
            del self.response[:rx_len]
            return out
        return b""

    def _command(self, tx: bytes) -> None:
        self.command_count += 1
        opcode = tx[0]
        args = tx[1:]

        if opcode in (
            self.CMD_RESET_ENABLE,
            self.CMD_RESET,
            self.CMD_ENTER_QUAD,
            self.CMD_EXIT_QUAD,
            self.CMD_MODE_REGISTER,
        ):
            # Mode changes have no observable effect here: the MSPI model already
            # hands over whole byte strings rather than modelling lane widths.
            self.response.clear()
            return
        if opcode == self.CMD_READ_ID:
            self.response.clear()
            self.response.extend(self.device_id)
            return
        if opcode in (self.CMD_READ, self.CMD_FAST_READ, self.CMD_QUAD_READ):
            address = int.from_bytes(args[:3].rjust(3, b"\x00"), "big") % self.size
            self.response.clear()
            self.response.extend(self.data[address : address + 4096])
            return
        if opcode in (self.CMD_WRITE, self.CMD_QUAD_WRITE):
            address = int.from_bytes(args[:3].rjust(3, b"\x00"), "big") % self.size
            payload = args[3:]
            self.data[address : address + len(payload)] = payload
            self.bytes_written += len(payload)
            return
        self.unknown_commands[opcode] = self.unknown_commands.get(opcode, 0) + 1

    def summary(self) -> str:
        out = [f"  PSRAM: {self.command_count} commands, {self.bytes_written} bytes written"]
        if self.unknown_commands:
            unknown = ", ".join(f"0x{op:02X}x{n}" for op, n in self.unknown_commands.items())
            out.append(f"    unhandled commands: {unknown}")
        return "\n".join(out)


# ── display ──────────────────────────────────────────────────────────────────


class Rm67162Display(SpiDevice):
    """The AMOLED panel, with a real framebuffer.

    The wire protocol was read off the running firmware. Commands use the Raydium
    QSPI framing ``02 00 <cmd> 00 <params…>``; pixel writes are announced with
    ``32 00 2C 00`` (memory write) or ``32 00 3C 00`` (continue) and the payload
    then arrives as bulk MSPI DMA::

        02 00 2A 00 00 14 01 7B     column address set: x 20..379
        02 00 2B 00 00 10 01 77     page address set:   y 16..375
        32 00 2C 00                 memory write
        <64800 bytes>               360 x 90 pixels, RGB565
        32 00 3C 00                 memory write continue
        <64800 bytes>               ... four stripes make one 360x360 frame

    ``02 00 3A 00 75`` earlier in the stream selects 16 bits per pixel, which is
    what fixes the format as RGB565.
    """

    name = "RM67162"

    WRITE_COMMAND = 0x02
    WRITE_PIXELS = 0x32

    CMD_COLUMN_ADDRESS = 0x2A
    CMD_PAGE_ADDRESS = 0x2B
    CMD_MEMORY_WRITE = 0x2C
    CMD_MEMORY_WRITE_CONTINUE = 0x3C

    #: Transfers shorter than this are control traffic (DMA descriptors and the
    #: like) rather than pixels, and must not be painted into the framebuffer.
    MIN_PIXEL_TRANSFER = 256

    #: The controller's memory is bigger than the glass, and this watch maps its
    #: 360x360 visible area at (20, 16) inside it -- every window the firmware
    #: sets is 20..379 by 16..375. So the addresses on the wire are *panel*
    #: coordinates and have to be translated before they index the framebuffer.
    #: Treating them as framebuffer coordinates clips each row to 340 pixels
    #: instead of 360, and the picture shears a little further on every row
    #: until the screen is diagonal noise. Re-derived at runtime from any
    #: full-width / full-height window (see _command).
    DEFAULT_OFFSET = (20, 16)

    #: The panel's tearing-effect line, and the shape of the pulse on it.
    #:
    #: The RM67162 raises TE once per internal refresh, and the firmware
    #: busy-waits for that edge before it starts a frame: the loop at
    #: 0x00024006 in display_amoled_rm67162.c reads the pin through
    #: am_hal_gpio_state_read and gives up after 100,000 tries. With nothing
    #: driving the pin every frame ran that timeout to the end -- ~4M
    #: instructions, more than half of everything the watch executed, and enough
    #: to stretch one UI cycle to 153ms of watch time.
    TE_PIN = 18
    TE_PERIOD_CYCLES = 800_000   # 60 Hz at 48 MHz
    TE_HIGH_CYCLES = 48_000      # ~1 ms of vertical blanking

    def __init__(self, width: int = 360, height: int = 360, log=print) -> None:
        self.width = width
        self.height = height
        self.log = log
        self.commands: list[tuple[int, bytes]] = []
        self.pixel_bytes = 0
        self.frames = 0
        self.sleeping = True
        self.display_on = False

        #: RGB565, two bytes per pixel, row-major.
        self.framebuffer = bytearray(width * height * 2)
        #: Window in *panel* coordinates, exactly as the firmware set it.
        self.window = (0, 0, width - 1, height - 1)
        self.offset_x, self.offset_y = self.DEFAULT_OFFSET
        self._x = 0
        self._y = 0
        self._writing = False
        #: Bumped whenever pixels land, so a viewer can repaint only on change.
        self.revision = 0
        #: Bytes painted since the last memory-write restart. One full frame is
        #: width * height * 2, which is the check that the DMA path is sane.
        self.frame_bytes = 0
        self.last_frame_bytes = 0
        #: Tearing-effect line state, set up by :meth:`attach_te`.
        self._te_machine = None
        self._te_pin = self.TE_PIN
        self._te_phase = 0
        self._te_level = -1
        self.te_pulses = 0

    # ── the tearing-effect line ──────────────────────────────────────────────

    def attach_te(self, machine, pin: int = TE_PIN) -> None:
        """Drive the panel's TE pin from the emulated clock."""
        self._te_machine = machine
        self._te_pin = pin
        machine.add_timer(self)

    def next_deadline(self):
        """Cycles until the TE line next changes level.

        The pulse is 48,000 cycles out of an 800,000-cycle period, so neither
        half is narrow — but the *edge* is what the panel driver waits for, and
        landing on it exactly is what keeps the frame timing honest.
        """
        if self._te_machine is None:
            return None
        if self._te_phase < self.TE_HIGH_CYCLES:
            return self.TE_HIGH_CYCLES - self._te_phase
        return self.TE_PERIOD_CYCLES - self._te_phase

    def advance(self, cycles: int) -> None:
        if self._te_machine is None:
            return
        self._te_phase = (self._te_phase + cycles) % self.TE_PERIOD_CYCLES
        level = 1 if self._te_phase < self.TE_HIGH_CYCLES else 0
        if level != self._te_level:
            self._te_level = level
            self.te_pulses += level
            self._te_machine.gpio.set_input(self._te_pin, level)

    # ── wire protocol ────────────────────────────────────────────────────────

    def exchange(self, tx: bytes, rx_len: int) -> bytes:
        if not tx:
            return b"\x00" * rx_len

        # Bulk pixel payloads are tested for first, and by length. They arrive as
        # raw DMA with no header, so a stripe that happens to begin with 0x02 or
        # 0x32 would otherwise be mistaken for a panel command -- which rewrites
        # the window from image data and loses the rest of the frame.
        if self._writing and len(tx) >= self.MIN_PIXEL_TRANSFER:
            self._paint(tx)
        elif tx[0] == self.WRITE_COMMAND and len(tx) >= 3:
            self._command(tx[2], tx[4:])
        elif tx[0] == self.WRITE_PIXELS and len(tx) >= 3:
            command = tx[2]
            if command == self.CMD_MEMORY_WRITE:
                self._begin_write(restart=True)
            elif command == self.CMD_MEMORY_WRITE_CONTINUE:
                self._begin_write(restart=False)
            else:
                self._command(command, tx[4:])
        return b"\x00" * rx_len

    def _command(self, command: int, params: bytes) -> None:
        self.commands.append((command, bytes(params)))
        if command == 0x11:
            self.sleeping = False
        elif command == 0x10:
            self.sleeping = True
        elif command == 0x29:
            self.display_on = True
        elif command == 0x28:
            self.display_on = False
        elif command == self.CMD_COLUMN_ADDRESS and len(params) >= 4:
            x0 = (params[0] << 8) | params[1]
            x1 = (params[2] << 8) | params[3]
            self.window = (x0, self.window[1], x1, self.window[3])
            # A window exactly as wide as the glass must start at the left edge
            # of it, so its origin is the panel offset. Derived rather than
            # hard-coded, so a different panel or rotation still lands square.
            if x1 - x0 + 1 == self.width:
                self.offset_x = x0
        elif command == self.CMD_PAGE_ADDRESS and len(params) >= 4:
            y0 = (params[0] << 8) | params[1]
            y1 = (params[2] << 8) | params[3]
            self.window = (self.window[0], y0, self.window[2], y1)
            if y1 - y0 + 1 == self.height:
                self.offset_y = y0

    def _begin_write(self, *, restart: bool) -> None:
        self._writing = True
        if restart:
            self._x = self.window[0] - self.offset_x
            self._y = self.window[1] - self.offset_y
            if self.frame_bytes:
                self.last_frame_bytes = self.frame_bytes
            self.frame_bytes = 0
            self.frames += 1

    def _paint(self, data: bytes) -> None:
        """Write RGB565 pixels into the active window, row-major with wrap.

        Coordinates are translated from panel space to framebuffer space and
        clipped, but the *stream* still advances by the window's full logical
        width, so clipping can never shift the pixels that follow it.

        Copied a row-segment at a time rather than pixel by pixel: a frame is
        260 KB, so a per-pixel Python loop costs hundreds of thousands of
        iterations per frame and dominates the whole emulator's runtime.
        """
        self.pixel_bytes += len(data)
        self.frame_bytes += len(data)

        x0 = self.window[0] - self.offset_x
        x1 = self.window[2] - self.offset_x
        y1 = self.window[3] - self.offset_y
        # A degenerate window (nothing ever set it, or the parse went wrong)
        # would otherwise walk the whole height one row at a time per transfer.
        if x1 < x0:
            return
        left, right = max(x0, 0), min(x1, self.width - 1)
        bottom = min(y1, self.height - 1)

        framebuffer = self.framebuffer
        pixels = len(data) // 2
        position = 0
        while position < pixels and self._y <= bottom:
            if self._x > x1:
                self._x = x0
                self._y += 1
                continue
            take = min(x1 - self._x + 1, pixels - position)
            start = max(self._x, left)
            stop = min(self._x + take - 1, right)
            if self._y >= 0 and stop >= start:
                source = (position + start - self._x) * 2
                destination = (self._y * self.width + start) * 2
                framebuffer[destination : destination + (stop - start + 1) * 2] = data[
                    source : source + (stop - start + 1) * 2
                ]
            position += take
            self._x += take
        self.revision += 1

    # ── output ───────────────────────────────────────────────────────────────

    def to_rgb(self) -> bytes:
        """Expand the RGB565 framebuffer to packed 8-bit RGB."""
        out = bytearray(self.width * self.height * 3)
        fb = self.framebuffer
        for i in range(self.width * self.height):
            value = (fb[i * 2] << 8) | fb[i * 2 + 1]
            r = (value >> 11) & 0x1F
            g = (value >> 5) & 0x3F
            b = value & 0x1F
            out[i * 3] = (r << 3) | (r >> 2)
            out[i * 3 + 1] = (g << 2) | (g >> 4)
            out[i * 3 + 2] = (b << 3) | (b >> 2)
        return bytes(out)

    def save_png(self, path) -> None:
        """Write the framebuffer as a PNG (stdlib only — no image library)."""
        import struct
        import zlib

        rgb = self.to_rgb()
        stride = self.width * 3
        raw = bytearray()
        for y in range(self.height):
            raw.append(0)  # filter type 0
            raw += rgb[y * stride : (y + 1) * stride]

        def chunk(tag: bytes, payload: bytes) -> bytes:
            return (
                struct.pack(">I", len(payload))
                + tag
                + payload
                + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF)
            )

        png = b"\x89PNG\r\n\x1a\n"
        png += chunk(b"IHDR", struct.pack(">IIBBBBB", self.width, self.height, 8, 2, 0, 0, 0))
        png += chunk(b"IDAT", zlib.compress(bytes(raw), 6))
        png += chunk(b"IEND", b"")
        Path(path).write_bytes(png)
        self.log(f"  [display] wrote {self.width}x{self.height} screenshot to {path}")

    def is_blank(self) -> bool:
        return not any(self.framebuffer)

    def summary(self) -> str:
        state = "on" if self.display_on else "off"
        awake = "awake" if not self.sleeping else "asleep"
        recent = ", ".join(f"0x{c:02X}" for c, _ in self.commands[-8:])
        expected = self.width * self.height * 2
        frame = self.last_frame_bytes or self.frame_bytes
        health = "" if frame in (0, expected) else f" (expected {expected}!)"
        return (
            f"  display: {self.width}x{self.height}, {len(self.commands)} commands, "
            f"panel {awake}/{state}, {self.frames} frames, {self.pixel_bytes} pixel bytes"
            f"{' (framebuffer still blank)' if self.is_blank() else ''}"
            f"\n    panel origin ({self.offset_x}, {self.offset_y}); "
            f"last full frame {frame} bytes{health}"
            f"\n    last commands: {recent}"
        )


# ── wiring ───────────────────────────────────────────────────────────────────

#: Chip-select GPIO pins, determined by decoding the bus traffic (see module docs).
CS_PSRAM = 12
CS_DISPLAY = 19
CS_NAND = 7

#: Where the watch keeps its resource partition and OTA staging area in the NAND
#: (``docs/firmware.md`` §4, and the destination addresses in the image headers).
RESOURCE_ADDRESS = 0x0C780000
OTA_STAGING_ADDRESS = 0x0FC00000


def attach_mspi_devices(
    machine,
    *,
    resource_blob: Optional[Path] = None,
    log=print,
) -> dict[str, SpiDevice]:
    """Hang the SPI NAND, PSRAM and display off the MSPI and return them.

    If *resource_blob* is given it is loaded into the NAND at the resource
    partition address, so the firmware reads its genuine artwork and fonts.
    """
    nand = SpiNand(log=log)
    psram = Psram(log=log)
    display = Rm67162Display(log=log)

    if resource_blob is not None and Path(resource_blob).exists():
        data = Path(resource_blob).read_bytes()
        # The blob carries the same 4-byte destination address prefix the OTA
        # path strips off; the content proper starts after it.
        address = int.from_bytes(data[:4], "little")
        if address == RESOURCE_ADDRESS:
            nand.load(data[4:], RESOURCE_ADDRESS)
        else:
            log(f"  [nand] {Path(resource_blob).name} targets 0x{address:08X}, not the "
                f"resource partition — loading it there anyway")
            nand.load(data[4:], address)

    mspi = machine.bus.by_base[0x50014000]
    # Order matters: the board drives several selects low at once, so the most
    # specific line is tested first.
    mspi.chip_selects = [(CS_PSRAM, psram), (CS_DISPLAY, display), (CS_NAND, nand)]
    mspi.device = nand
    display.attach_te(machine)
    return {"nand": nand, "psram": psram, "display": display}


# -- the touch panel ---------------------------------------------------------

#: The touch controller sits on IOM2 at I2C address 0x20 and signals through
#: GPIO 28. All three were read off the running firmware: pulsing each of the
#: six interrupt-enabled pins in turn, only 28 made the watch talk to a sensor,
#: and the driver it went through is the one next to the ``touch_wcn1f3549.c``
#: path string in the image.
TOUCH_IOM_BASE = 0x50006000
TOUCH_I2C_ADDRESS = 0x20
TOUCH_IRQ_PIN = 28


class TouchPanel(I2cDevice):
    """The WCN1F3549 capacitive panel -- one finger, reported on an interrupt.

    The report format comes from the driver's own parser, not from a datasheet.
    On each edge on GPIO 28 the driver reads eight bytes from register 0x8000
    and two more from 0x8400 into one buffer, and then reads::

        buffer[4:6]  X, little endian
        buffer[6:8]  Y, little endian
        buffer[9]    event flags: bits 0-1 = contact, bit 3 = lift

    It bounds-checks both coordinates against 360 and stores ``359 - value``.
    That is *not* a reason to mirror here: the axis-transform stage right above
    it (0x0003F250, configured ``{1,1,0}`` at 0x10001875) rotates the point 180
    degrees, so the two cancel and the panel's raw coordinates reach LVGL
    unchanged. Mirroring in the model as well sent every touch to the opposite
    corner and reversed the direction of every swipe. Finally the driver writes
    a byte to register 0x03, which is the interrupt acknowledge.
    """

    name = "WCN1F3549 touch"

    REPORT_REGISTER = 0x8000
    EVENT_REGISTER = 0x8400
    ACK_REGISTER = 0x03

    EVENT_CONTACT = 0x01
    EVENT_LIFT = 0x08

    def __init__(self, machine=None, *, width: int = 360, height: int = 360,
                 irq_pin: int = TOUCH_IRQ_PIN, log=print) -> None:
        self.machine = machine
        self.width = width
        self.height = height
        self.irq_pin = irq_pin
        self.log = log

        self.x = 0
        self.y = 0
        self.event = 0
        self.touching = False
        #: Counters, for the boot report.
        self.reports = 0
        self.interrupts = 0
        self.acks = 0

    # -- the wire ------------------------------------------------------------

    def read(self, register: int, length: int) -> bytes:
        if register == self.REPORT_REGISTER:
            report = bytearray(length)
            if length >= 8:
                report[4:6] = self.x.to_bytes(2, "little")
                report[6:8] = self.y.to_bytes(2, "little")
            self.reports += 1
            return bytes(report)
        if register == self.EVENT_REGISTER:
            # Lands at buffer[8:10], so the flags the driver reads at [9] are
            # the second byte.
            return bytes((0, self.event)).ljust(length, bytes(1))[:length]
        return bytes(length)

    def write(self, register: int, data: bytes) -> None:
        if register == self.ACK_REGISTER:
            self.acks += 1

    # -- the finger ----------------------------------------------------------

    def press(self, x: int, y: int) -> None:
        self._report(x, y, self.EVENT_CONTACT)

    def move(self, x: int, y: int) -> None:
        self._report(x, y, self.EVENT_CONTACT)

    def release(self) -> None:
        if not self.touching:
            return
        self._report(self.x, self.y, self.EVENT_LIFT)

    def _report(self, x: int, y: int, event: int) -> None:
        self.x = min(max(int(x), 0), self.width - 1)
        self.y = min(max(int(y), 0), self.height - 1)
        self.event = event
        self.touching = event != self.EVENT_LIFT
        if self.machine is None:
            return
        # The panel holds its interrupt line asserted while a finger is down.
        self.machine.gpio.assert_irq(self.irq_pin, self.touching)
        self.interrupts += 1

    def summary(self) -> str:
        state = f"({self.x}, {self.y})" if self.touching else "up"
        return (f"  touch  : {self.name} at 0x{TOUCH_I2C_ADDRESS:02X}, finger {state}, "
                f"{self.interrupts} interrupts, {self.reports} reports read, "
                f"{self.acks} acknowledged")


# -- the motion sensor -------------------------------------------------------

#: The accelerometer sits on IOM1 at I2C address 0x68 and is an InvenSense
#: MPU-6500-class part. Nothing here is guessed: the firmware's bring-up at
#: 0x000522FC reads WHO_AM_I (0x75), resets through PWR_MGMT_1 (0x6B) = 0x80,
#: waits 100 ms and then walks a 16-entry (register, value) table at 0x000CE058:
#:
#:     6C=3F  19=13  1A=01  1B=00  1C=10  1D=08  23=08  37=00
#:     38=10  39=40  60=00  61=C8  6A=04  6A=44  6B=28  6C=07
#:
#: which is accelerometer-only (PWR_MGMT_2 = 0x07), 50 Hz (SMPLRT_DIV = 19),
#: +/-8 g (ACCEL_CONFIG = 0x10), streaming into the FIFO with its interrupt
#: enabled. The read side is at 0x00052000: INT_STATUS (0x3A), then FIFO_COUNTH
#: and FIFO_COUNTL (0x72/0x73) big-endian and clamped to 200, then a burst from
#: FIFO_R_W (0x74) of "count & ~7" bytes, parsed as 8-byte samples whose first
#: three big-endian int16s are X, Y and Z.
MOTION_IOM_BASE = 0x50005000
MOTION_I2C_ADDRESS = 0x68
#: The sensor's interrupt line. Identified by pressing each of the six pins the
#: firmware enables a GPIO interrupt on and diffing module coverage against an
#: idle run: pin 16 is the only one that moves system_step_task.c (47 -> 92
#: executed blocks) and adds I2C traffic (i2c.c 133 -> 167). Without it the
#: firmware configures the part, waits, and never reads a byte of the FIFO --
#: the driver is interrupt-driven and does not poll.
MOTION_IRQ_PIN = 16


class Walking:
    """A wrist on someone walking: gravity on Z and a vertical bounce at the step rate.

    Give it to :attr:`MotionSensor.motion` and the firmware's own pedometer
    (``system_step_task.c``) counts steps, and its next sport record carries them
    (#51). What it counts is the firmware's business. Over a minute of watch
    time a 2 Hz bounce of 0.5 g counts 121 steps, one a bounce; a 1 Hz bounce
    counts 108, not 60; standing still, none. (It was twice that -- 237 at 2 Hz
    -- until the motion model stopped raising a second interrupt per batch,
    #87.) So a brisk walk counts about right and a slow one does not; the step
    algorithm has not been read.

    A class rather than a closure so that a snapshot can carry it.
    """

    def __init__(self, cadence: float = 2.0, bounce: float = 0.5) -> None:
        #: Steps a second.
        self.cadence = cadence
        #: Peak vertical acceleration on top of gravity, in g.
        self.bounce = bounce

    def __call__(self, seconds: float) -> tuple[float, float, float]:
        return (0.0, 0.0, 1.0 + self.bounce * math.sin(2 * math.pi * self.cadence * seconds))


class MotionSensor(I2cDevice):
    """The wrist's accelerometer: a FIFO that fills on the clock.

    The watch never polls this part. It configures it once and then waits for a
    line to be pulled, so a model that only answers register reads is still
    silent -- the FIFO has to fill in real time and the interrupt has to fire.
    """

    name = "MPU-6500-class motion"

    WHO_AM_I_REG = 0x75
    SMPLRT_DIV = 0x19
    INT_STATUS = 0x3A
    #: FIFO_WM_INT_STATUS: the init writes 0x60/0x61 as a FIFO watermark
    #: threshold (0xC8), which an MPU-6500 does not have and an ICM-20602-class
    #: part does, with the watermark status here.
    FIFO_WM_INT_STATUS = 0x39
    FIFO_COUNTH = 0x72
    FIFO_COUNTL = 0x73
    FIFO_R_W = 0x74
    USER_CTRL = 0x6A
    PWR_MGMT_1 = 0x6B

    #: MPU-6500's id. The bring-up reads WHO_AM_I but does not branch on it --
    #: 0x0005221E only packs it into a version word with CONFIG and PWR_MGMT_1 --
    #: so this identifies the part rather than gating anything.
    WHO_AM_I = 0x70

    #: 8 bytes per FIFO sample, because the reader strides by 8: "bic r2, r5, #7"
    #: then "lsr fp, r5, #3" at 0x0005204E. X, Y, Z, then two bytes it reads past.
    SAMPLE_BYTES = 8
    #: The reader clamps the count to 200 bytes, so there is no point holding
    #: much more than that; a real part's FIFO is 512.
    FIFO_CAPACITY = 512
    #: Raise the interrupt once this many samples are waiting. The init table
    #: writes 0xC8 = 200 to register 0x61, the same figure as the reader's clamp,
    #: which is 25 of these samples.
    WATERMARK_SAMPLES = 25

    #: +/-8 g at 16 bits is 4096 LSB per g (ACCEL_CONFIG = 0x10).
    LSB_PER_G = 4096

    def __init__(self, machine=None, *, irq_pin=MOTION_IRQ_PIN, log=print) -> None:
        self.machine = machine
        self.irq_pin = irq_pin
        self.log = log
        self.registers: dict[int, int] = {}
        self.fifo = bytearray()
        #: Acceleration in g, as the watch feels it lying still face up.
        self.acceleration = (0.0, 0.0, 1.0)
        #: What the wrist is doing, if not lying still: a callable from watch
        #: seconds (counted in samples since the part started) to (x, y, z) in g,
        #: such as :class:`Walking`. None holds :attr:`acceleration`.
        self.motion = None
        self._phase = 0
        self._asserted = False
        self.enabled = False
        self.samples_made = 0
        self.fifo_bytes_read = 0
        self.reads = 0
        self.writes = 0

    # -- the wire ------------------------------------------------------------

    def read(self, register: int, length: int) -> bytes:
        self.reads += 1
        if register == self.FIFO_R_W:
            take = min(length, len(self.fifo))
            data = bytes(self.fifo[:take])
            del self.fifo[:take]
            self.fifo_bytes_read += take
            self._refresh_interrupt()
            return data.ljust(length, bytes(1))
        if register == self.WHO_AM_I_REG:
            return bytes([self.WHO_AM_I]) * max(1, length)
        if register == self.FIFO_COUNTH:
            return bytes([(len(self.fifo) >> 8) & 0xFF]) * max(1, length)
        if register == self.FIFO_COUNTL:
            return bytes([len(self.fifo) & 0xFF]) * max(1, length)
        if register == self.INT_STATUS:
            # Bit 4 is the FIFO interrupt the firmware enables (INT_ENABLE=0x10).
            return bytes([0x10 if self._asserted else 0x00]) * max(1, length)
        if register == self.FIFO_WM_INT_STATUS:
            # Bit 6: a watermark's worth is waiting. The reader re-arms the
            # FIFO and then reads only if this is set (0x00051FFA, then
            # `tst r0, #0x40` at 0x00052036). It is a status, so the init
            # table's write of 0x40 to it does not stick: answering with that
            # write made the step task's second poll after every batch, when
            # the FIFO is empty, look full (#87).
            return bytes([0x40 if self._asserted else 0x00]) * max(1, length)
        return bytes([self.registers.get(register, 0)]) * max(1, length)

    def write(self, register: int, data: bytes) -> None:
        self.writes += 1
        value = data[0] if data else 0
        self.registers[register] = value
        if register == self.PWR_MGMT_1 and value & 0x80:      # DEVICE_RESET
            self.fifo.clear()
            self.registers.clear()
            self.enabled = False
            self._refresh_interrupt()
            return
        if register == self.USER_CTRL:
            if value & 0x04:                                   # FIFO_RST
                self.fifo.clear()
            self.enabled = bool(value & 0x40)                  # FIFO_EN
            self._refresh_interrupt()

    # -- the clock -----------------------------------------------------------

    @property
    def sample_period_cycles(self) -> int:
        divider = self.registers.get(self.SMPLRT_DIV, 19)
        return 48_000_000 * (1 + divider) // 1000

    def next_deadline(self):
        """Cycles until the next sample lands in the FIFO."""
        if not self.enabled:
            return None
        return max(1, self.sample_period_cycles - self._phase)

    def advance(self, cycles: int) -> None:
        if not self.enabled:
            return
        self._phase += cycles
        period = self.sample_period_cycles
        while self._phase >= period:
            self._phase -= period
            self._push_sample()
        self._refresh_interrupt()

    def _push_sample(self) -> None:
        if len(self.fifo) + self.SAMPLE_BYTES > self.FIFO_CAPACITY:
            del self.fifo[:self.SAMPLE_BYTES]        # a real FIFO drops the oldest
        axes = self.acceleration
        if self.motion is not None:
            axes = self.motion(self.samples_made * self.sample_period_cycles / 48_000_000)
        for axis in axes:
            raw = max(-32768, min(32767, int(axis * self.LSB_PER_G)))
            self.fifo.extend((raw & 0xFFFF).to_bytes(2, "big"))
        self.fifo.extend(bytes(2))                   # the pair the reader strides past
        self.samples_made += 1

    def _refresh_interrupt(self) -> None:
        want = len(self.fifo) >= self.WATERMARK_SAMPLES * self.SAMPLE_BYTES
        if want == self._asserted:
            return
        self._asserted = want
        if self.machine is None or self.irq_pin is None:
            return
        # One interrupt per watermark: the init table writes INT_PIN_CFG
        # (0x37) = 0x00, a 50 us pulse, not latched, so the line going back
        # once the FIFO is read is no second event. Latching it as one woke the
        # step task again after every batch, to an empty FIFO, and it fed the
        # heart-rate driver a second time 14 ms after the first -- with no
        # frames, which is what the PixArt algorithm then saw. The physical
        # watch, read through 0xEE mid-measurement, feeds once every ~494 ms.
        if want:
            self.machine.gpio.assert_irq(self.irq_pin, True)
        else:
            self.machine.gpio.release_irq(self.irq_pin)

    def summary(self) -> str:
        state = "streaming" if self.enabled else "idle"
        return (f"  motion : {self.name} at 0x{MOTION_I2C_ADDRESS:02X}, {state}, "
                f"{self.samples_made} samples made, {self.fifo_bytes_read} bytes read, "
                f"{len(self.fifo)} queued")


# -- the heart-rate sensor --------------------------------------------------

#: The PixArt PAH8011 hangs off a second bus that ``ew_drv_sim_i2c.c`` clocks
#: by hand. Its entry in the bit-bang device table at 0x000C92DF is
#: ``2A 27 28``: 8-bit address 0x2A on SCL 39 / SDA 40 (the gauge's entry next
#: to it is ``C4 0B 0D``, 0x62 on pins 11 / 13).
PAH8011_I2C_ADDRESS = 0x15
PAH8011_SCL_PIN = 39
PAH8011_SDA_PIN = 40
#: hr_pah8011.c's open (0x00047D7A) drives pin 17 to power the part, and its
#: close (0x00047D2C) drops it.
PAH8011_POWER_PIN = 17


class Pulse:
    """A wrist with a pulse under the sensor: the PPG the PAH8011 samples (#87).

    Both channels the driver enables see the same waveform: a DC level and a
    small sinusoid at *bpm*. The firmware's PixArt algorithm
    (``pah_hrd_function.c``, a licensed blob) turns it into a heart rate after
    a minute or so of a measurement; the scale is not from a datasheet, and
    only the rate has been checked to come back.

    A class rather than a closure so that a snapshot can carry it.
    """

    def __init__(self, bpm: float = 72.0, dc: int = 1 << 20, depth: float = 0.01) -> None:
        self.bpm = bpm
        self.dc = dc
        self.depth = depth

    def __call__(self, seconds: float) -> int:
        # One sinusoid at the heart rate. A shaped beat -- systolic peak and a
        # dicrotic dip -- read as twice the rate (72 bpm came back as 143).
        return int(self.dc * (1.0 - self.depth * math.sin(2 * math.pi * self.bpm / 60.0 * seconds)))


class Pah8011(I2cDevice):
    """The PixArt PAH8011 heart-rate sensor, as the firmware's driver uses it (#87).

    Everything here is from the driver's own sequence, read out of the image and
    recorded on this bus:

    * banks through register 0x7F, which every bank has;
    * bank 0 register 0x00 is the product id, 0x11: the probe at 0x00047DB0
      writes bank 4 0x69 and reads it up to nine times until it sees 0x11;
    * bank 2 register 0x1B is the interrupt status, write-one-to-clear (the
      init writes 0xFF to it, the task routine at 0x000B2C1C writes back what it
      read): bits 2 and 3 are errors the routine returns as 0xB and 0xC;
    * bank 2 registers 0x25-0x26 are the FIFO count in 32-bit samples (LE16);
      the routine reads ``(count - 1) / channels`` frames, at most 60 samples;
    * bank 3 is the FIFO port: LE 32-bit samples, the enabled channels
      interleaved frame by frame (two channels in this firmware's config);
    * bank 2 registers 0x1C-0x1F hold the XOR of every 32-bit word of the last
      FIFO read, which 0x00088424 checks the data against (error 0xA otherwise);
    * bank 2 register 0x00 bit 0 is touch: is the sensor on skin;
    * it samples once the init has written bank 1 0x30 = 1, until its power
      pin drops.

    The frame period, 20 Hz, is the physical watch's: read through 0xEE in the
    middle of a measurement, its algorithm input (0x10011D40) took 8 to 11
    frames a fill, one fill every ~494 ms. It also fits the 20 frames per report
    the driver asks for (0x1001210C + 0x108), and the 0x0FA0 the init writes to
    bank 1 0x26-0x27 as 4000 ticks of an 80 kHz clock.
    """

    name = "PAH8011 heart rate"
    PRODUCT_ID = 0x11
    CHANNELS = 2
    FIFO_SAMPLES = 256
    FRAME_HZ = 20

    def __init__(self, machine=None, *, power_pin=PAH8011_POWER_PIN, log=print) -> None:
        self.machine = machine
        self.power_pin = power_pin
        self.log = log
        self.bank = 0
        self.registers: dict = {}
        self.status = 0
        self.fifo: collections.deque = collections.deque()
        self.checksum = 0
        self._last_word = bytearray()
        self.running = False
        #: What is under the sensor: a :class:`Pulse`, or None for nothing (no
        #: touch, and a flat signal).
        self.wrist = None
        self._phase = 0
        self.frames_made = 0
        self.samples_read = 0
        self.reads = 0
        self.writes = 0

    # -- the wire ------------------------------------------------------------

    def read(self, register: int, length: int) -> bytes:
        return bytes(self.read_byte((register + i) & 0xFF) for i in range(length))

    def read_byte(self, register: int) -> int:
        self.reads += 1
        bank = self.bank
        if bank == 0 and register == 0x00:
            return self.PRODUCT_ID
        if bank == 3:
            return self._fifo_byte()
        if bank == 2:
            if register == 0x00:
                return 0x01 if self.wrist is not None else 0x00
            if register == 0x1B:
                return self.status
            if register in (0x25, 0x26):
                count = len(self.fifo)
                return count & 0xFF if register == 0x25 else count >> 8
            if 0x1C <= register <= 0x1F:
                return (self.checksum >> (8 * (register - 0x1C))) & 0xFF
        return self.registers.get((bank, register), 0)

    def write(self, register: int, data: bytes) -> None:
        self.writes += 1
        for offset, value in enumerate(data):
            reg = (register + offset) & 0xFF
            if reg == 0x7F:
                if value == 3 and self.bank != 3:
                    # Every FIFO read starts by selecting bank 3 (0x00088390),
                    # and the XOR covers one read.
                    self.checksum = 0
                    self._last_word = bytearray()
                self.bank = value
                continue
            if self.bank == 2 and reg == 0x1B:
                self.status &= ~value & 0xFF
                continue
            if self.bank == 1 and reg == 0x30:
                self._start(bool(value & 1))
            self.registers[(self.bank, reg)] = value

    def _start(self, on: bool) -> None:
        if on and not self.running:
            self.fifo.clear()
            self.checksum = 0
            self._phase = 0
        self.running = on

    def _fifo_byte(self) -> int:
        """Pop the next byte of the FIFO port, keeping the read's XOR checksum."""
        if not self._last_word:
            word = self.fifo.popleft() if self.fifo else 0
            self.samples_read += 1
            self.checksum ^= word
            self._last_word = bytearray(word.to_bytes(4, "little"))
        return self._last_word.pop(0)

    # -- the clock -----------------------------------------------------------

    @property
    def frame_cycles(self) -> int:
        return 48_000_000 // self.FRAME_HZ

    def next_deadline(self):
        if not self._sampling():
            return None
        return max(1, self.frame_cycles - self._phase)

    def _sampling(self) -> bool:
        if not self.running:
            return False
        if self.machine is not None and self.power_pin is not None:
            bank, bit = divmod(self.power_pin, 32)
            return bool((self.machine.gpio.out[bank] >> bit) & 1)
        return True

    def advance(self, cycles: int) -> None:
        if not self._sampling():
            return
        self._phase += cycles
        while self._phase >= self.frame_cycles:
            self._phase -= self.frame_cycles
            self._push_frame()

    def _push_frame(self) -> None:
        seconds = self.frames_made / self.FRAME_HZ
        value = self.wrist(seconds) if self.wrist is not None else 0
        for _ in range(self.CHANNELS):
            if len(self.fifo) >= self.FIFO_SAMPLES:
                self.fifo.popleft()
            self.fifo.append(value & 0xFFFFFFFF)
        self.frames_made += 1
        self.status |= 0x01

    def summary(self) -> str:
        state = "sampling" if self._sampling() else "idle"
        on = "on a wrist" if self.wrist is not None else "on nothing"
        return (f"  hr     : {self.name} at 0x{PAH8011_I2C_ADDRESS:02X}, {state}, {on}, "
                f"{self.frames_made} frames made, {self.samples_read} samples read")


#: The battery gauge is not on an IOM at all. ``ew_drv_sim_i2c.c`` clocks it by
#: hand on two GPIOs, which is why every hardware I2C controller stays idle and
#: the part went unmodelled for so long -- there was no bus traffic to miss.
#: Recovered by decoding the waveform: SDA falls while SCL is high (START), then
#: eight bits give the address byte 0xC4 -> 0x62 write.
PMU_SCL_PIN = 11
PMU_SDA_PIN = 13
PMU_I2C_ADDRESS = 0x62
#: The charger shares that bus at 0x09. It is not a separate driver -- the same
#: pmu_cw6303.c owns both addresses -- which is why looking for a second module
#: talking to it found nothing.
CHARGER_I2C_ADDRESS = 0x09


class Cw6303Pmu(I2cDevice):
    """The battery gauge, on the bit-banged bus at 0x62.

    The map is the firmware's own traffic, decoded off the wire rather than
    taken from a datasheet. Over a 60M-instruction boot it writes MODE, reads
    CONFIG, uploads a 64-byte profile starting at 0x10, and then settles into
    reading 0x02 (twelve times), 0x04, 0x06/0x07, 0x08 and 0x0A. That is the
    CellWise CW201x layout exactly: a cell voltage and a state-of-charge, each
    big-endian across a register pair, above a profile table.

    Note this part reports *charge*, not charger state. The charger bits come
    from the other half of the PMU, at 0x09 on the same bus -- see
    :class:`Cw6303Charger`.
    """

    name = "CW201x-class fuel gauge"

    VERSION = 0x00
    VCELL_H, VCELL_L = 0x02, 0x03
    SOC_H, SOC_L = 0x04, 0x05
    RRT_H, RRT_L = 0x06, 0x07
    CONFIG = 0x08
    MODE = 0x0A
    #: The 64-byte battery profile the firmware uploads, 0x10..0x4F.
    PROFILE_BASE = 0x10
    PROFILE_END = 0x4F

    #: The cell voltage the firmware sees, in microvolts per LSB. A single-cell
    #: lithium pack runs 3.3-4.2 V, and 312.5 uV/LSB puts that in range for the
    #: 14-bit field these parts use.
    UV_PER_LSB = 312.5

    def __init__(self, *, percent: float = 80.0, charging: bool = False,
                 log=print) -> None:
        self.log = log
        self.percent = max(0.0, min(100.0, float(percent)))
        self.charging = bool(charging)
        #: Registers the firmware writes and reads back: CONFIG, MODE and the
        #: 64-byte profile. Held verbatim so an upload survives a read.
        self.ram: dict[int, int] = {}
        self.reads = 0
        self.writes = 0
        self.registers_read: set[int] = set()

    # -- derived values ------------------------------------------------------

    @property
    def millivolts(self) -> float:
        """Cell voltage, interpolated across the usable range by charge."""
        return 3300.0 + (4200.0 - 3300.0) * (self.percent / 100.0)

    def _register(self, register: int) -> int:
        if register == self.VERSION:
            return 0x63                       # identifies the part; nothing branches on it
        raw = int(self.millivolts * 1000.0 / self.UV_PER_LSB) & 0x3FFF
        if register == self.VCELL_H:
            return raw >> 8
        if register == self.VCELL_L:
            return raw & 0xFF
        if register == self.SOC_H:
            return int(self.percent) & 0xFF
        if register == self.SOC_L:
            return int((self.percent % 1.0) * 256) & 0xFF
        if register in (self.RRT_H, self.RRT_L):
            # Remaining run time in minutes; roughly a day from full. The top
            # bit of the high byte is the alert flag on these parts.
            minutes = int(self.percent * 14.4)
            return (minutes >> 8) & 0x1F if register == self.RRT_H else minutes & 0xFF
        return self.ram.get(register, 0x00)

    # -- the wire ------------------------------------------------------------

    def read(self, register: int, length: int) -> bytes:
        self.reads += 1
        self.registers_read.add(register)
        return bytes(self._register((register + i) & 0xFF) for i in range(length))

    def write(self, register: int, data: bytes) -> None:
        self.writes += 1
        for i, byte in enumerate(data):
            self.ram[(register + i) & 0xFF] = byte

    @property
    def profile_bytes(self) -> int:
        """How much of the 64-byte battery profile the firmware has uploaded."""
        return sum(1 for r in self.ram if self.PROFILE_BASE <= r <= self.PROFILE_END)

    def summary(self) -> str:
        state = "charging" if self.charging else "discharging"
        return (f"  battery: {self.name} at 0x{PMU_I2C_ADDRESS:02X} "
                f"(bit-banged, SCL {PMU_SCL_PIN}/SDA {PMU_SDA_PIN}), "
                f"{self.percent:.0f}% {state}, {self.millivolts:.0f} mV, "
                f"{self.profile_bytes}/64 profile bytes, "
                f"{self.reads} reads, {self.writes} writes")


class Cw6303Charger(I2cDevice):
    """The charger half of the PMU, at 0x09 on the same bit-banged bus.

    Both addresses belong to ``pmu_cw6303.c``; there is no second driver. At
    bring-up, 175k instructions into boot, it writes::

        0x01 = 0xB7   0x02 = 0x87   0x03 = 0x00   0x04 = 0xFF
        0x0A..0x0D = 0xBD           0x00 = 0x00

    0x01 and 0x02 are packed at 0x0004E9C8 from two lookup tables -- 32 halfwords
    at 0x000CE008 and 8 at 0x000CE048 -- as ``mode << 6 | flag << 5 | index`` and
    ``0x80 | index``, which is the charge current and voltage setting.

    **0x03 is the status register.** The read at 0x0004E6B6 pulls it into
    0x10001555 and decodes it, and 0x04 is a latch written 0x5F before the read
    and 0xFF after. Every read is followed by a write of 0 to 0x03, so a real
    part clearly re-asserts rather than staying cleared: reads here are always
    generated from the modelled state, and writes to 0x03 are accepted and
    ignored.

    What each bit produces, measured by feeding the firmware each value and
    watching the code the driver returns at 0x0004E6F4:

    ==========  ===================  =======================
    register 3  driver status codes  power event raised
    ==========  ===================  =======================
    absent      0, then 4            0x03
    0x00        0, then 4            0x03
    0x10        5, then 4            0x03
    0x20        2, then 3            0x18  (no screen)
    0x80        2, then 4            0x03
    0xA0        2, then 3            0x18  (no screen)
    ==========  ===================  =======================

    So bit 5 is what the steady-state query at 0x0004E6B6 tests (3 with, 4
    without), bit 7 additionally raises the one-shot code 2, and bit 4 gives the
    codes 5 and 6. Which of 3 and 4 the firmware *calls* "on the charger" is not
    established -- the naming below follows the only inference the evidence
    supports, that a part which is absent (all reads fail, status 0) is a watch
    that is not charging. Nothing in the emulator branches on the names.
    """

    name = "CW6303 charger"

    CHARGE_CURRENT = 0x01
    CHARGE_VOLTAGE = 0x02
    STATUS = 0x03
    LATCH = 0x04

    #: Bit 5: the state the steady query distinguishes. See the table above.
    STATUS_CHARGE = 0x20
    #: Bit 7: additionally produces the one-shot code 2.
    STATUS_CHARGE_EVENT = 0x80
    #: Bit 4: produces codes 5 and 6, which read as a fault.
    STATUS_FAULT = 0x10

    def __init__(self, *, charging: bool = False, status: int | None = None,
                 log=print) -> None:
        self.log = log
        self.charging = bool(charging)
        self._status_override = status
        self.ram: dict[int, int] = {}
        self.reads = 0
        self.writes = 0
        self.registers_read: set = set()

    @property
    def status(self) -> int:
        if self._status_override is not None:
            return self._status_override & 0xFF
        return self.STATUS_CHARGE if self.charging else 0x00

    def _register(self, register: int) -> int:
        if register == self.STATUS:
            return self.status
        return self.ram.get(register, 0x00)

    def read(self, register: int, length: int) -> bytes:
        self.reads += 1
        self.registers_read.add(register)
        return bytes(self._register((register + i) & 0xFF) for i in range(length))

    def write(self, register: int, data: bytes) -> None:
        self.writes += 1
        for i, byte in enumerate(data):
            reg = (register + i) & 0xFF
            if reg == self.STATUS:
                continue          # the firmware clears it after every read
            self.ram[reg] = byte

    @property
    def configured(self) -> bool:
        """True once the firmware has written the charge current and voltage."""
        return self.CHARGE_CURRENT in self.ram and self.CHARGE_VOLTAGE in self.ram

    def summary(self) -> str:
        state = "charging" if self.charging else "not charging"
        setting = (f"I=0x{self.ram[self.CHARGE_CURRENT]:02X} "
                   f"V=0x{self.ram[self.CHARGE_VOLTAGE]:02X}"
                   if self.configured else "unconfigured")
        return (f"  charger: {self.name} at 0x{CHARGER_I2C_ADDRESS:02X} "
                f"(bit-banged, SCL {PMU_SCL_PIN}/SDA {PMU_SDA_PIN}), "
                f"{state}, status 0x{self.status:02X}, {setting}, "
                f"{self.reads} reads, {self.writes} writes")


def attach_pmu(machine, *, percent: float = 80.0, charging: bool = False,
               charger_status: int | None = None, log=print):
    """Put both halves of the PMU on the bus the firmware drives by hand.

    Returns ``(gauge, charger)``. They share one bus and one driver; the gauge
    at 0x62 reports charge, the charger at 0x09 reports charger state.
    """
    bus = BitBangI2cBus(machine.gpio, PMU_SCL_PIN, PMU_SDA_PIN)
    pmu = Cw6303Pmu(percent=percent, charging=charging, log=log)
    charger = Cw6303Charger(charging=charging, status=charger_status, log=log)
    bus.devices[PMU_I2C_ADDRESS] = pmu
    bus.devices[CHARGER_I2C_ADDRESS] = charger
    machine.sim_i2c = bus
    pmu.bus = bus
    charger.bus = bus
    return pmu, charger


def attach_motion_sensor(machine, *, irq_pin=MOTION_IRQ_PIN, log=print) -> MotionSensor:
    """Hang the motion sensor off IOM1 and drive it from the clock."""
    sensor = MotionSensor(machine, irq_pin=irq_pin, log=log)
    iom = machine.bus.by_base.get(MOTION_IOM_BASE)
    if iom is not None:
        iom.devices[MOTION_I2C_ADDRESS] = sensor
    machine.add_timer(sensor)
    return sensor


def attach_heart_rate_sensor(machine, *, log=print) -> Pah8011:
    """Put the PAH8011 on its own bit-banged bus and drive it from the clock."""
    sensor = Pah8011(machine, log=log)
    bus = BitBangI2cBus(machine.gpio, PAH8011_SCL_PIN, PAH8011_SDA_PIN)
    bus.devices[PAH8011_I2C_ADDRESS] = sensor
    sensor.bus = bus
    machine.add_timer(sensor)
    return sensor


def attach_touch_panel(machine, *, log=print) -> TouchPanel:
    """Hang the touch panel off IOM2 and return it."""
    touch = TouchPanel(machine, log=log)
    iom = machine.bus.by_base.get(TOUCH_IOM_BASE)
    if iom is not None:
        iom.devices[TOUCH_I2C_ADDRESS] = touch
    return touch


class NationzController(BleController):
    """The BLE controller this watch actually has: a Nationz NZ8801.

    The part names itself. The third section the firmware downloads is a
    RivieraWaves NVDS blob that starts ``"NVDS"`` and carries, in
    ``[tag][0x06][length][data]`` records, tag 1 = a BD address and tag 2 =
    the string ``"NZ8801V1A"``.

    It boots empty. Before it will speak HCI the host has to download its
    firmware, which the Apollo3 side does in three commands per section --
    all H4 command packets whose opcode *high* byte is the phase and whose
    *low* byte is the section id (built a byte at a time at 0x00089C24)::

        01 <section> F1 02 <length lo> <length hi>     begin, total length
        01 <section> F2 <n>  <n bytes of payload>      data, n <= 0x80
        01 <section> F3 02 <checksum lo> <checksum hi> end

    and this watch sends four sections: 0xBB (1620 bytes of code), 0xCC (560
    more), 0xDD (194 bytes, the NVDS), then 0xEE with a length of zero, which
    means "run it".

    Each of those is answered with a five-byte event the firmware builds for
    itself and compares against with a memcmp at 0x00089CEA::

        04 <section> <phase> 01 00

    which is why an ordinary HCI Command Complete is rejected here -- it is
    not HCI yet. After the 0xEE go-ahead the controller restarts into what it
    was given and says so *unsolicited*, in the same shape; the firmware waits
    for it at 0x00089F56 and checks only that the second byte is 0xEE
    (0x00089F88). From then on the link is ordinary HCI, and the first thing
    across it is the vendor command 0xFD04.

    What this class does **not** do is behave like a radio. It answers so the
    firmware's stack can finish starting, and it keeps every section it was
    given. Putting a real stack behind it -- a bumble ``Controller`` on a
    ``LocalLink``, out to the netsim endpoint the Android emulator's bridge
    already serves -- is the rest of task #25.
    """

    #: Opcode high bytes for the three download phases.
    PHASE_BEGIN = 0xF1
    PHASE_DATA = 0xF2
    PHASE_END = 0xF3
    #: The section whose "begin" doubles as "start running".
    SECTION_GO = 0xEE

    #: Vendor opcode 0xFD02 is "read 32 bits of the controller's memory": an
    #: 8-byte command carrying the address (built at 0x0008A1DA, ``movw r1,
    #: #0xfd02``), answered with a Command Complete whose return parameters are
    #: the value. The caller assembles it from bytes 7..10 of the reply
    #: (0x0008A248), so the whole event is eleven bytes.
    OPCODE_READ_MEMORY = 0xFD02

    #: What that read is *for*: the HAL reads 0x20006054 at 0x00089550 and the
    #: check at 0x00089558 accepts it only in 0x7B00..0x8200 -- a version gate
    #: on the controller. The bound is the firmware's; the value inside it is
    #: not known, so this is a plausible one in range rather than a measured
    #: one, and it is the only invented number in this model.
    CONTROLLER_VERSION_ADDRESS = 0x20006054
    CONTROLLER_VERSION = 0x00007D00

    def __init__(self, log=None) -> None:
        super().__init__()
        self.log = log
        #: True once the go-ahead has been answered and the link is HCI.
        self.booted = False
        #: section id -> the bytes the firmware downloaded into it.
        self.sections: dict = {}
        #: section id -> the length it declared, for checking against.
        self.declared: dict = {}
        #: Opcodes seen after the download, in order.
        self.hci_commands: list = []
        #: The controller's memory, as far as the firmware ever looks at it.
        self.memory = {self.CONTROLLER_VERSION_ADDRESS: self.CONTROLLER_VERSION}
        self._section = None

    def _ack(self, section: int, phase: int) -> None:
        self.to_host(bytes([0x04, section, phase, 0x01, 0x00]))

    def _event(self, payload: bytes) -> None:
        """Queue an HCI event, framed the way the running controller frames.

        Once the download is over the framing changes: 0x0008900C reads a
        **two-byte little-endian length** first (0x00089068-0x00089074),
        refuses anything above 0x100, and only then reads that many bytes.
        The bootloader's replies are raw by comparison, which is why the
        handover needs two shapes rather than one -- by this point the
        controller is running the firmware it was just given.
        """
        self.to_host(len(payload).to_bytes(2, "little") + payload)

    def from_host(self, packet: bytes) -> None:
        super().from_host(packet)
        if len(packet) < 4 or packet[0] != 0x01:
            return
        section, phase, length = packet[1], packet[2], packet[3]
        body = packet[4:4 + length]

        if not self.booted and phase in (self.PHASE_BEGIN, self.PHASE_DATA,
                                         self.PHASE_END):
            if phase == self.PHASE_BEGIN:
                self.declared[section] = int.from_bytes(body[:2], "little")
                self.sections[section] = bytearray()
                self._section = section
            elif phase == self.PHASE_DATA:
                self.sections.setdefault(section, bytearray()).extend(body)
            # Exactly one message goes back, including for the go-ahead. That
            # last one is *not* an ack -- the controller restarts into what it
            # was just given, so it cannot answer the command; what the
            # firmware waits for at 0x00089F56 is the announcement that comes
            # out the other side, and it reads only one message there (two
            # bytes then three). Sending both an ack and an announcement leaves
            # five bytes in the queue that every later read is then skewed by,
            # and the firmware restarts the whole download.
            self._ack(section, phase)
            if phase == self.PHASE_BEGIN and section == self.SECTION_GO:
                self.booted = True
                if self.log:
                    sizes = ", ".join(
                        f"0x{s:02X}={len(d)}B" for s, d in sorted(self.sections.items()))
                    self.log(f"  [ble] controller booted; downloaded {sizes}")
            return

        # Past the bootloader: ordinary HCI.
        opcode = section | (phase << 8)
        self.hci_commands.append(opcode)
        if opcode == self.OPCODE_READ_MEMORY and len(packet) >= 8:
            address = int.from_bytes(packet[4:8], "little")
            value = self.memory.get(address, 0)
            # 0x0008A248 assembles the answer from bytes 7..10 of the payload,
            # so the four return-parameter bytes have to land exactly there.
            self._event(bytes([0x04, 0x0E, 0x08, 0x01, section, phase, 0x00])
                        + value.to_bytes(4, "little"))
            return
        self._event(bytes([0x04, 0x0E, 0x04, 0x01, section, phase, 0x00]))

    # -- what the download tells us about the part ---------------------------

    def nvds(self):
        """The NVDS section, if it has been downloaded."""
        for data in self.sections.values():
            if bytes(data[:4]) == b"NVDS":
                return bytes(data)
        return None

    def nvds_tags(self) -> dict:
        """``{tag: value}`` from the NVDS blob.

        Records are ``[tag][0x06][length][data]`` after the four-byte magic.
        """
        blob = self.nvds()
        tags = {}
        if not blob:
            return tags
        i = 4
        while i + 3 <= len(blob):
            tag, status, length = blob[i], blob[i + 1], blob[i + 2]
            if status != 0x06 or i + 3 + length > len(blob):
                break
            tags[tag] = blob[i + 3: i + 3 + length]
            i += 3 + length
        return tags

    def summary(self) -> str:
        tags = self.nvds_tags()
        name = tags.get(2, b"").rstrip(b"\x00").decode("ascii", "replace")
        address = tags.get(1)
        parts = [f"{len(self.sections)} sections downloaded"]
        if name:
            parts.append(f"part {name}")
        if address:
            parts.append("NVDS address " + ":".join(f"{b:02X}" for b in reversed(address)))
        parts.append("HCI up" if self.booted else "still in the bootloader")
        return "  BLE controller: " + ", ".join(parts)


#: The BLEIF block, where the on-die BLE controller hangs off the bus.
BLEIF_BASE = 0x5000C000


def attach_ble_controller(machine, controller=None, log=print):
    """Put a BLE controller on the other side of the BLEIF FIFO.

    Without one the firmware brings the radio up, sends its first packet into
    nothing and retries until it gives up -- which is faithful to a dead
    controller but is also what makes a radio-on boot slow. With one, the
    transport completes and whatever the firmware's Cordio host says is
    available as H4 packets.

    ``controller`` defaults to :class:`NationzController`, which is enough for
    the firmware's own stack to finish starting: it runs the NZ8801's download
    protocol and then answers HCI. Pass something else to put a real stack
    behind it.
    """
    bleif = machine.bus.by_base.get(BLEIF_BASE)
    if bleif is None:
        log("  [ble] no BLEIF on the bus")
        return None
    if controller is None:
        controller = NationzController(log=log)
    bleif.controller = controller
    attach = getattr(controller, "attach", None)
    if attach is not None:
        attach(machine)
    return controller
