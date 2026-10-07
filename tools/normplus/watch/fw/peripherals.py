"""Apollo3 Blue peripheral register models.

Scope is set by what the firmware actually touches: scanning the image for
peripheral base addresses finds RSTGEN, CLKGEN, RTC, CTIMER/STIMER, GPIO, CACHECTRL,
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

from .cortexm import IRQ_BLE, IRQ_GPIO, IRQ_MSPI, IRQ_RTC, IRQ_STIMER_CMPR0


class Peripheral:
    """Default behaviour: registers store what was written and read back."""

    name = "PERIPHERAL"
    #: offset -> register name, for tracing.
    REGS: dict[int, str] = {}
    #: offset -> the value this register always reads as, for registers whose
    #: answer never depends on anything. The bus serves these without dispatching
    #: here at all (:meth:`Bus.read`), which is worth doing because the firmware
    #: reads some of them millions of times -- its delay helper takes the core
    #: clock from CLKGEN.BLEBUCKSTATUS on every call (#72). :meth:`read` consults
    #: the same table, so the two cannot answer differently.
    CONSTANT_REGS: dict[int, int] = {}

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
    """Clock generator. CLKKEY/CCTRL are written during startup.

    Its block ends at +0x200, where the RTC's begins (:class:`Rtc`).
    """

    name = "CLKGEN"
    REGS = {
        0x000: "CALXT", 0x004: "CALRC", 0x008: "ACALCTR", 0x00C: "OCTRL",
        0x010: "CLKOUT", 0x014: "CLKKEY", 0x018: "CCTRL", 0x01C: "STATUS",
        0x020: "HFADJ", 0x028: "CLOCKENSTAT", 0x02C: "CLOCKEN2STAT",
        0x030: "CLOCKEN3STAT", 0x034: "BLEBUCKSTATUS", 0x100: "FREQCTRL",
        0x1FC: "BLEBUCKTONADJ",
    }

    CONSTANT_REGS = {
        # BLEBUCKSTATUS. Read twice by the BLE bring-up check at 0x0008A746,
        # which requires bit 1 set and bit 2 non-zero or it flags the error the
        # image spells "BLE Buck Err!" -- so the buck is reported up. It is also
        # the most-read register in the machine by a wide margin: the firmware's
        # delay helper at 0x0008B544 reads it on every call to find out how fast
        # the core is running, which is two million times in a boot that draws
        # the factory resources (#72).
        0x034: 0x00000006,
        # STATUS: XTal and LFRC running, no oscillator failure.
        0x01C: 0x00000003,
        # CLOCKENSTAT and its two companions: report everything enabled.
        0x028: 0xFFFFFFFF, 0x02C: 0xFFFFFFFF, 0x030: 0xFFFFFFFF,
    }

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        value = self.CONSTANT_REGS.get(offset)
        return self.storage.get(offset, 0) if value is None else value


def _bcd(value: int) -> int:
    """The HAL's dec_to_bcd (0x00094F04): tens in the high nibble, unchecked."""
    return (((value // 10) << 4) | (value % 10)) & 0xFF


def _dec(value: int) -> int:
    """The HAL's bcd_to_dec (0x00092DC0): high nibble x 10 + low nibble."""
    return (value >> 4) * 10 + (value & 0xF)


class Rtc(Peripheral):
    """The real-time clock: the watch's time of day, and its once-a-second alarm.

    The firmware keeps the time in this counter and nowhere else, through the
    AmbiqSuite HAL: ``am_hal_rtc_time_get`` (0x0008D614) reads CTRLOW and CTRUP
    and BCD-decodes them, ``am_hal_rtc_time_set`` (0x0008D6A8) writes both with
    RTCCTL.WRTC set. A model that only stores those registers gives back the
    last time written, which is the face standing still after a phone sets the
    time (#81).

    ``..\\Board\\rtc.c``'s init (0x000B3CE0) also arms the alarm to repeat every
    second (``am_hal_rtc_alarm_set(t, 7)`` at 0x000B3D86), enables ALM, and
    enables IRQ 2; the ISR (0x0008DFE8) clears ALM and calls the callback
    registered at [0x100005A4]. So the alarm is modelled too, for every RPT
    interval the HAL can set.

    Field layout, from the HAL's own shifts and masks:

    ``CTRLOW``  100ths [7:0], seconds [14:8], minutes [22:16], hours [29:24]
    ``CTRUP``   date [5:0], month [12:8], year [23:16], weekday [26:24],
                CB [27], CEB [28], CTERR [31]
    ``ALMLOW``  as CTRLOW; ``ALMUP`` date [5:0], month [12:8], weekday [18:16]
    ``RTCCTL``  WRTC [0], RPT [3:1], RSTOP [4], HR1224 [5]

    The year is whatever the firmware writes: ``rtc.c`` stores year - 1900, so
    2026 is 126 and goes in as ``0xC6`` -- not BCD, but the HAL's decode gives
    126 back, so the field is held as the firmware's number. Leap years are
    every fourth, which is the same answer for 1900 + year as for a two-digit
    year in 2000-2099. 24-hour only: nothing in the image sets HR1224.

    The counter runs on the watch's clock, not the host's: a hundredth is
    480,000 cycles. It starts at zero, as after a power-on, so the firmware
    puts in its 2017-01-01 12:00 default until a phone sets the time.
    """

    name = "RTC"

    CTRLOW = 0x040
    CTRUP = 0x044
    ALMLOW = 0x048
    ALMUP = 0x04C
    RTCCTL = 0x050
    INTEN = 0x100
    INTSTAT = 0x104
    INTCLR = 0x108
    INTSET = 0x10C

    REGS = {
        0x040: "CTRLOW", 0x044: "CTRUP", 0x048: "ALMLOW", 0x04C: "ALMUP",
        0x050: "RTCCTL", 0x100: "INTEN", 0x104: "INTSTAT", 0x108: "INTCLR",
        0x10C: "INTSET",
    }

    #: 48 MHz / 100.
    CYCLES_PER_HUNDREDTH = 480_000
    DAY = 8_640_000  # hundredths
    #: Alarm repeat interval (RTCCTL.RPT) -> its period in hundredths, for the
    #: ones with a fixed period: 4 day, 5 hour, 6 minute, 7 second. 1 (year),
    #: 2 (month) and 3 (week) depend on the calendar. There is no 8 or 9 in the
    #: field: the HAL caps RPT at 7 and asks for every tenth or every hundredth
    #: with ALM100's high nibble, 0xF0 or 0xFF (0x0008D512-0x0008D534).
    _PERIOD = {4: DAY, 5: 360_000, 6: 6_000, 7: 100}

    def __init__(self, base: int, size: int, machine=None) -> None:
        super().__init__(base, size, machine)
        #: hundredths, seconds, minutes, hours, date, month, year, weekday,
        #: century, century enable -- as of the last :meth:`_now`.
        self.counter = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
        #: Hundredths elapsed since then, and cycles into the next one.
        self.ticks = 0
        self._acc = 0
        self.int_enable = 0
        self.int_status = 0
        #: Hundredths until the alarm next matches, or None when it cannot.
        self._alarm_in: Optional[int] = None
        self.alarms = 0

    # ── the counter ──────────────────────────────────────────────────────────

    @property
    def running(self) -> bool:
        return not self.storage.get(self.RTCCTL, 0) & 0x10  # RSTOP

    @staticmethod
    def _days_in(month: int, year: int) -> int:
        if month == 2:
            return 29 if year % 4 == 0 else 28
        return 30 if month in (4, 6, 9, 11) else 31

    @classmethod
    def _fold(cls, counter: list, ticks: int) -> list:
        """*counter* after *ticks* more hundredths."""
        h, s, mi, hr, date, month, year, wday, cb, ceb = counter
        total = h + 100 * (s + 60 * (mi + 60 * hr)) + ticks
        days, tod = divmod(total, cls.DAY)
        hr, tod = divmod(tod, 360_000)
        mi, tod = divmod(tod, 6_000)
        s, h = divmod(tod, 100)
        wday = (wday + days) % 7
        for _ in range(days):
            date += 1
            if date > cls._days_in(month, year):
                date, month = 1, month + 1
                if month > 12:
                    month, year = 1, year + 1
                    if year == 100:  # 99 -> 00: the century bit flips, if enabled
                        year = 0
                        cb ^= ceb
        return [h, s, mi, hr, date, month, year, wday, cb, ceb]

    def _now(self) -> list:
        """The counter as it reads now; folds the elapsed hundredths into it."""
        if self.ticks:
            self.counter = self._fold(self.counter, self.ticks)
            self.ticks = 0
        return self.counter

    def reading(self) -> dict:
        """The time as the firmware would read it, without touching the model --
        safe from another thread while the CPU runs (``EmulatedWatch``)."""
        while True:
            counter, ticks = list(self.counter), self.ticks
            if counter == self.counter:
                break
        h, s, mi, hr, date, month, year, wday, cb, ceb = self._fold(counter, ticks)
        return dict(year=year, month=month, date=date, hour=hr, minute=mi, second=s,
                    hundredths=h, weekday=wday)

    def _low(self) -> int:
        h, s, mi, hr = self._now()[:4]
        return (_bcd(hr) & 0x3F) << 24 | (_bcd(mi) & 0x7F) << 16 | (_bcd(s) & 0x7F) << 8 | _bcd(h)

    def _up(self) -> int:
        date, month, year, wday, cb, ceb = self._now()[4:]
        return (ceb << 28 | cb << 27 | (wday & 7) << 24 | _bcd(year) << 16
                | (_bcd(month) & 0x1F) << 8 | (_bcd(date) & 0x3F))

    # ── the alarm ────────────────────────────────────────────────────────────

    def _schedule_alarm(self) -> None:
        """Hundredths from now until the counter next equals the alarm in every
        field RPT selects -- strictly after now, because a match at this very
        hundredth has already been latched on the way in."""
        rpt = (self.storage.get(self.RTCCTL, 0) >> 1) & 7
        if rpt == 0 or not self.running:
            self._alarm_in = None
            return
        h, s, mi, hr, date, month, _year, wday = self._now()[:8]
        low = self.storage.get(self.ALMLOW, 0)
        up = self.storage.get(self.ALMUP, 0)
        a_h = _dec(low & 0xFF)
        tod_alarm = (a_h + 100 * (_dec((low >> 8) & 0x7F) + 60 * (_dec((low >> 16) & 0x7F)
                     + 60 * _dec((low >> 24) & 0x3F))))
        tod = h + 100 * (s + 60 * (mi + 60 * hr))
        if rpt == 7 and low & 0xF0 == 0xF0:
            if low & 0xFF == 0xFF:  # every hundredth
                self._alarm_in = 1
            else:  # every tenth, on ALM100's low digit
                self._alarm_in = (((low & 0xF) - h) % 10) or 10
            return
        period = self._PERIOD.get(rpt)
        if period is not None:
            self._alarm_in = ((tod_alarm - tod) % period) or period
            return
        if rpt == 3:  # every week: weekday and time of day
            week = 7 * self.DAY
            here = wday * self.DAY + tod
            there = ((up >> 16) & 7) * self.DAY + tod_alarm
            self._alarm_in = ((there - here) % week) or week
            return
        # 2 every month (date), 1 every year (month and date): walk the calendar.
        a_date, a_month = _dec(up & 0x3F), _dec((up >> 8) & 0x1F)
        year = _year
        ahead = tod_alarm - tod
        for _ in range(8 * 366):
            if ahead > 0 and date == a_date and (rpt == 2 or month == a_month):
                self._alarm_in = ahead
                return
            ahead += self.DAY
            date += 1
            if date > self._days_in(month, year):
                date, month = 1, month + 1
                if month > 12:
                    month, year = 1, year + 1
        self._alarm_in = None  # a date that never comes (Feb 30)

    def next_deadline(self) -> Optional[int]:
        """Cycles until the alarm next matches. The counter itself needs no
        deadline: nothing in the firmware spins on it, so reading it as of the
        last slice is exact enough."""
        if self._alarm_in is None or not self.running:
            return None
        return max(1, self._alarm_in * self.CYCLES_PER_HUNDREDTH - self._acc)

    def advance(self, cycles: int) -> None:
        if not self.running:
            return
        self._acc += cycles
        ticks, self._acc = divmod(self._acc, self.CYCLES_PER_HUNDREDTH)
        if not ticks:
            return
        self.ticks += ticks
        if self._alarm_in is not None:
            self._alarm_in -= ticks
            if self._alarm_in <= 0:
                self.int_status |= 1  # ALM
                self.alarms += 1
                self._schedule_alarm()

    def pending_irqs(self) -> list[int]:
        return [IRQ_RTC] if self.int_status & self.int_enable else []

    # ── registers ────────────────────────────────────────────────────────────

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == self.CTRLOW:
            return self._low()
        if offset == self.CTRUP:
            return self._up()  # CTERR stays 0: the two reads are never split by a tick
        if offset == self.INTEN:
            return self.int_enable
        if offset == self.INTSTAT:
            return self.int_status
        return self.storage.get(offset, 0)

    def write(self, offset: int, size: int, value: int) -> None:
        self.writes[offset] += 1
        if offset in (self.CTRLOW, self.CTRUP):
            if not self.storage.get(self.RTCCTL, 0) & 1:
                return  # WRTC clear: the counter is write-protected
            c = self._now()
            if offset == self.CTRLOW:
                c[0:4] = [_dec(value & 0xFF), _dec((value >> 8) & 0x7F),
                          _dec((value >> 16) & 0x7F), _dec((value >> 24) & 0x3F)]
                self._acc = 0
            else:
                c[4:10] = [_dec(value & 0x3F), _dec((value >> 8) & 0x1F),
                           _dec((value >> 16) & 0xFF), (value >> 24) & 7,
                           (value >> 27) & 1, (value >> 28) & 1]
            self._schedule_alarm()
            return
        if offset == self.INTEN:
            # Enabling over an already-latched status makes an interrupt
            # deliverable now, which no deadline could have predicted.
            self.int_enable = value
            if self.machine is not None:
                self.machine.cut_slice()
            return
        if offset == self.INTCLR:
            self.int_status &= ~value & 0xFFFFFFFF
            return
        if offset == self.INTSET:
            self.int_status |= value
            if self.machine is not None:
                self.machine.cut_slice()
            return
        self.storage[offset] = value
        if offset in (self.ALMLOW, self.ALMUP, self.RTCCTL):
            self._schedule_alarm()
            if self.machine is not None:
                self.machine.cut_slice()  # the deadline it was running to has moved


class Pwrctrl(Peripheral):
    """Power control. Status registers mirror the enables, else the HAL spins."""

    name = "PWRCTRL"
    REGS = {
        0x000: "SUPPLYSRC", 0x004: "SUPPLYSTATUS", 0x008: "DEVPWREN",
        0x00C: "MEMPWDINSLEEP", 0x010: "MEMPWREN", 0x014: "MEMPWRSTATUS",
        0x018: "DEVPWRSTATUS", 0x01C: "SRAMCTRL", 0x020: "ADCSTATUS",
        0x024: "MISC", 0x028: "DEVPWREVENTEN", 0x02C: "MEMPWREVENTEN",
    }

    #: DEVPWREN bit -> DEVPWRSTATUS bit, read out of the firmware's own
    #: ``am_hal_pwrctrl_periph`` table at **0x000CB1EC**: three words per
    #: peripheral, word0 the DEVPWREN mask and word1 the DEVPWRSTATUS mask that
    #: ``am_hal_pwrctrl_periph_enable`` (0x0008D2C0) polls for. The two are *not*
    #: the same bit, and several peripherals share one status bit -- IOM0..2 all
    #: report through 0x08, IOM3..5 through 0x10, and IOS/UART0/UART1/SCARD all
    #: through 0x04.
    #:
    #: Mirroring DEVPWREN into DEVPWRSTATUS, which is what this model used to do,
    #: happens to satisfy most of them by coincidence: MSPI wants status bit 0x40
    #: and gets it because IOM5's *enable* bit is also 0x40. The BLE controller is
    #: the one it cannot fake -- it wants status 0x100 from enable 0x2000, and
    #: 0x100 would need UART1 powered, which this firmware never does. So
    #: ``am_hal_pwrctrl_periph_enable(AM_HAL_PWRCTRL_PERIPH_BLEL)`` timed out,
    #: ``am_hal_ble_power_control`` bailed at 0x0008A3AC, and the radio was never
    #: brought up. See tests/test_ble_powerup.py.
    DEVPWREN_TO_STATUS = {
        0x0001: 0x0004,  # 1  IOS
        0x0002: 0x0008,  # 2  IOM0
        0x0004: 0x0008,  # 3  IOM1
        0x0008: 0x0008,  # 4  IOM2
        0x0010: 0x0010,  # 5  IOM3
        0x0020: 0x0010,  # 6  IOM4
        0x0040: 0x0010,  # 7  IOM5
        0x0080: 0x0004,  # 8  UART0
        0x0100: 0x0004,  # 9  UART1
        0x0200: 0x0020,  # 10 ADC
        0x0400: 0x0004,  # 11 SCARD
        0x0800: 0x0040,  # 12 MSPI
        0x1000: 0x0080,  # 13 PDM
        0x2000: 0x0100,  # 14 BLEL
    }

    #: Status bit 0x100 is BLEL's and nothing else's, so withholding it is
    #: exactly "the BLE controller never reports up" -- which is the behaviour
    #: this model had before the mapping above was fixed. ``Apollo3Machine(
    #: ble=False)`` clears this to get it back: without a controller behind the
    #: BLEIF FIFO the firmware's HCI transport spins in an interrupt-masked
    #: retry for ~100M instructions, which is faithful but costs about 2.1x on a
    #: boot and leaves far less idle time for anything measuring it. Tests that are not
    #: about the radio turn it off. See tests/test_ble_powerup.py.
    ble_controller = True

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == 0x004:  # SUPPLYSTATUS: running from the simo buck, BLE buck on
            return 0x00000003
        if offset == 0x014:  # MEMPWRSTATUS mirrors MEMPWREN
            return self.storage.get(0x010, 0xFFFFFFFF)
        if offset == 0x018:
            # DEVPWRSTATUS: every enabled peripheral reports up, through the
            # status bit the firmware's own table assigns it.
            enabled = self.storage.get(0x008, 0)
            status = 0
            for enable_bit, status_bit in self.DEVPWREN_TO_STATUS.items():
                if enabled & enable_bit:
                    status |= status_bit
            if not self.ble_controller:
                status &= ~self.DEVPWREN_TO_STATUS[0x2000]
            return status
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

    def next_deadline(self) -> Optional[int]:
        """Cycles until the counters next move.

        The next 32.768 kHz tick, deliberately not "the next compare crossing":
        ``STTMR`` is readable and every ``am_util_delay_*`` in this firmware
        spins on it, so a slice that ran past a tick without advancing would
        hand a delay loop a stale counter and let it overshoot. Stopping on the
        tick keeps the counter and the compares both exact, and 1464 cycles is
        still 5.7x the fixed quantum it replaces.
        """
        return self.CYCLES_PER_TICK - self._acc

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
            # Enabling over an already-latched status makes an interrupt
            # deliverable now, which no deadline could have predicted.
            self.stim_int_enable = value
            if self.machine is not None:
                self.machine.cut_slice()
            return
        if offset == self.STMINTCLR:
            self.stim_int_status &= ~value & 0xFFFFFFFF
            return
        if offset == self.STMINTSET:
            self.stim_int_status |= value
            if self.machine is not None:
                self.machine.cut_slice()
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

    #: PADREG A..M, one byte per pad: PULL@0, INPEN@1, STRNG@2, FNCSEL@3..5.
    PADREG_BASE = 0x000
    #: CFG A..H, one nibble per pin: INCFG@0, OUTCFG@1..2, INTD@3.
    #: INTD picks the edge the pin interrupts on -- 0 low-to-high, 1 high-to-low.
    CFG_BASE = 0x040

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
        #: Pins an attached device is actively holding low, per bank.
        #:
        #: An open-drain bus -- I2C -- works by every participant either
        #: releasing the line or pulling it to ground, and a pull always wins.
        #: ``inputs`` cannot express that, because it is OR-ed with the output:
        #: once the firmware drives a pin high nothing could contradict it, so a
        #: bit-banged slave could never ACK.
        self.pulled_low = [0, 0]
        #: Pins with a pull-up on the board, per bank: the lines of a bit-banged
        #: I2C bus. Such a pin reads high whenever its own pad is not driving it
        #: -- configured as an input, or open-drain and released -- unless
        #: something pulls it low. Without it, a master that drove its own ACK
        #: low and then turned SDA into an input to read the next byte went on
        #: reading its stale output bit: every 1 the slave sent arrived as 0
        #: (the PAH8011's FIFO came through as its first byte and zeros, #87).
        self.pulled_up = [0, 0]
        #: Callables notified as ``(bank, before, after)`` when an output
        #: register changes, so a device wired to a pin can react to the edge
        #: itself. Bit-banging is far finer-grained than the timer tick, so a
        #: polled model would miss most of the waveform.
        self.output_watchers: list = []

    def _set_out(self, bank: int, value: int) -> None:
        before = self.out[bank]
        if before == value:
            return
        self.out[bank] = value
        for watcher in self.output_watchers:
            watcher(bank, before, value)

    def pull_low(self, number: int, held: bool) -> None:
        """Hold *number* at ground (or release it), as an open-drain slave does."""
        bank, bit = divmod(number, 32)
        if held:
            self.pulled_low[bank] |= 1 << bit
        else:
            self.pulled_low[bank] &= ~(1 << bit)

    def pull_up(self, number: int) -> None:
        """Give *number* a board pull-up (see :attr:`pulled_up`)."""
        bank, bit = divmod(number, 32)
        self.pulled_up[bank] |= 1 << bit

    def _released(self, bank: int) -> int:
        """Pulled-up pins of *bank* that their own pad is not driving now.

        CFG holds a nibble per pin with OUTCFG in bits 1..2: 0 output disabled,
        1 push-pull, 2 open drain (drives only a 0), 3 tristate (drives when
        enabled in EN).
        """
        released = 0
        pins = self.pulled_up[bank]
        while pins:
            bit = (pins & -pins).bit_length() - 1
            pins &= pins - 1
            number = bank * 32 + bit
            cfg = self.storage.get(self.CFG_BASE + (number // 8) * 4, 0)
            outcfg = (cfg >> ((number % 8) * 4 + 1)) & 3
            driving_one = (self.out[bank] >> bit) & 1
            enabled = (self.storage.get(0x0A0 + 4 * bank, 0) >> bit) & 1
            if (outcfg == 0 or (outcfg == 2 and driving_one)
                    or (outcfg == 3 and not enabled)):
                released |= 1 << bit
        return released

    def _levels(self, bank: int) -> int:
        """Every pin of *bank* as a reader sees it."""
        released = self._released(bank) if self.pulled_up[bank] else 0
        driven = self.out[bank] & ~released
        return ((driven | released | self.inputs[bank]) & ~self.pulled_low[bank]) & 0xFFFFFFFF

    def level(self, number: int) -> int:
        """The level a reader would actually see on *number*."""
        bank, bit = divmod(number, 32)
        return (self._levels(bank) >> bit) & 1

    def pin(self, number: int) -> int:
        """Current output level of pin *number* (0-63)."""
        return (self.out[number // 32] >> (number % 32)) & 1

    def interrupt_edge(self, number: int) -> int:
        """0 if *number* interrupts low-to-high, 1 if high-to-low (INTD)."""
        cfg = self.storage.get(self.CFG_BASE + (number // 8) * 4, 0)
        return (cfg >> ((number % 8) * 4 + 3)) & 1

    def resting_level(self, number: int) -> int:
        """The level *number* sits at when nothing is pressing it.

        A line that interrupts on the falling edge has to rest high to have an
        edge to fall from, and none of this board's interrupt pins enable the
        chip's internal pull-up, so the resistor is on the board. Reading that
        back out of the firmware's own pad configuration is the only evidence
        there is for which way round each button is wired.
        """
        return self.interrupt_edge(number)

    def interrupt_pins(self) -> list:
        """Pins the firmware has enabled a GPIO interrupt on."""
        out = []
        for bank in (0, 1):
            enabled = self.storage.get(self.INT_EN[bank], 0)
            out.extend(bank * 32 + bit for bit in range(32) if (enabled >> bit) & 1)
        return out

    def raise_interrupt(self, number: int) -> None:
        """Latch an edge on *number*, as a button press or sensor line would.

        Everything the watch reacts to besides the clock arrives this way — the
        crown, the side button, the touch panel and the accelerometer all signal
        through a GPIO edge, so without this the emulated watch can never receive
        any input at all.
        """
        bank, bit = divmod(number, 32)
        self.int_status[bank] |= 1 << bit
        # An edge can be latched from inside a register access — a device
        # re-asserting its line during an I2C read, say — and then it is not on
        # any clock, so no deadline saw it coming.
        if self.machine is not None:
            self.machine.cut_slice()

    def assert_irq(self, number: int, asserted: bool) -> None:
        """Drive *number* to its active or resting level and latch the edge.

        Which level is "active" is the pin's own business: a line the firmware
        set to interrupt on the falling edge rests high on a board pull-up and
        is pulled down to signal. Every device that signals through GPIO goes
        through here so the polarity is decided in one place from the firmware's
        own configuration, rather than each model guessing.
        """
        resting = self.resting_level(number)
        self.set_input(number, (1 - resting) if asserted else resting)
        self.raise_interrupt(number)

    def release_irq(self, number: int) -> None:
        """Return *number* to its resting level without latching anything.

        For a device whose line only ever signals one way: going back to rest is
        the edge the firmware did not ask for. :meth:`assert_irq` latches on
        release as well, which the touch panel needs (its lift is a report of its
        own) and the accelerometer must not have (#87).
        """
        self.set_input(number, self.resting_level(number))

    def set_input(self, number: int, level: int) -> None:
        bank, bit = divmod(number, 32)
        if level:
            self.inputs[bank] |= 1 << bit
        else:
            self.inputs[bank] &= ~(1 << bit)

    #: How often an asserting GPIO is re-examined, in core cycles. Same number
    #: and same reason as Mspi.CQ_PUMP_INTERVAL: this source is level-sensitive,
    #: so how often it is looked at decides how many times it re-pends, and that
    #: rate is part of the model rather than a free parameter.
    POLL_INTERVAL = 256

    def next_deadline(self) -> Optional[int]:
        """When an asserting pin needs looking at again, or None if none is.

        Edges arrive from things that do have deadlines — the panel's TE line,
        the accelerometer's sample clock, scheduled touch input — or from inside
        a register access, which calls :meth:`raise_interrupt`. What needs a rate
        rather than a deadline is the *level* afterwards.
        """
        for bank in (0, 1):
            if self.int_status[bank] & self.storage.get(self.INT_EN[bank], 0):
                return self.POLL_INTERVAL
        return None

    def pending_irqs(self) -> list[int]:
        for bank in (0, 1):
            if self.int_status[bank] & self.storage.get(self.INT_EN[bank], 0):
                return [IRQ_GPIO]
        return []

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == self.RDA:
            return self._levels(0)
        if offset == self.RDB:
            return self._levels(1)
        if offset in self.INT_STAT:
            return self.int_status[self.INT_STAT.index(offset)]
        return self.storage.get(offset, 0)

    def write(self, offset: int, size: int, value: int) -> None:
        self.writes[offset] += 1
        if offset in (self.WTA, self.WTB):
            self._set_out(0 if offset == self.WTA else 1, value & 0xFFFFFFFF)
            return
        if offset in (self.WTSA, self.WTSB):
            bank = 0 if offset == self.WTSA else 1
            self._set_out(bank, self.out[bank] | (value & 0xFFFFFFFF))
            return
        if offset in (self.WTCA, self.WTCB):
            bank = 0 if offset == self.WTCA else 1
            self._set_out(bank, self.out[bank] & ~value & 0xFFFFFFFF)
            return
        if offset in self.INT_CLR:
            # Write-one-to-clear. Without this the handler can never dismiss the
            # interrupt and the first edge becomes a permanent storm.
            self.int_status[self.INT_CLR.index(offset)] &= ~value & 0xFFFFFFFF
            return
        if offset in self.INT_SET:
            self.int_status[self.INT_SET.index(offset)] |= value & 0xFFFFFFFF
            if self.machine is not None:
                self.machine.cut_slice()
            return
        self.storage[offset] = value
        if offset in self.INT_EN and self.machine is not None:
            # Enabling an interrupt whose edge is already latched makes it
            # deliverable at once, and nothing on the clock changed to say so.
            self.machine.cut_slice()


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

    #: A device whose reads have side effects -- a FIFO that pops -- defines
    #: ``read_byte(register) -> int`` instead, and the bit-banged bus then asks
    #: for each byte as the master clocks it out, with the register advanced by
    #: one each time, rather than handing over a run the master may not take.
    read_byte = None


class BitBangI2cBus:
    """An I2C bus the firmware clocks by hand on two GPIO pins.

    Not everything on this watch hangs off an IOM. ``ew_drv_sim_i2c.c`` drives
    SCL and SDA directly, and the battery gauge is on that bus — which is why it
    was invisible for so long: all six IOMs stay idle, so there was nothing to
    notice. Reading GPIO was simply the hottest MMIO in the whole run.

    The model is a slave-side state machine. It watches the two pins change,
    recognises START and STOP, shifts bits in on each rising clock edge, and
    pulls SDA low to ACK or to return a byte. That is done at the pin level
    rather than by intercepting the driver, so the firmware's own timing,
    clock stretching and bus scanning all behave the way they would on the part.
    """

    #: Bus states.
    IDLE, ADDRESS, REGISTER, WRITING, READING = range(5)

    def __init__(self, gpio, scl: int, sda: int) -> None:
        self.gpio = gpio
        self.scl = scl
        self.sda = sda
        self.devices: dict[int, I2cDevice] = {}
        self.transactions = 0
        self.unanswered: collections.Counter = collections.Counter()
        # I2C needs its pull-ups; the board has them.
        gpio.pull_up(scl)
        gpio.pull_up(sda)
        self._reset()
        gpio.output_watchers.append(self._on_output_change)

    def _reset(self) -> None:
        self.state = self.IDLE
        self.bits: list[int] = []
        self.address = None
        self.reading = False
        self.payload = bytearray()
        self._tx = bytearray()
        #: The device being read, when it streams (``I2cDevice.read_byte``).
        self._streaming = None
        #: The master NAKed the last byte it read: send nothing more.
        self._master_nak = False
        #: The next rising clock is the acknowledge slot, not a data bit.
        self._ack_slot = False
        #: We are currently holding SDA through that slot.
        self._acking = False
        #: Whether this slave should acknowledge; False is a NAK.
        self._owes_ack = False
        # NOT self.register: the address pointer persists across STOP, which is
        # how these parts behave and how the firmware reads them -- it sets the
        # register in one transaction and reads in the next.
        if not hasattr(self, "register"):
            self.register = None
        self.gpio.pull_low(self.sda, False)

    # ── pin-level decoding ──────────────────────────────────────────────────

    def _on_output_change(self, bank: int, before: int, after: int) -> None:
        changed = before ^ after
        scl_bank, scl_bit = divmod(self.scl, 32)
        sda_bank, sda_bit = divmod(self.sda, 32)
        if bank == sda_bank and changed >> sda_bit & 1:
            self._sda_edge(after >> sda_bit & 1)
        if bank == scl_bank and changed >> scl_bit & 1:
            self._scl_edge(after >> scl_bit & 1)

    def _line(self, pin: int) -> int:
        bank, bit = divmod(pin, 32)
        return (self.gpio.out[bank] >> bit) & 1

    def _sda_edge(self, level: int) -> None:
        if self._line(self.scl) != 1:
            return                       # data only moves while the clock is low
        if level == 0:
            self._start()                # START, or a repeated START
        else:
            self._stop()

    def _start(self) -> None:
        self._flush_write()
        self.state = self.ADDRESS
        self.bits = []
        self.reading = False
        self.gpio.pull_low(self.sda, False)

    def _stop(self) -> None:
        self._flush_write()
        self._reset()

    def _flush_write(self) -> None:
        """Hand a completed write to its device."""
        # A zero-length payload means the master only set the address pointer
        # and then issued a repeated START to read -- there is nothing to write.
        if self.state == self.WRITING and self.address is not None and self.payload:
            device = self.devices.get(self.address)
            if device is not None and self.register is not None:
                device.write(self.register, bytes(self.payload))
        self.payload = bytearray()

    def _scl_edge(self, level: int) -> None:
        if self.state == self.IDLE:
            return
        if level:
            self._sample()
        else:
            self._drive()

    def _sample(self) -> None:
        """Rising clock: latch whatever the master is presenting.

        Every ninth pulse is the acknowledge slot, not data. Counting it as a
        bit shifts every byte one place right, which is subtle enough to look
        like a plausible register map -- 0x08 arrives as 4, 0x5F as 0x17.
        """
        if self._ack_slot:
            if self.state == self.READING and self._acking and not self._owes_ack:
                # The master's own acknowledge, after a byte it read: low to
                # ask for another, high (NAK) for "that was the last". A slave
                # that went on fetching would pop a byte nobody reads, which
                # for a FIFO is a byte lost -- every burst after it misaligned.
                # Only on the ninth clock (``_acking``): the flag goes up as
                # the eighth data bit is presented, and the rising edge after
                # that is the master sampling the data bit, not acknowledging.
                self._master_nak = self.gpio.level(self.sda) == 1
            return
        if self.state == self.READING:
            return
        self.bits.append(self.gpio.level(self.sda))
        if len(self.bits) < 8:
            return
        byte = int("".join(str(b) for b in self.bits), 2)
        self.bits = []
        if self.state == self.ADDRESS:
            self.address = byte >> 1
            self.reading = bool(byte & 1)
            device = self.devices.get(self.address)
            if device is None:
                self.unanswered[self.address] += 1
                self.state = self.IDLE
                self._owes_ack = False          # NAK: nobody is there
                self._ack_slot = True
                return
            self.transactions += 1
            if self.reading:
                # A master reads as many bytes as it likes and NAKs the last
                # one, so hand over a generous run and let it stop where it will
                # -- or, for a device that streams, nothing until it is clocked.
                self._streaming = device if device.read_byte is not None else None
                self._master_nak = False
                self._tx = (bytearray() if self._streaming is not None
                            else bytearray(device.read(self.register or 0, 32)))
                self.state = self.READING
            else:
                self.state = self.REGISTER
        elif self.state == self.REGISTER:
            self.register = byte
            self.state = self.WRITING
        elif self.state == self.WRITING:
            self.payload.append(byte)
        self._owes_ack = True
        self._ack_slot = True

    def _drive(self) -> None:
        """Falling clock: present the ACK, or the next bit of a read."""
        if self._ack_slot and not self._acking:
            self._acking = True
            self.gpio.pull_low(self.sda, self._owes_ack)
            return
        if self._acking:
            self._acking = False
            self._ack_slot = False
            self.gpio.pull_low(self.sda, False)
        if self.state == self.READING:
            if not self.bits:
                if self._master_nak:
                    self.gpio.pull_low(self.sda, False)
                    return
                if self._streaming is not None:
                    self._tx = bytearray([self._streaming.read_byte(self.register or 0) & 0xFF])
                if not self._tx:
                    self._tx = bytearray(b"\xff")
                byte = self._tx.pop(0)
                self.bits = [(byte >> i) & 1 for i in range(7, -1, -1)]
                self.register = ((self.register or 0) + 1) & 0xFF
            self.gpio.pull_low(self.sda, self.bits.pop(0) == 0)
            if not self.bits:
                # The master drives the acknowledge after a read, so release.
                self._owes_ack = False
                self._ack_slot = True


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

    #: How often the controller is given a chance to walk its queue, in core
    #: cycles. Tried at 512 while looking for the cost of the factory resources
    #: (#72) and rejected again: the goldens move, one MSPI interrupt and one
    #: STIMER tick either way, which is the emulated timing changing rather than
    #: a measurement wobbling. This is the historical poll rate — the fixed quantum every
    #: measurement was taken at — kept as a number here because polling *is* the
    #: model: :meth:`pending_irqs` pumps the queue, so its frequency decides how
    #: fast pixels reach the panel. At one poll every 16384 cycles a boot loses
    #: two frames and three NAND pages, which is what pinned this down.
    CQ_PUMP_INTERVAL = 256

    def next_deadline(self) -> Optional[int]:
        """When the command queue next needs a turn, or None if it has no work.

        The saving is not in pumping less often while the controller is busy —
        that would drop frames — it is in not pumping at all when the queue is
        caught up, which is most of a boot.
        """
        if self.int_status & self.storage.get(self.INTEN, 0):
            # Tempting to return None here when the exception is already latched
            # in the NVIC -- the run loop will deliver it the moment the core
            # allows, and during the BLE retry spin this poll wins the deadline
            # 135,396 times out of 162,827 while the core is masked throughout,
            # so skipping it is worth 1.14x. It was tried and rejected: the pump
            # this poll performs is not idle work. Without it a radio-off boot
            # moves from 12,040 STIMER interrupts to 12,043, and --idle-skip
            # lands on 12,039 -- so the two stop agreeing, which is the one
            # property that switch has to keep.
            return self.CQ_PUMP_INTERVAL
        if not self.storage.get(self.CQCFG, 0) & self.CQ_ENABLE:
            return None
        cur = self.storage.get(self.CQCURIDX, 0) & self.CQ_INDEX_MASK
        end = self.storage.get(self.CQENDIDX, 0) & self.CQ_INDEX_MASK
        return self.CQ_PUMP_INTERVAL if cur != end else None

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


class BleController:
    """The other side of the BLEIF: an on-die BLE controller speaking H4 HCI.

    The firmware runs a Cordio host and this is what it talks to. Packets the
    firmware sends arrive at :meth:`from_host`; packets for the firmware are
    queued with :meth:`to_host` and show up to it as "the controller has
    something" on BSTATUS bit 7.

    This base class is the seam, not a radio: it records what the host sends
    and hands back whatever was queued. A real stack (a bumble ``Controller``
    on a ``LocalLink``) subclasses it -- that is the rest of task #25.
    """

    def __init__(self) -> None:
        #: Complete transfers the firmware has sent, in order.
        self.sent: list = []
        self._inbound = bytearray()

    def from_host(self, packet: bytes) -> None:
        """One complete transfer's worth of bytes from the firmware."""
        self.sent.append(bytes(packet))

    def to_host(self, data: bytes) -> None:
        """Queue bytes for the firmware to read."""
        self._inbound.extend(data)

    def has_data(self) -> bool:
        return bool(self._inbound)

    def available(self) -> int:
        return len(self._inbound)

    def take(self, count: int) -> bytes:
        out = bytes(self._inbound[:count])
        del self._inbound[: len(out)]
        return out


class Bleif(Peripheral):
    """The Apollo3's interface to its on-die BLE controller.

    The controller is a separate core reached over this IOM-like block: the HAL
    powers it up through ``BLECFG``/``PWRCMD``, waits on ``BSTATUS``, and then
    exchanges HCI packets through the FIFO. Everything the firmware's Cordio
    host says to the radio passes through here, so this is the seam where the
    emulated watch's BLE traffic can be taken out to a real host stack.

    Every field below was read off the firmware's own transport rather than a
    datasheet, because the two disagree about where BSTATUS even lives:

    * ``am_hal_ble_power_control`` (0x0008A340) brings the controller up and
      spins at 0x0008A422 until BSTATUS bits [10:8] read 3.
    * ``am_hal_ble_wakeup_set`` (0x0008A6B0) drives PWRCMD bits [3:2]: 3 to
      wake the controller, 2 to let it sleep.
    * The send path (0x000891F0) wakes it and then waits up to 300 x 160us at
      0x00089590 for **BSTATUS bit 3** -- the controller answering "awake, SPI
      ready". 0x0008966E/0x00089672 then write OFFSETHI and CMD, and the loop
      at 0x0008931E pushes one word at a time into FIFOPUSH for as long as
      **FIFOPTR bits [15:8]** (free space in the write FIFO) is at least 4,
      finishing on **INTSTAT bit 0**.
    * The receive path waits at 0x00089C92 for **BSTATUS bit 7** -- the
      controller saying it has something -- and then reads five bytes. Its
      drain loop at 0x000899B0 takes the available byte count from **FIFOPTR
      bits [23:16]** and pops words from FIFOPOP at 0x00089A10.
    * Something the firmware did not ask for -- a connection, data from the
      phone -- reaches it through **IRQ 12**. ``HciDrvRadioBoot`` ends
      (0x0004704E) with INTCLR 0x281, INTEN 0x281 and ``NVIC_EnableIRQ(12)``
      inlined at 0x00047066; the ISR (0x000889CC, via 0x00046EE8) reads
      INTSTAT & INTEN, clears it through INTCLR, and wakes the HCI driver's
      task with ``WsfSetEvent``. Bit 7 of that mask is **BLECIRQ**, the
      interrupt form of BSTATUS bit 7, and the blocking transfers mask it
      (INTEN 0x201 at 0x00089274) so that a reply they are about to poll for
      does not also interrupt.

    With no controller attached the two handshake bits stay clear, which is the
    truthful answer -- there is nothing on the other side -- and is what this
    model did before, so a bare machine and ``--no-ble`` behave as they did.
    """

    name = "BLEIF"
    FIFO = 0x000
    FIFOPTR = 0x100
    FIFOTHR = 0x104
    FIFOPOP = 0x108
    FIFOPUSH = 0x10C
    FIFOCTRL = 0x110
    CLKCFG = 0x200
    #: [4:0] direction, [19:8] TSIZE. 0x00000601 is the first thing the
    #: firmware ever sends: write, six bytes.
    CMD = 0x20C
    CMD_WRITE = 1
    CMD_READ = 2
    CMDSTAT = 0x218
    INTEN = 0x220
    INTSTAT = 0x224
    INTCLR = 0x228
    BLECFG = 0x300
    PWRCMD = 0x304
    #: **0x30C, not 0x308.** Every site that reads the controller's state uses
    #: +0x30C off the 0x5000C000 base literal: 0x0008A422 tests bits [10:8] for
    #: B2M_STATE == 3, 0x00089590 tests bit 3, and 0x0008923A / 0x00089C92 test
    #: bit 7. Nothing in the image was seen to read 0x308, so it is left
    #: unnamed rather than guessed at.
    BSTATUS = 0x30C

    #: INTSTAT bit 0. Both the send loop (0x0008938E) and the receive path wait
    #: on it to mean "the transfer you asked for is done".
    INT_CMDCMP = 1 << 0

    #: INTSTAT bit 8: the same "controller is awake and ready" that BSTATUS
    #: bit 3 carries, in the form the firmware switches to once it is running.
    #:
    #: 0x000895A4 is the readiness check, and it has two halves: while the
    #: handle byte at +0x40 is zero it reads BSTATUS bit 3 (0x00089590), and
    #: once that byte is set it reads **INTSTAT bit 8** instead (0x000895C6).
    #: The byte is set at 0x0008A076, right after the last step of controller
    #: bring-up -- so every check before that point goes through BSTATUS and
    #: every check afterwards through INTSTAT. Model only the first and the
    #: firmware gets all the way up and then decides its controller has gone
    #: away, which it answers by restarting the whole download.
    INT_CONTROLLER_READY = 1 << 8

    #: INTSTAT bit 7, BLECIRQ: the controller has raised its line because it
    #: has something to say. Latched on the rising edge of "has data" and
    #: cleared by INTCLR, unlike bit 8, which is a level: the ISR clears what
    #: it saw *before* the task gets round to reading, and a level here would
    #: re-enter the ISR until it did. INTEN 0x281 at 0x00047062 enables it
    #: alongside CMDCMP (bit 0) and DCMP (bit 9); IRQ 12 is what carries it.
    INT_BLECIRQ = 1 << 7

    #: PWRCMD bits [3:2] == 3 is the firmware asking the controller to wake, 2
    #: lets it sleep again (``am_hal_ble_wakeup_set``, 0x0008A6B0).
    PWRCMD_WAKE_MASK = 0xC
    PWRCMD_WAKE = 0xC

    #: BSTATUS bits, each confirmed at the sites named in the class docstring.
    BSTATUS_SPI_READY = 1 << 3
    BSTATUS_HAS_DATA = 1 << 7
    BSTATUS_B2M_ACTIVE = 3 << 8

    #: The firmware sets FIFOTHR to 0x2020 at 0x000896FA -- one threshold of 32
    #: per direction, which is what pins the FIFO depth.
    FIFO_DEPTH = 32

    REGS = {
        0x000: "FIFO", 0x100: "FIFOPTR", 0x104: "FIFOTHR", 0x108: "FIFOPOP",
        0x10C: "FIFOPUSH", 0x110: "FIFOCTRL", 0x114: "FIFOLOC", 0x200: "CLKCFG",
        0x20C: "CMD", 0x210: "CMDRPT", 0x214: "OFFSETHI", 0x218: "CMDSTAT",
        0x220: "INTEN", 0x224: "INTSTAT", 0x228: "INTCLR", 0x22C: "INTSET",
        0x240: "DMATRIGEN", 0x244: "DMATRIGSTAT", 0x250: "DMACFG",
        0x254: "DMATOTCOUNT", 0x258: "DMATARGADDR", 0x25C: "DMASTAT",
        0x300: "BLECFG", 0x304: "PWRCMD", 0x30C: "BSTATUS", 0x310: "BLEDBG",
    }

    def __init__(self, base: int, size: int, machine=None) -> None:
        super().__init__(base, size, machine)
        #: The controller on the other side, or None for "nothing is fitted".
        self.controller = None
        #: Everything the firmware has pushed, kept whole for triage.
        self.tx = bytearray()
        #: The read FIFO, filled from the controller when a read is commanded.
        self.rx = bytearray()
        self.commands = 0
        self.packets_sent = 0
        self.packets_received = 0
        self.int_status = 0
        self.interrupts_raised = 0
        self._had_data = False
        self._direction = 0
        self._tsize = 0
        self._moved = 0
        self._outbound = bytearray()

    # -- the state the firmware polls ----------------------------------------

    @property
    def awake(self) -> bool:
        """Whether the firmware currently has the controller woken."""
        return (self.storage.get(self.PWRCMD, 0)
                & self.PWRCMD_WAKE_MASK) == self.PWRCMD_WAKE

    def _has_data(self) -> bool:
        """Whether the controller has something for the firmware, latching
        BLECIRQ on the way up. Every path that looks comes through here, so
        an arrival is noticed whichever of them looks first."""
        has = bool(self.rx) or (self.controller is not None
                                and self.controller.has_data())
        if has and not self._had_data:
            self.int_status |= self.INT_BLECIRQ
        self._had_data = has
        return has

    def _bstatus(self) -> int:
        status = self.BSTATUS_B2M_ACTIVE
        if self.controller is None:
            # Nothing on the other side, and saying otherwise would send the
            # transport after a packet that does not exist.
            return status
        if self.awake:
            status |= self.BSTATUS_SPI_READY
        if self._has_data():
            status |= self.BSTATUS_HAS_DATA
        return status

    def pending_irqs(self) -> list:
        """IRQ 12 while anything enabled in INTEN is set in INTSTAT.

        Level-sensitive like the GPIO block's: the ISR takes it down through
        INTCLR. The arrival that matters is polled here rather than pushed,
        because a bumble controller queues its events from another thread and
        this is the machine's thread.
        """
        self._has_data()
        if self.int_status & self.storage.get(self.INTEN, 0):
            self.interrupts_raised += 1
            return [IRQ_BLE]
        return []

    def _fifoptr(self) -> int:
        # [23:16] is what the receive drain reads as "bytes available"; [15:8]
        # is what the send loop reads as "room to push". The controller takes
        # bytes as fast as they are pushed, so the write side is always clear.
        #
        # The count is rounded up to a whole word, because the FIFO holds words
        # and the drain loop refuses to move at all below four bytes
        # (0x000899C2). The firmware really does ask for two-byte reads -- the
        # post-download announcement at 0x00089F64 is one -- and reporting two
        # there deadlocks it against its own threshold.
        available = (len(self.rx) + 3) & ~3
        return ((available & 0xFF) << 16) | ((self.FIFO_DEPTH & 0xFF) << 8)

    # -- registers -----------------------------------------------------------

    def read(self, offset: int, size: int) -> int:
        self.reads[offset] += 1
        if offset == self.BSTATUS:
            return self._bstatus()
        if offset == self.CMDSTAT:
            return 0x00000004  # idle, last command complete
        if offset == self.INTSTAT:
            # Bit 8 is a level, not a latch: it says the controller is awake,
            # which is the same thing BSTATUS bit 3 says, so it is recomputed
            # rather than remembered. Letting INTCLR knock it down would strand
            # the firmware the moment it switched over to reading this one.
            ready = self.controller is not None and self.awake
            self._has_data()
            return self.int_status | (self.INT_CONTROLLER_READY if ready else 0)
        if offset == self.FIFOPTR:
            return self._fifoptr()
        if offset in (self.FIFO, self.FIFOPOP):
            return self._pop()
        return self.storage.get(offset, 0)

    def write(self, offset: int, size: int, value: int) -> None:
        self.writes[offset] += 1
        if offset in (self.FIFO, self.FIFOPUSH):
            self._push(value)
            return
        if offset == self.INTCLR:
            self.int_status &= ~value
            return
        if offset == self.CMD:
            self.commands += 1
            self._begin(value)
        self.storage[offset] = value

    # -- transfers -----------------------------------------------------------

    def _begin(self, command: int) -> None:
        """A CMD write starts a transfer: direction in [4:0], size in [19:8]."""
        self._direction = command & 0x1F
        self._tsize = (command >> 8) & 0xFFF
        self._moved = 0
        self._outbound.clear()
        self.int_status &= ~self.INT_CMDCMP
        if self._direction == self.CMD_READ and self.controller is not None:
            # The controller presents the bytes; the firmware pops them out.
            #
            # A read clocks TSIZE bytes off the bus whether or not the
            # controller has that many to give, so short answers are padded
            # rather than left short. The firmware relies on it: it reads an
            # HCI event in a fixed nine-byte window, and a seven-byte Command
            # Complete delivered as seven bytes leaves its drain loop
            # (0x000899B0) waiting for two more that are never coming.
            data = self.controller.take(self._tsize)
            self.rx.extend(data.ljust(self._tsize, b"\x00"))

    def _push(self, word: int) -> None:
        """One 32-bit word into the write FIFO (0x00089346).

        The last word of a transfer is partial whenever TSIZE is not a multiple
        of four -- the very first packet is six bytes, pushed as two words --
        so the tail is trimmed to what was actually asked for.
        """
        remaining = self._tsize - self._moved
        chunk = word.to_bytes(4, "little")[: max(0, min(4, remaining))]
        self._outbound.extend(chunk)
        self.tx.extend(chunk)
        self._moved += 4
        if self._tsize and self._moved >= self._tsize:
            self.int_status |= self.INT_CMDCMP
            if self.controller is not None:
                self.controller.from_host(bytes(self._outbound))
                self.packets_sent += 1
            self._outbound.clear()

    def _pop(self) -> int:
        """One 32-bit word out of the read FIFO (0x00089A10)."""
        word = self.rx[:4]
        del self.rx[: len(word)]
        self._moved += 4
        if self._tsize and self._moved >= self._tsize:
            self.int_status |= self.INT_CMDCMP
            self.packets_received += 1
            # The line drops here if that was everything, so that the next
            # arrival is a fresh edge even if nobody polls in between.
            self._has_data()
        return int.from_bytes(bytes(word).ljust(4, b"\x00"), "little")

    def summary(self) -> str:
        fitted = ("no controller" if self.controller is None else
                  f"{self.packets_sent} sent, {self.packets_received} received")
        return (
            f"  BLEIF: {self.commands} commands, {len(self.tx)} bytes to the "
            f"controller ({fitted}), {sum(self.reads.values())} reads, "
            f"IRQ asserted {self.interrupts_raised}x"
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

    #: Every peripheral here is a 4 KB block, so the page number is enough to
    #: pick one out. Nothing depends on that being true — a peripheral of any
    #: size claims every page it touches, and the bounds are still checked.
    _PAGE_SHIFT = 12

    def __init__(self, machine=None) -> None:
        self.machine = machine
        self.peripherals: list[Peripheral] = []
        self.by_base: dict[int, Peripheral] = {}
        self._by_page: dict[int, Peripheral] = {}
        self._shared_pages: set[int] = set()
        self.unknown_reads = collections.Counter()
        self.unknown_writes = collections.Counter()
        self.unknown_storage: dict[int, int] = {}
        #: absolute address -> (value, the owner's read counter, offset), for
        #: registers whose answer is fixed (:attr:`Peripheral.CONSTANT_REGS`).
        #: Served here rather than dispatched, because at two million reads a
        #: boot the dispatch is the cost, not the answer (#72).
        self._constant: dict[int, tuple] = {}

    def add(self, peripheral: Peripheral) -> Peripheral:
        self.peripherals.append(peripheral)
        self.by_base[peripheral.base] = peripheral
        for offset, value in peripheral.CONSTANT_REGS.items():
            # setdefault for the same reason _by_page uses it: where two models
            # cover one address the first added wins, and the fast path has to
            # answer for the same one find() would have chosen.
            self._constant.setdefault(peripheral.base + offset,
                                      (value, peripheral.reads, offset))
        first = peripheral.base >> self._PAGE_SHIFT
        last = (peripheral.base + peripheral.size - 1) >> self._PAGE_SHIFT
        for page in range(first, last + 1):
            # setdefault, not assignment: the scan this replaces returned the
            # first peripheral added that covered the address, so where two
            # models share a page the first still wins — and the page is marked
            # so a miss on it falls back to the scan instead of reporting a gap.
            if self._by_page.setdefault(page, peripheral) is not peripheral:
                self._shared_pages.add(page)
        return peripheral

    def find(self, addr: int) -> Optional[Peripheral]:
        """Which peripheral owns this address, or None.

        Called on every MMIO access — 722k times in a 200M-instruction boot — so
        it is a dict lookup rather than a scan of all twenty-odd peripherals.
        """
        page = addr >> self._PAGE_SHIFT
        p = self._by_page.get(page)
        if p is not None and p.base <= addr < p.base + p.size:
            return p
        if page in self._shared_pages:
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
        # A register whose answer never changes is answered here, without the
        # page lookup and the dispatch into the model. The counter is still kept,
        # so nothing downstream can tell the difference -- see CONSTANT_REGS.
        fixed = self._constant.get(addr)
        if fixed is not None:
            value, reads, offset = fixed
            reads[offset] += 1
            return value
        # The page lookup is inlined rather than calling find(): this runs on
        # every MMIO access, and at this frequency the Python call itself is a
        # measurable share of it. find() is still the fallback, so shared pages
        # and out-of-bounds addresses behave exactly as before.
        p = self._by_page.get(addr >> self._PAGE_SHIFT)
        if p is None or not (p.base <= addr < p.base + p.size):
            p = self.find(addr)
            if p is None:
                self.unknown_reads[addr] += 1
                return self.unknown_storage.get(addr, 0)
        return p.read(addr - p.base, size)

    def write(self, addr: int, size: int, value: int) -> None:
        p = self._by_page.get(addr >> self._PAGE_SHIFT)
        if p is None or not (p.base <= addr < p.base + p.size):
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
    bus.add(Clkgen(0x40004000, 0x200, machine))
    bus.add(Rtc(0x40004200, 0xE00, machine))  # the rest of the page, as before
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
