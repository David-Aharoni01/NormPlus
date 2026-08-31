"""Apollo3 Blue peripheral register models.

Scope is set by what the firmware actually touches: scanning the image for
peripheral base addresses finds RSTGEN, CLKGEN, CTIMER/STIMER, GPIO, CACHECTRL,
UART0, MCUCTRL, PWRCTRL, WDT, IOM0, BLEIF and MSPI, and nothing else.

Two rules keep this honest:

* Registers that gate progress are modelled for real. The two that matter most are
  ``STIMER->STTMR`` (every ``am_util_delay_*`` spins on it — a frozen counter is an
  instant hang) and the ``PWRCTRL`` status registers (the HAL waits for a power
  domain to report ready).
* Everything else is read/write storage, and every access to an address with no
  model is counted. :meth:`Bus.unknown_report` prints them, so an unmodelled
  register shows up as a line in the triage report rather than as a mystery hang.
"""

from __future__ import annotations

import collections
from typing import Optional

from .cortexm import IRQ_GPIO, IRQ_MSPI, IRQ_STIMER_CMPR0


class Peripheral:
    """Default behaviour: registers store what was written and read back."""

    name = "PERIPHERAL"
    #: offset -> register name, for tracing.
    REGS: dict[int, str] = {}

    def __init__(self, base: int, size: int, machine=None) -> None:
        self.base = base
        self.size = size
        self.machine = machine
        self.storage: dict[int, int] = {}
        self.reads = collections.Counter()
        self.writes = collections.Counter()

    def reg_name(self, offset: int) -> str:
        return self.REGS.get(offset, f"+0x{offset:03X}")

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        return self.storage.get(offset, 0)

    def write(self, offset: int, size: int, value: int) -> None:
        self.writes[offset] += 1
        self.storage[offset] = value


class Mcuctrl(Peripheral):
    """MCU control: chip identification, and the errata gates keyed off CHIPREV."""

    name = "MCUCTRL"
    REGS = {
        0x000: "CHIPPN", 0x004: "CHIPID0", 0x008: "CHIPID1", 0x00C: "CHIPREV",
        0x010: "VENDORID", 0x014: "SKU", 0x018: "FEATUREENABLE", 0x020: "BOOTLOADER",
        0x024: "SHADOWVALID", 0x030: "SCRATCH0", 0x034: "SCRATCH1", 0x040: "ICODEFAULTADDR",
        0x044: "DCODEFAULTADDR", 0x048: "SYSFAULTADDR", 0x04C: "FAULTSTATUS",
        0x050: "FAULTCAPTUREEN", 0x0F0: "DBGR1", 0x0F4: "DBGR2", 0x198: "PMUENABLE",
        0x1A0: "TPIUCTRL", 0x1B4: "OTAPOINTER", 0x250: "VRCTRL", 0x25C: "ADCPWRDLY",
        0x268: "ADCCAL", 0x26C: "ADCBATTLOAD", 0x2A0: "XTALCTRL", 0x2A4: "XTALGENCTRL",
        0x334: "BOOTLOADERLOW", 0x35C: "SHADOWVALID2",
    }

    #: Low byte of CHIPREV. The HAL branches on this to enable silicon errata
    #: workarounds (this image compares against 0x12 and 0x21). 0x22 selects the
    #: newest revision the firmware knows about; override with --chiprev.
    chiprev = 0x00000022

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == 0x018:
            # FEATUREENABLE drives the handshake that hands the BLE controller to
            # the MCU: software sets BLEREQ (bit 0) and then polls for BLEACK
            # (bit 1) and BLEAVAIL (bit 2). Without the acknowledgement the HAL
            # spins 10000 times and gives up, and the whole BLE stack is skipped.
            requested = self.storage.get(0x018, 0)
            return requested | (0x6 if requested & 1 else 0)
        if offset == 0x000:  # CHIPPN: Apollo3 part number
            return 0x06000000
        if offset == 0x004:  # CHIPID0
            return 0x00000000
        if offset == 0x008:  # CHIPID1
            return 0x00000000
        if offset == 0x00C:
            return self.chiprev
        if offset == 0x010:  # VENDORID: "AMBQ"
            return 0x414D4251
        if offset == 0x024:  # SHADOWVALID
            return 0x00000003
        return self.storage.get(offset, 0)


class Clkgen(Peripheral):
    """Clock generator. CLKKEY/CCTRL are written during startup."""

    name = "CLKGEN"
    REGS = {
        0x000: "CALXT", 0x004: "CALRC", 0x008: "ACALCTR", 0x00C: "OCTRL",
        0x010: "CLKOUT", 0x014: "CLKKEY", 0x018: "CCTRL", 0x01C: "STATUS",
        0x020: "HFADJ", 0x028: "CLOCKENSTAT", 0x02C: "CLOCKEN2STAT",
        0x030: "CLOCKEN3STAT", 0x034: "BLEBUCKSTATUS", 0x100: "FREQCTRL",
        0x1FC: "BLEBUCKTONADJ",
        0x200: "INTEN", 0x204: "INTSTAT", 0x208: "INTCLR", 0x20C: "INTSET",
    }

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == 0x034:
            # Read twice by the BLE bring-up check at 0x0008A746: it requires
            # bit 1 to be set and bit 2 to be non-zero, otherwise it flags the
            # error the image spells "BLE Buck Err!". Reporting the buck as up.
            return 0x00000006
        if offset == 0x01C:  # STATUS: XTal and LFRC running, no oscillator failure
            return 0x00000003
        if offset in (0x028, 0x02C, 0x030):  # CLOCKENSTAT: report everything enabled
            return 0xFFFFFFFF
        return self.storage.get(offset, 0)


class Pwrctrl(Peripheral):
    """Power control. Status registers mirror the enables, else the HAL spins."""

    name = "PWRCTRL"
    REGS = {
        0x000: "SUPPLYSRC", 0x004: "SUPPLYSTATUS", 0x008: "DEVPWREN",
        0x00C: "MEMPWDINSLEEP", 0x010: "MEMPWREN", 0x014: "MEMPWRSTATUS",
        0x018: "DEVPWRSTATUS", 0x01C: "SRAMCTRL", 0x020: "ADCSTATUS",
        0x024: "MISC", 0x028: "DEVPWREVENTEN", 0x02C: "MEMPWREVENTEN",
    }

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == 0x004:  # SUPPLYSTATUS: running from the simo buck, BLE buck on
            return 0x00000003
        if offset == 0x014:  # MEMPWRSTATUS mirrors MEMPWREN
            return self.storage.get(0x010, 0xFFFFFFFF)
        if offset == 0x018:  # DEVPWRSTATUS mirrors DEVPWREN
            return self.storage.get(0x008, 0)
        if offset == 0x020:  # ADCSTATUS: powered and ready
            return 0x00000001
        return self.storage.get(offset, 0)


class Rstgen(Peripheral):
    name = "RSTGEN"
    REGS = {
        0x000: "CFG", 0x004: "SWPOI", 0x008: "SWPOR", 0x00C: "TPIURST",
        0x200: "INTEN", 0x204: "INTSTAT", 0x208: "INTCLR", 0x20C: "INTSET",
    }

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == 0x008:  # STAT: last reset was a power-on reset
            return 0x00000001
        return self.storage.get(offset, 0)

    def write(self, offset: int, size: int, value: int) -> None:
        super().write(offset, size, value)
        # SWPOI / SWPOR with the right key request a reset.
        if offset in (0x004, 0x008) and (value & 0xFF) == 0x1B and self.machine:
            self.machine.request_reset()


class Cachectrl(Peripheral):
    name = "CACHECTRL"
    REGS = {
        0x000: "CACHECFG", 0x004: "FLASHCFG", 0x008: "CTRL", 0x010: "NCR0START",
        0x014: "NCR0END", 0x018: "NCR1START", 0x01C: "NCR1END", 0x040: "DMON0",
        0x044: "DMON1", 0x048: "DMON2", 0x04C: "DMON3", 0x050: "IMON0",
        0x054: "IMON1", 0x058: "IMON2", 0x05C: "IMON3",
    }

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == 0x008:  # CTRL: cache ready, not busy
            return 0x00000004
        return self.storage.get(offset, 0)


class Wdt(Peripheral):
    """Watchdog. Modelled as never expiring — a reset here would just mask bugs."""

    name = "WDT"
    REGS = {
        0x000: "CFG", 0x004: "RSTRT", 0x008: "LOCK", 0x00C: "COUNT",
        0x200: "INTEN", 0x204: "INTSTAT", 0x208: "INTCLR", 0x20C: "INTSET",
    }

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == 0x00C:
            return 0  # the counter is kept at zero: the watchdog never bites
        return self.storage.get(offset, 0)


def _crossed(before: int, after: int, target: int) -> bool:
    if after >= before:
        return before < target <= after
    return target > before or target <= after  # wrapped


class CtimerBlock(Peripheral):
    """CTIMER 0-3 *and* the STIMER, which share one 4 KB block at 0x40008000.

    They have to be modelled together because their registers interleave: the
    STIMER's counter and compares sit at +0x140, the CTIMER's interrupt registers
    at +0x200, and the STIMER's own interrupt registers at +0x300. Splitting the
    block at +0x140 (the obvious-looking thing to do) puts CTIMER's INTEN inside
    the STIMER's range and silently loses the STIMER interrupt enables.

    That matters more than it sounds: the AmbiqSuite FreeRTOS port overrides
    ``vPortSetupTimerInterrupt`` to drive the RTOS tick from an **STIMER compare**
    rather than SysTick. If STMINTEN is not readable at +0x300, the tick interrupt
    is never enabled, nothing preempts, and the first driver that busy-waits hangs
    the whole system.

    ``STTMR`` is the other register that has to be real: every ``am_util_delay_*``
    spins on it, so a frozen counter is an instant hang.
    """

    name = "CTIMER"

    # CTIMER
    INTEN = 0x200
    INTSTAT = 0x204
    INTCLR = 0x208
    INTSET = 0x20C
    # STIMER
    STCFG = 0x140
    STTMR = 0x144
    CAPTURECTRL = 0x148
    SCMPR0 = 0x150  # SCMPR0..7 at 0x150..0x16C
    STMINTEN = 0x300
    STMINTSTAT = 0x304
    STMINTCLR = 0x308
    STMINTSET = 0x30C

    REGS = {
        0x000: "TMR0", 0x004: "CMPRA0", 0x008: "CMPRB0", 0x00C: "CTRL0",
        0x020: "TMR1", 0x024: "CMPRA1", 0x028: "CMPRB1", 0x02C: "CTRL1",
        0x040: "TMR2", 0x044: "CMPRA2", 0x048: "CMPRB2", 0x04C: "CTRL2",
        0x060: "TMR3", 0x064: "CMPRA3", 0x068: "CMPRB3", 0x06C: "CTRL3",
        0x140: "STCFG", 0x144: "STTMR", 0x148: "CAPTURECTRL",
        0x150: "SCMPR0", 0x154: "SCMPR1", 0x158: "SCMPR2", 0x15C: "SCMPR3",
        0x160: "SCMPR4", 0x164: "SCMPR5", 0x168: "SCMPR6", 0x16C: "SCMPR7",
        0x200: "INTEN", 0x204: "INTSTAT", 0x208: "INTCLR", 0x20C: "INTSET",
        0x300: "STMINTEN", 0x304: "STMINTSTAT", 0x308: "STMINTCLR", 0x30C: "STMINTSET",
    }

    #: The STIMER runs from the 32.768 kHz crystal while the core runs at 48 MHz.
    CYCLES_PER_TICK = 48000000 // 32768

    def __init__(self, base: int, size: int, machine=None) -> None:
        super().__init__(base, size, machine)
        self.counter = 0  # STIMER
        self.counters = [0, 0, 0, 0]  # CTIMER 0..3
        self._acc = 0
        self.stim_int_status = 0
        self.stim_int_enable = 0

    # ── time ─────────────────────────────────────────────────────────────────

    def advance(self, cycles: int) -> None:
        self._acc += cycles
        ticks, self._acc = divmod(self._acc, self.CYCLES_PER_TICK)
        if not ticks:
            return
        for i in range(4):
            self.counters[i] = (self.counters[i] + ticks) & 0xFFFFFFFF

        if not (self.storage.get(self.STCFG, 0) & 0x0000000F):
            return  # STIMER clock source is NOCLK: the counter really is frozen
        before = self.counter
        self.counter = (self.counter + ticks) & 0xFFFFFFFF
        for i in range(8):
            cmpr = self.storage.get(self.SCMPR0 + 4 * i)
            if cmpr is not None and _crossed(before, self.counter, cmpr):
                self.stim_int_status |= 1 << i

    def pending_irqs(self) -> list[int]:
        """STIMER compare interrupts are IRQ23..30 (``STIMER_CMPR0..7``)."""
        active = self.stim_int_status & self.stim_int_enable
        return [IRQ_STIMER_CMPR0 + i for i in range(8) if active & (1 << i)]

    # ── registers ────────────────────────────────────────────────────────────

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == self.STTMR:
            return self.counter
        if offset in (0x000, 0x020, 0x040, 0x060):
            return self.counters[offset // 0x20]
        if offset == self.STMINTSTAT:
            return self.stim_int_status
        if offset == self.STMINTEN:
            return self.stim_int_enable
        return self.storage.get(offset, 0)

    def write(self, offset: int, size: int, value: int) -> None:
        self.writes[offset] += 1
        if offset == self.STCFG:
            self.storage[offset] = value
            if value & 0x80000000:  # CLEAR
                self.counter = 0
            return
        if offset == self.STMINTEN:
            self.stim_int_enable = value
            return
        if offset == self.STMINTCLR:
            self.stim_int_status &= ~value & 0xFFFFFFFF
            return
        if offset == self.STMINTSET:
            self.stim_int_status |= value
            return
        if self.SCMPR0 <= offset < self.SCMPR0 + 32:
            # Apollo3 quirk: software writes a *delta*, and the hardware latches
            # `STTMR + delta` as the absolute compare value. Storing the delta
            # verbatim makes every compare fire immediately (or never), which
            # turns the RTOS tick into either a storm or silence.
            self.storage[offset] = (self.counter + value) & 0xFFFFFFFF
            return
        self.storage[offset] = value


class Gpio(Peripheral):
    """GPIO with real set/clear semantics on the output registers.

    This has to be modelled properly rather than stored verbatim, because the
    board uses GPIO pins as **chip selects** for the devices sharing the MSPI bus.
    Without tracking pin state there is no way to tell which device a given SPI
    transfer is addressed to, and the SPI NAND's traffic gets mixed up with the
    display's.
    """

    name = "GPIO"
    RDA, RDB = 0x080, 0x084
    WTA, WTB = 0x088, 0x08C
    WTSA, WTSB = 0x090, 0x094
    WTCA, WTCB = 0x098, 0x09C

    REGS = {
        0x060: "PADKEY", 0x080: "RDA", 0x084: "RDB", 0x088: "WTA", 0x08C: "WTB",
        0x090: "WTSA", 0x094: "WTSB", 0x098: "WTCA", 0x09C: "WTCB",
        0x0A0: "ENA", 0x0A4: "ENB", 0x0A8: "ENSA", 0x0AC: "ENSB",
        0x200: "INT0EN", 0x204: "INT0STAT", 0x208: "INT0CLR", 0x20C: "INT0SET",
        0x210: "INT1EN", 0x214: "INT1STAT", 0x218: "INT1CLR", 0x21C: "INT1SET",
    }

    # Interrupt registers, one set per bank.
    INT_EN = (0x200, 0x210)
    INT_STAT = (0x204, 0x214)
    INT_CLR = (0x208, 0x218)
    INT_SET = (0x20C, 0x21C)

    def __init__(self, base: int, size: int, machine=None) -> None:
        super().__init__(base, size, machine)
        #: Output state of pins 0-31 (bank A) and 32-63 (bank B).
        self.out = [0, 0]
        #: Latched interrupt status per bank.
        self.int_status = [0, 0]
        #: Input level per bank, driven by attached models or injected events.
        self.inputs = [0, 0]

    def pin(self, number: int) -> int:
        """Current output level of pin *number* (0-63)."""
        return (self.out[number // 32] >> (number % 32)) & 1

    def raise_interrupt(self, number: int) -> None:
        """Latch an edge on *number*, as a button press or sensor line would.

        Everything the watch reacts to besides the clock arrives this way — the
        crown, the side button, the touch panel and the accelerometer all signal
        through a GPIO edge, so without this the emulated watch can never receive
        any input at all.
        """
        bank, bit = divmod(number, 32)
        self.int_status[bank] |= 1 << bit

    def set_input(self, number: int, level: int) -> None:
        bank, bit = divmod(number, 32)
        if level:
            self.inputs[bank] |= 1 << bit
        else:
            self.inputs[bank] &= ~(1 << bit)

    def pending_irqs(self) -> list[int]:
        for bank in (0, 1):
            if self.int_status[bank] & self.storage.get(self.INT_EN[bank], 0):
                return [IRQ_GPIO]
        return []

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == self.RDA:
            return self.out[0] | self.inputs[0]
        if offset == self.RDB:
            return self.out[1] | self.inputs[1]
        if offset in self.INT_STAT:
            return self.int_status[self.INT_STAT.index(offset)]
        return self.storage.get(offset, 0)

    def write(self, offset: int, size: int, value: int) -> None:
        self.writes[offset] += 1
        if offset in (self.WTA, self.WTB):
            self.out[0 if offset == self.WTA else 1] = value & 0xFFFFFFFF
            return
        if offset in (self.WTSA, self.WTSB):
            bank = 0 if offset == self.WTSA else 1
            self.out[bank] |= value & 0xFFFFFFFF
            return
        if offset in (self.WTCA, self.WTCB):
            bank = 0 if offset == self.WTCA else 1
            self.out[bank] &= ~value & 0xFFFFFFFF
            return
        if offset in self.INT_CLR:
            # Write-one-to-clear. Without this the handler can never dismiss the
            # interrupt and the first edge becomes a permanent storm.
            self.int_status[self.INT_CLR.index(offset)] &= ~value & 0xFFFFFFFF
            return
        if offset in self.INT_SET:
            self.int_status[self.INT_SET.index(offset)] |= value & 0xFFFFFFFF
            return
        self.storage[offset] = value


class Uart(Peripheral):
    """UART. TX is captured so the firmware's own logging is visible."""

    name = "UART"
    REGS = {
        0x000: "DR", 0x004: "RSR", 0x018: "FR", 0x020: "ILPR", 0x024: "IBRD",
        0x028: "FBRD", 0x02C: "LCRH", 0x030: "CR", 0x034: "IFLS", 0x038: "IER",
        0x03C: "IES", 0x040: "MIS", 0x044: "IEC",
    }

    def __init__(self, base: int, size: int, machine=None) -> None:
        super().__init__(base, size, machine)
        self.tx = bytearray()
        self.lines: list[str] = []

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == 0x018:  # FR: TX FIFO empty, RX FIFO empty, never busy
            return 0x00000090
        if offset == 0x000:
            return 0
        return self.storage.get(offset, 0)

    def write(self, offset: int, size: int, value: int) -> None:
        self.writes[offset] += 1
        if offset == 0x000:  # DR
            byte = value & 0xFF
            if byte in (0x0A, 0x0D):
                if self.tx:
                    self.lines.append(self.tx.decode("utf-8", "replace"))
                    self.tx.clear()
            else:
                self.tx.append(byte)
            return
        self.storage[offset] = value


class I2cDevice:
    """A device on an IOM's I2C bus, addressed by register offset."""

    name = "i2c device"

    def read(self, register: int, length: int) -> bytes:
        return b"\x00" * length

    def write(self, register: int, data: bytes) -> None:
        pass


class Iom(Peripheral):
    """I/O master (SPI/I2C) — the touch panel, PMU and motion sensor bus.

    Transfers are driven through ``CMD``: bits[1:0] select read/write, bits[7:5]
    give the number of offset (register-address) bytes, bits[19:8] the transfer
    size, and bits[31:24] the low offset byte. ``DEVCFG`` (+0x404) holds the I2C
    address. A read pushes the addressed device's bytes into the RX FIFO.

    Without this the whole bus reads back as zeros, which is indistinguishable
    from "every sensor reports nothing".
    """

    name = "IOM"
    DEVCFG = 0x404
    OFFSETHI = 0x220
    CMD = 0x218

    #: CMD bits[1:0]. The two are the other way round from the obvious guess,
    #: which matters: swapped, every sensor read looks like a write and nothing
    #: on the bus can ever answer. Pinned by two transfers that can only be
    #: reads -- the motion sensor's WHO_AM_I, which is a read-only register, and
    #: the touch panel's 8-byte report, whose bytes the driver goes on to parse
    #: as coordinates (see TouchPanel in devices.py).
    CMD_WRITE = 1
    CMD_READ = 2

    #: I2C address -> device model, populated by the board wiring.
    devices: dict[int, I2cDevice]
    REGS = {
        0x000: "FIFO", 0x100: "FIFOPTR", 0x104: "FIFOTHR", 0x108: "FIFOPOP",
        0x10C: "FIFOPUSH", 0x110: "FIFOCTRL", 0x114: "FIFOLOC", 0x200: "INTEN",
        0x204: "INTSTAT", 0x208: "INTCLR", 0x20C: "INTSET", 0x210: "CLKCFG",
        0x214: "SUBMODCTRL", 0x218: "CMD", 0x21C: "DCX", 0x220: "OFFSETHI",
        0x224: "CMDSTAT", 0x240: "DMATRIGEN", 0x244: "DMATRIGSTAT", 0x250: "DMACFG",
        0x254: "DMATOTCOUNT", 0x258: "DMATARGADDR", 0x25C: "DMASTAT", 0x264: "STATUS",
        0x2A0: "CQCFG", 0x2A8: "CQADDR", 0x2B0: "CQSTAT", 0x2B4: "CQFLAGS",
        0x2B8: "CQSETCLEAR", 0x2C0: "CQPAUSEEN", 0x2C4: "CQCURIDX", 0x2C8: "CQENDIDX",
    }

    #: Bytes the write FIFO can hold. Real silicon has 64 across both
    #: directions; the only thing that depends on the number is how much room
    #: FIFOPTR advertises, and the HAL pushes in bursts no larger than this.
    FIFO_DEPTH = 32

    def __init__(self, base: int, size: int, machine=None) -> None:
        super().__init__(base, size, machine)
        self.devices = {}
        self.rx = bytearray()
        #: Bytes the HAL has pushed for the *next* write transfer.
        self.tx = bytearray()
        #: A write whose CMD has been issued but whose payload has not all
        #: arrived yet: (device, register, length).
        self._pending_write = None
        self.transactions = 0
        self.unanswered: collections.Counter = collections.Counter()

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == 0x2B4:
            # CQFLAGS. The HAL waits for `flags & 0x6 == 0x4` before it will
            # consider a queued transfer finished — read out of the arguments to
            # am_hal_flash_delay_status_check at runtime, not guessed. Without it
            # the touch and sensor drivers spin here forever.
            return 0x00000004
        if offset == 0x2B0:  # CQSTAT: queue idle
            return 0x00000000
        if offset in (0x000, 0x108):  # FIFO / FIFOPOP
            word = self.rx[:4]
            del self.rx[: len(word)]
            return int.from_bytes(bytes(word).ljust(4, b"\x00"), "little")
        if offset == 0x100:  # FIFOPTR
            # Only the write half of this register is modelled honestly. The
            # read half answers a constant "32 bytes waiting", which is what it
            # always did and what the HAL's read loop is happy with; reporting
            # the true count instead makes it wait for a threshold that a
            # short transfer never reaches, and every sensor read stops.
            #
            # The write half used to answer FIFO0REM = 0 -- "no room to push" --
            # so the HAL never pushed a payload and *no write on any I2C bus
            # carried data*. The motion sensor is what made it visible: its
            # whole configuration is register writes, so it stayed switched off.
            room = max(0, self.FIFO_DEPTH - len(self.tx))
            return (len(self.rx) & 0xFF) | ((room & 0xFF) << 8) | 0x00200000
        if offset == 0x204:  # INTSTAT: command complete
            return 0x00000001
        if offset == 0x224:  # CMDSTAT: IDLE + command complete
            return 0x00000004
        if offset == 0x264:  # STATUS: idle, not busy
            return 0x00000004
        if offset == 0x25C:  # DMASTAT: transfer complete
            return 0x00000002
        return self.storage.get(offset, 0)

    #: Writes anywhere in this window, and to FIFOPUSH, are payload bytes.
    FIFO_WINDOW = 0x100
    FIFOPUSH = 0x10C

    def write(self, offset: int, size: int, value: int) -> None:
        self.writes[offset] += 1
        if offset < self.FIFO_WINDOW or offset == self.FIFOPUSH:
            self.tx.extend(value.to_bytes(4, "little")[:max(1, size)])
            self._deliver_pending_write()
            return
        self.storage[offset] = value
        if offset == self.CMD:
            self._run_transaction(value)

    def _deliver_pending_write(self) -> None:
        """Hand a write's payload over once the HAL has pushed all of it.

        The HAL fills the FIFO around the CMD write rather than strictly before
        it, so the transfer completes from whichever side finishes last.
        """
        if self._pending_write is None:
            return
        device, register, length = self._pending_write
        if len(self.tx) < length:
            return
        payload = bytes(self.tx[:length]) if device is not None else b""
        # The HAL pushes whole 32-bit words, so a 1-byte register write arrives
        # as one word with three bytes of padding behind it. Those belong to
        # this transfer, not the next one: leaving them queued shifted every
        # subsequent value along by one and the sensor was configured with its
        # neighbours' bytes.
        self.tx.clear()
        self._pending_write = None
        if device is not None:
            device.write(register, payload)

    def _run_transaction(self, cmd: int) -> None:
        address = self.storage.get(self.DEVCFG, 0) & 0xFF
        offset_count = (cmd >> 5) & 0x7
        length = (cmd >> 8) & 0xFFF
        # The register address is up to three bytes wide: the low one rides in
        # CMD and the rest in OFFSETHI, and only the bottom `offset_count` of
        # them go on the wire.
        offset = ((cmd >> 24) & 0xFF) | ((self.storage.get(self.OFFSETHI, 0) & 0xFFFF) << 8)
        register = offset & ((1 << (8 * offset_count)) - 1) if offset_count else 0
        self.transactions += 1
        device = self.devices.get(address)
        if (cmd & 0x3) != self.CMD_READ:
            if device is None:
                self.unanswered[address] += 1
            # A write nobody answers still has to swallow its payload, or
            # the bytes the HAL pushes for it corrupt the next write.
            self._pending_write = (device, register, length)
            self._deliver_pending_write()
            return
        if device is None:
            self.unanswered[address] += 1
            self.rx.extend(bytes(length))
            return
        data = bytes(device.read(register, length))
        self.rx.extend(data[:length].ljust(length, bytes(1)))


class SpiDevice:
    """A device hanging off the MSPI. Override to model real silicon.

    ``exchange`` is handed the bytes the controller shipped for one PIO transfer
    and returns the bytes clocked back. The default returns 0xFF, which is what an
    absent or erased SPI part looks like on the wire.
    """

    name = "absent SPI device"

    def exchange(self, tx: bytes, rx_len: int) -> bytes:
        return b"\xff" * rx_len


class Mspi(Peripheral):
    """Multi-bit SPI controller — the SPI NAND, PSRAM and display path.

    Only the PIO path is modelled, because that is what the boot code uses. The
    bit that matters is ``CTRL`` bit 1 (STATUS, "transfer complete"): the HAL waits
    for it through ``am_hal_flash_delay_status_check(timeout, &CTRL, 0x2, 0x2)``,
    and a model that never sets it hangs the SPI NAND driver forever.

    (The awaited mask/value are not guesswork — they were read out of the poll
    helper's arguments at runtime; see ``docs`` in the README.)
    """

    name = "MSPI"
    CTRL = 0x000
    TXFIFO = 0x010
    RXFIFO = 0x014
    TXENTRIES = 0x018
    RXENTRIES = 0x01C

    # CTRL bit assignments, as used by this firmware.
    START = 1 << 0
    STATUS = 1 << 1
    TXRX = 1 << 10  # set = controller transmits
    XFERBYTES_SHIFT = 16

    REGS = {
        0x000: "CTRL", 0x004: "CFG", 0x008: "ADDR", 0x00C: "INSTR", 0x010: "TXFIFO",
        0x014: "RXFIFO", 0x018: "TXENTRIES", 0x01C: "RXENTRIES", 0x020: "THRESHOLD",
        0x100: "MSPICFG", 0x104: "PADCFG", 0x108: "PADOUTEN", 0x10C: "FLASH",
        0x200: "INTEN", 0x204: "INTSTAT", 0x208: "INTCLR", 0x20C: "INTSET",
        # Offsets confirmed from the firmware's own command queue, which writes
        # them in order: target address, device address, byte count, then config.
        0x250: "DMACFG", 0x254: "DMASTAT", 0x258: "DMATARGADDR", 0x25C: "DMADEVADDR",
        0x260: "DMATOTCOUNT", 0x264: "DMABCOUNT", 0x278: "DMATHRESH",
        # The command-queue offsets are pinned by the layout of the entries the
        # firmware builds (see _pump_command_queue): every entry opens with two
        # writes to CQPAUSE and closes with one to CQCURIDX.
        0x2A0: "CQCFG", 0x2A8: "CQADDR", 0x2AC: "CQSTAT", 0x2B0: "CQFLAGS",
        0x2B4: "CQSETCLEAR", 0x2B8: "CQPAUSE", 0x2C0: "CQCURIDX", 0x2C4: "CQENDIDX",
    }

    DMACFG = 0x250
    DMASTAT = 0x254
    DMATARGADDR = 0x258
    DMADEVADDR = 0x25C
    DMATOTCOUNT = 0x260
    CQCFG = 0x2A0
    CQADDR = 0x2A8
    CQFLAGS = 0x2B0
    CQSETCLEAR = 0x2B4
    CQPAUSE = 0x2B8
    CQCURIDX = 0x2C0
    CQENDIDX = 0x2C4

    #: CQCFG bit 0 (CQEN). The controller only walks the queue while it is set.
    CQ_ENABLE = 1 << 0

    #: CQCURIDX and CQENDIDX are eight bits wide on this part -- am_hal_cmdq's
    #: sync helper reads the current index back with a UXTB. Software's own
    #: counter runs past 255, so both have to be compared modulo 256 or the
    #: queue never looks caught up.
    CQ_INDEX_MASK = 0xFF

    INTEN = 0x200
    INTSTAT = 0x204
    INTCLR = 0x208

    #: INTSTAT bit assignments, taken from the firmware's own MSPI handler
    #: rather than guessed: am_hal_mspi_interrupt_service masks the accumulated
    #: status with 0x18C0 for "finished or failed" and with 0x1880 for "failed",
    #: so the bit in the first mask but not the second -- bit 6 -- is DMA
    #: complete, and 7/11/12 are the three error sources. The driver enables
    #: 0x1AC0, i.e. those plus CQUPD.
    INT_DMACMP = 1 << 6
    INT_DMAERR = 1 << 7
    INT_CQUPD = 1 << 9
    #: Bit 0 reads back set unconditionally: am_hal_mspi_interrupt_service spins
    #: on it before touching the status, and nothing ever waits for it to clear.
    INT_CMDCMP = 1 << 0
    #: Safety caps: an entry is 9 register/value pairs in this firmware, and a
    #: burst is 2 entries. Neither should ever be approached.
    CQ_MAX_PAIRS_PER_ENTRY = 32
    CQ_MAX_ENTRIES_PER_PUMP = 64

    #: DMACFG bits, as used by this firmware (it writes 0x0F to start, 0 to stop).
    DMA_ENABLE = 0x3
    DMA_TO_DEVICE = 1 << 2

    def __init__(self, base: int, size: int, machine=None) -> None:
        super().__init__(base, size, machine)
        #: Fallback device, used when no chip select matches.
        self.device: SpiDevice = SpiDevice()
        #: ``[(gpio_pin, device), ...]`` for the parts sharing this bus, most
        #: specific first. Chip selects are active low.
        self.chip_selects: list[tuple[int, SpiDevice]] = []
        self.tx_fifo = bytearray()
        self.rx_fifo = bytearray()
        self.transfers = 0
        self.dma_transfers = 0
        self.dma_bytes_out = 0
        self.dma_bytes_in = 0
        self.cq_entries = 0
        self._pending: Optional[int] = None
        self._in_command_queue = False
        #: Address of the next queue entry the controller would execute.
        self._cq_next = 0
        #: Latched interrupt status. Real hardware raises the MSPI interrupt when
        #: a DMA finishes; without it the SPI NAND driver posts a transfer, waits
        #: on a semaphore nobody ever gives, and times out -- which is why the
        #: resource partition was never read and every image on the watch face
        #: fell back to LVGL's "No data" placeholder.
        self.int_status = 0
        self.interrupts_raised = 0

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == self.CTRL:
            # The driver polls CTRL for completion, which is the point by which
            # it has finished filling the TX FIFO — so settle the transfer here.
            self._complete_pending()
            return (self.storage.get(self.CTRL, 0) & ~self.START) | self.STATUS
        if offset == self.RXFIFO:
            self._complete_pending()
            word = self.rx_fifo[:4]
            del self.rx_fifo[: len(word)]
            return int.from_bytes(bytes(word).ljust(4, b"\xff"), "little")
        if offset == self.TXENTRIES:
            return len(self.tx_fifo) // 4
        if offset == self.RXENTRIES:
            self._complete_pending()
            return (len(self.rx_fifo) + 3) // 4
        if offset == self.INTSTAT:
            return self.int_status | self.INT_CMDCMP
        if offset == self.DMASTAT:
            # DMACPL — every transfer in this model finishes instantly.
            return 0x00000002
        if offset == self.CQADDR:
            # Software reads CQADDR back to find out how far the controller has
            # got: am_hal_cmdq's sync helper (0x000B85BC) does exactly
            # ``q->pRead = *pCQAddrReg``, and am_hal_cmdq_alloc_block then
            # refuses to wrap the ring past pRead. Returning the value software
            # last *wrote* leaves pRead pinned at the base of the ring, so the
            # queue looks permanently full and every transfer posted through it
            # fails with AM_HAL_STATUS_OUT_OF_RANGE -- which is what stopped the
            # SPI NAND driver from ever reading a page.
            return self._cq_next or self.storage.get(self.CQADDR, 0)
        if offset == 0x2AC:  # CQSTAT: the queue is never busy here
            return 0x00000000
        return self.storage.get(offset, 0)

    def write(self, offset: int, size: int, value: int) -> None:
        self.writes[offset] += 1
        if offset == self.TXFIFO:
            self.tx_fifo.extend(value.to_bytes(4, "little"))
            return
        previous = self.storage.get(offset, 0)
        self.storage[offset] = value
        if offset == self.INTCLR:
            self.int_status &= ~value & 0xFFFFFFFF
            return
        if offset == self.CTRL and value & self.START:
            # A new transfer supersedes any unsettled one.
            self._complete_pending()
            self._pending = value
        elif offset == self.DMACFG and (value & self.DMA_ENABLE) == self.DMA_ENABLE:
            self._run_dma(value)
        elif self._in_command_queue:
            # Writes replayed out of a queue entry must not re-enter the pump.
            pass
        elif offset == self.CQADDR:
            self._cq_next = value
        elif offset == self.CQCURIDX:
            # Only the index moves. CQCURIDX is the controller's own progress
            # counter; writing it does not reposition the fetch pointer, and
            # rewinding to CQADDR here sent the controller back to the base of
            # the ring after a wrap, so it looked for the next entry in a slot
            # software had not written -- the queue then stalled with
            # CQCURIDX != CQENDIDX and every later transfer failed to allocate.
            pass
        elif offset in (self.CQCFG, self.CQENDIDX):
            # Either enabling the queue or posting another entry can set it going.
            self._pump_command_queue()

    # ── DMA and the command queue ────────────────────────────────────────────

    def _run_dma(self, cfg: int) -> None:
        """Move DMATOTCOUNT bytes between memory and the selected SPI device.

        This is the path the display and storage stacks actually use for bulk
        data — not the PIO FIFO — so without it no pixels ever reach the panel.
        """
        count = self.storage.get(self.DMATOTCOUNT, 0)
        address = self.storage.get(self.DMATARGADDR, 0)
        if not count or not address or self.machine is None:
            return
        device = self._select_device()
        if cfg & self.DMA_TO_DEVICE:
            data = bytes(self.machine.uc.mem_read(address, count))
            device.exchange(data, 0)
            self.dma_bytes_out += count
        else:
            data = bytes(device.exchange(b"", count))
            self.machine.uc.mem_write(address, data.ljust(count, b"\xff")[:count])
            self.dma_bytes_in += count
        self.dma_transfers += 1
        self.int_status |= self.INT_DMACMP

    def _pump_command_queue(self) -> None:
        """Walk the hardware command queue the way the controller does.

        The queue is a ring of (register address, value) pairs in SRAM at
        ``CQADDR``. ``CQCURIDX`` is where the controller has got to and
        ``CQENDIDX`` is how far software has posted; the controller executes
        entries while the two differ, and each entry ends by writing its own
        index to ``CQCURIDX`` so the hardware advances.

        That bookkeeping is not optional. Replaying the ring from its base every
        time software touched the block re-ran whatever the *previous* operation
        had left there, which sent every display stripe to the panel twice — half
        a frame of pixels landing at the wrong offset, i.e. a screen of noise.
        """
        if self.machine is None or self._in_command_queue:
            return
        if not self.storage.get(self.CQCFG, 0) & self.CQ_ENABLE:
            return
        self._in_command_queue = True
        try:
            for _ in range(self.CQ_MAX_ENTRIES_PER_PUMP):
                cur = self.storage.get(self.CQCURIDX, 0) & self.CQ_INDEX_MASK
                end = self.storage.get(self.CQENDIDX, 0) & self.CQ_INDEX_MASK
                if cur == end:
                    break  # caught up with software
                if not self._run_cq_entry():
                    break
        finally:
            self._in_command_queue = False

    def _run_cq_entry(self) -> bool:
        """Execute one queue entry. True if it completed and the cursor advanced."""
        address = self._cq_next
        if not address:
            return False
        terminator = self.base + self.CQCURIDX
        jump = self.base + self.CQADDR
        for _ in range(self.CQ_MAX_PAIRS_PER_ENTRY):
            try:
                pair = bytes(self.machine.uc.mem_read(address, 8))
            except Exception:
                return False
            target = int.from_bytes(pair[:4], "little")
            value = int.from_bytes(pair[4:], "little")
            # Software has not written this far: there is no entry here to run.
            if not 0x40000000 <= target < 0x60000000:
                return False
            address += 8
            if (target & ~3) == jump:
                # Not a register poke: alloc_block appends this pair when a
                # block will not fit before the end of the ring, so it is the
                # controller's own "carry on from the base" jump.
                address = value
                continue
            if (target & ~3) == terminator:
                # The index write is the controller's own bookkeeping, so it is
                # applied directly rather than dispatched back through write().
                self.storage[self.CQCURIDX] = value
                self._cq_next = address
                self.cq_entries += 1
                return True
            self.machine.write32(target & ~3, value)
        self._cq_next = address
        return False

    def _complete_pending(self) -> None:
        """Run the transfer that CTRL.START armed, if there is one.

        Deliberately lazy. The driver writes CTRL to start a transaction and only
        *then* pushes the bytes into the TX FIFO, so running the exchange at the
        moment START is written would ship an empty buffer and lose every command.
        Settling at the first completion poll gets the whole payload.
        """
        ctrl = self._pending
        if ctrl is None:
            return
        self._pending = None

        n = (ctrl >> self.XFERBYTES_SHIFT) & 0xFFFF
        is_tx = bool(ctrl & self.TXRX)
        tx = bytes(self.tx_fifo[:n]) if is_tx else b""
        # The FIFO is word-wide, so pushing 1-3 bytes leaves padding behind it.
        # Hardware drops the remainder with the transaction; keeping it would
        # prepend stale bytes to the next command and shift the whole stream.
        self.tx_fifo.clear()
        rx_len = 0 if is_tx else n
        rx = self._select_device().exchange(tx, rx_len)
        if rx_len:
            self.rx_fifo.extend(bytes(rx)[:rx_len].ljust(rx_len, b"\xff"))
        self.transfers += 1

    def pending_irqs(self) -> list[int]:
        # The controller walks the queue on its own, so the model has to advance
        # it as time passes rather than only when software touches a register.
        # Software posts an entry while the queue happens to be paused and then
        # relies on the controller picking it up; pumping only on register
        # writes left it one entry behind for ever, and once the ring wrapped
        # am_hal_cmdq_alloc_block ran out of room and every storage read after
        # that failed.
        self._pump_command_queue()
        if self.int_status & self.storage.get(self.INTEN, 0):
            self.interrupts_raised += 1
            return [IRQ_MSPI]
        return []

    def _select_device(self) -> SpiDevice:
        """Pick the device whose chip select is asserted (active low).

        ``chip_selects`` is ordered: the board drives several of these lines low
        at once, so the first match wins and the list is arranged most-specific
        first.
        """
        gpio = self.machine.bus.by_base[0x40010000] if self.machine else None
        if gpio is not None:
            for pin, device in self.chip_selects:
                if not gpio.pin(pin):
                    return device
        return self.device


class Bleif(Peripheral):
    """The Apollo3's interface to its on-die BLE controller.

    The controller is a separate core reached over this IOM-like block: the HAL
    powers it up through ``BLECFG``/``PWRCMD``, waits on ``BSTATUS``, and then
    exchanges HCI packets through the FIFO. Everything the firmware's Cordio host
    says to the radio passes through here, so this is the seam where the emulated
    watch's BLE traffic can be taken out to a real host stack.

    Currently a recording model: it names the registers, reports the controller as
    powered and idle so bring-up proceeds, and captures whatever the firmware
    writes to the FIFO. Turning that byte stream into HCI is task #25.
    """

    name = "BLEIF"
    FIFO = 0x000
    FIFOPTR = 0x100
    FIFOPOP = 0x108
    FIFOPUSH = 0x10C
    CMD = 0x20C
    CMDSTAT = 0x218
    INTSTAT = 0x224
    BLECFG = 0x300
    PWRCMD = 0x304
    BSTATUS = 0x308

    REGS = {
        0x000: "FIFO", 0x100: "FIFOPTR", 0x104: "FIFOTHR", 0x108: "FIFOPOP",
        0x10C: "FIFOPUSH", 0x110: "FIFOCTRL", 0x114: "FIFOLOC", 0x200: "CLKCFG",
        0x20C: "CMD", 0x210: "CMDRPT", 0x214: "OFFSETHI", 0x218: "CMDSTAT",
        0x220: "INTEN", 0x224: "INTSTAT", 0x228: "INTCLR", 0x22C: "INTSET",
        0x240: "DMATRIGEN", 0x244: "DMATRIGSTAT", 0x250: "DMACFG",
        0x254: "DMATOTCOUNT", 0x258: "DMATARGADDR", 0x25C: "DMASTAT",
        0x300: "BLECFG", 0x304: "PWRCMD", 0x308: "BSTATUS", 0x310: "BLEDBG",
    }

    def __init__(self, base: int, size: int, machine=None) -> None:
        super().__init__(base, size, machine)
        self.tx = bytearray()
        self.rx = bytearray()
        self.commands = 0

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == self.BSTATUS:
            # B2M_STATE = active, SPI ready, no IRQ outstanding. The HAL polls
            # this during power-up and will not proceed until it looks alive.
            return 0x00000030
        if offset == self.CMDSTAT:
            return 0x00000004  # idle, last command complete
        if offset == self.INTSTAT:
            return 0x00000000
        if offset == self.FIFOPTR:
            # No received bytes waiting, plenty of transmit room.
            return 0x00200000
        if offset in (self.FIFO, self.FIFOPOP):
            if self.rx:
                word = self.rx[:4]
                del self.rx[: len(word)]
                return int.from_bytes(bytes(word).ljust(4, b"\x00"), "little")
            return 0
        return self.storage.get(offset, 0)

    def write(self, offset: int, size: int, value: int) -> None:
        self.writes[offset] += 1
        if offset in (self.FIFO, self.FIFOPUSH):
            self.tx.extend(value.to_bytes(4, "little")[:size or 4])
            return
        if offset == self.CMD:
            self.commands += 1
        self.storage[offset] = value

    def summary(self) -> str:
        return (
            f"  BLEIF: {self.commands} commands, {len(self.tx)} bytes written to the "
            f"controller, {sum(self.reads.values())} reads"
        )


class JedecId(Peripheral):
    """The JEDEC / CoreSight identification block at 0xF0000000.

    ``am_hal_mcuctrl_device_info_get`` reads the peripheral-ID and component-ID
    registers here to fill in its device-info struct, so the block has to answer
    or device-info collection faults on an unmapped read.
    """

    name = "JEDEC"
    REGS = {
        0xFE0: "PID0", 0xFE4: "PID1", 0xFE8: "PID2", 0xFEC: "PID3",
        0xFF0: "CID0", 0xFF4: "CID1", 0xFF8: "CID2", 0xFFC: "CID3",
    }

    #: Standard ARM CoreSight component ID; the class field (CID1) marks a
    #: generic IP component.
    _VALUES = {
        0xFE0: 0x000000CE,
        0xFE4: 0x000000B0,
        0xFE8: 0x0000000B,
        0xFEC: 0x00000000,
        0xFF0: 0x0000000D,
        0xFF4: 0x000000F0,
        0xFF8: 0x00000005,
        0xFFC: 0x000000B1,
    }

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        return self._VALUES.get(offset, self.storage.get(offset, 0))


class Bus:
    """Address decode across every modelled peripheral, with a gap log."""

    def __init__(self, machine=None) -> None:
        self.machine = machine
        self.peripherals: list[Peripheral] = []
        self.by_base: dict[int, Peripheral] = {}
        self.unknown_reads = collections.Counter()
        self.unknown_writes = collections.Counter()
        self.unknown_storage: dict[int, int] = {}

    def add(self, peripheral: Peripheral) -> Peripheral:
        self.peripherals.append(peripheral)
        self.by_base[peripheral.base] = peripheral
        return peripheral

    def find(self, addr: int) -> Optional[Peripheral]:
        for p in self.peripherals:
            if p.base <= addr < p.base + p.size:
                return p
        return None

    def describe(self, addr: int) -> str:
        p = self.find(addr)
        if p is None:
            return f"0x{addr:08X} (unmodelled)"
        return f"{p.name}.{p.reg_name(addr - p.base)}"

    def read(self, addr: int, size: int) -> int:
        p = self.find(addr)
        if p is None:
            self.unknown_reads[addr] += 1
            return self.unknown_storage.get(addr, 0)
        return p.read(addr - p.base, size)

    def write(self, addr: int, size: int, value: int) -> None:
        p = self.find(addr)
        if p is None:
            self.unknown_writes[addr] += 1
            self.unknown_storage[addr] = value
            return
        p.write(addr - p.base, size, value)

    def unknown_report(self, limit: int = 15) -> str:
        if not self.unknown_reads and not self.unknown_writes:
            return "  unmodelled MMIO: none"
        out = ["  unmodelled MMIO (address: reads/writes):"]
        addrs = set(self.unknown_reads) | set(self.unknown_writes)
        ranked = sorted(addrs, key=lambda a: -(self.unknown_reads[a] + self.unknown_writes[a]))
        for addr in ranked[:limit]:
            out.append(f"    0x{addr:08X}  r={self.unknown_reads[addr]} w={self.unknown_writes[addr]}")
        if len(ranked) > limit:
            out.append(f"    ... and {len(ranked) - limit} more")
        return "\n".join(out)


def build_apollo3_bus(machine) -> Bus:
    """The peripheral set this firmware actually references."""
    bus = Bus(machine)
    bus.add(Rstgen(0x40000000, 0x1000, machine))
    bus.add(Clkgen(0x40004000, 0x1000, machine))
    bus.add(CtimerBlock(0x40008000, 0x1000, machine))
    bus.add(Peripheral(0x4000C000, 0x1000, machine))  # VCOMP
    bus.add(Gpio(0x40010000, 0x1000, machine))
    bus.add(Peripheral(0x40011000, 0x1000, machine))  # APBDMA
    bus.add(Cachectrl(0x40018000, 0x1000, machine))
    bus.add(Uart(0x4001C000, 0x1000, machine))
    bus.add(Uart(0x4001D000, 0x1000, machine))
    bus.add(Mcuctrl(0x40020000, 0x1000, machine))
    bus.add(Pwrctrl(0x40021000, 0x1000, machine))
    bus.add(Wdt(0x40024000, 0x1000, machine))
    bus.add(Peripheral(0x40030000, 0x1000, machine))  # SECURITY
    bus.add(Peripheral(0x50000000, 0x1000, machine))  # IOSLAVE
    for i in range(6):
        iom = Iom(0x50004000 + i * 0x1000, 0x1000, machine)
        iom.name = f"IOM{i}"
        bus.add(iom)
    bus.add(Peripheral(0x50010000, 0x1000, machine))  # ADC
    bus.add(Peripheral(0x50011000, 0x1000, machine))  # PDM
    bus.add(Bleif(0x5000C000, 0x1000, machine))
    bus.add(Mspi(0x50014000, 0x1000, machine))
    bus.add(JedecId(0xF0000000, 0x1000, machine))
    return bus
