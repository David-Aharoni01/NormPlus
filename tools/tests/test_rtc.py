"""The RTC: the watch's time of day, and the alarm that ticks once a second (#81).

The firmware keeps the time in the Apollo3's own RTC and nowhere else, and arms its
alarm to repeat every second; with the registers modelled as plain storage the face
stood still after a phone set the time. These drive the model the way the firmware's
HAL does -- the register sequences below are ``am_hal_rtc_time_set`` (0x0008D6A8)
and ``am_hal_rtc_alarm_set`` (0x0008D504) as they appear in the image -- and then
check the firmware itself: a boot sets its default time and takes the alarm once a
second.

Run with:  uv run normtest test_rtc
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus import paths
from normplus.watch.fw import image as image_mod
from normplus.watch.fw.cortexm import IRQ_RTC, exception_name
from normplus.watch.fw.devices import attach_mspi_devices
from normplus.watch.fw.machine import Apollo3Machine
from normplus.watch.fw.peripherals import Rtc

CTRLOW, CTRUP, ALMLOW, ALMUP, RTCCTL = 0x40, 0x44, 0x48, 0x4C, 0x50
INTEN, INTSTAT, INTCLR = 0x100, 0x104, 0x108
SECOND = 100 * Rtc.CYCLES_PER_HUNDREDTH


def bcd(n: int) -> int:
    return ((n // 10) << 4) | (n % 10)


def time_set(rtc: Rtc, year: int, month: int, date: int, hour: int, minute: int,
             second: int, hundredths: int = 0, weekday: int = 0) -> None:
    """What am_hal_rtc_time_set writes: WRTC on, CTRLOW, CTRUP, WRTC off."""
    ctl = rtc.read(RTCCTL, 4)
    rtc.write(RTCCTL, 4, (ctl & ~1) + 1)
    rtc.write(CTRLOW, 4, bcd(hour) << 24 | bcd(minute) << 16 | bcd(second) << 8 | bcd(hundredths))
    rtc.write(CTRUP, 4, (weekday << 24) | (bcd(year) & 0xFF) << 16 | bcd(month) << 8 | bcd(date))
    rtc.write(RTCCTL, 4, rtc.read(RTCCTL, 4) & ~1)


def alarm_set(rtc: Rtc, repeat: int, hundredths: int = 0, second: int = 0, minute: int = 0,
              hour: int = 0, date: int = 0, month: int = 0, weekday: int = 0) -> None:
    """What am_hal_rtc_alarm_set writes: RPT into RTCCTL, then ALMUP, ALMLOW."""
    rtc.write(RTCCTL, 4, rtc.read(RTCCTL, 4) | ((min(repeat, 7) << 1) & 0xE))
    rtc.write(ALMUP, 4, bcd(month) << 8 | (weekday << 16) | bcd(date))
    extra = {8: 0xF0, 9: 0xFF}.get(repeat, 0)
    rtc.write(ALMLOW, 4, bcd(hour) << 24 | bcd(minute) << 16 | bcd(second) << 8
              | (bcd(hundredths) | extra))


def time_get(rtc: Rtc) -> tuple:
    """What am_hal_rtc_time_get decodes, in the order rtc.c's wrapper uses."""
    low, up = rtc.read(CTRLOW, 4), rtc.read(CTRUP, 4)
    dec = lambda b: (b >> 4) * 10 + (b & 0xF)
    return (dec((up >> 16) & 0xFF), dec((up >> 8) & 0x1F), dec(up & 0x3F),
            dec((low >> 24) & 0x3F), dec((low >> 16) & 0x7F), dec((low >> 8) & 0x7F),
            dec(low & 0xFF), (up >> 24) & 7, up >> 31)


def running_rtc() -> Rtc:
    rtc = Rtc(0x40004200, 0xE00)
    rtc.write(RTCCTL, 4, 0)  # RSTOP clear, as am_hal_rtc_osc_enable leaves it
    return rtc


def test_the_time_set_is_the_time_read():
    rtc = running_rtc()
    # rtc.c stores year - 1900, so 2026 is 126 and goes in as 0xC6: not BCD,
    # but what the HAL's own decode turns back into 126.
    time_set(rtc, 126, 10, 4, 22, 11, 7, 50, weekday=0)
    assert rtc.read(CTRUP, 4) == 0x00C61004, hex(rtc.read(CTRUP, 4))
    assert time_get(rtc) == (126, 10, 4, 22, 11, 7, 50, 0, 0)


def test_the_counter_runs_on_the_watch_clock():
    rtc = running_rtc()
    time_set(rtc, 126, 10, 4, 22, 11, 7)
    rtc.advance(61 * SECOND + 3 * Rtc.CYCLES_PER_HUNDREDTH)
    assert time_get(rtc)[3:7] == (22, 12, 8, 3)


def test_the_counter_is_write_protected_without_wrtc():
    rtc = running_rtc()
    time_set(rtc, 126, 10, 4, 22, 11, 7)
    rtc.write(CTRLOW, 4, 0x01020300)
    assert time_get(rtc)[3:6] == (22, 11, 7)


def test_rstop_stops_it():
    rtc = running_rtc()
    time_set(rtc, 126, 10, 4, 22, 11, 7)
    rtc.write(RTCCTL, 4, 0x10)
    rtc.advance(5 * SECOND)
    assert time_get(rtc)[5] == 7
    assert rtc.next_deadline() is None
    rtc.write(RTCCTL, 4, 0)
    rtc.advance(5 * SECOND)
    assert time_get(rtc)[5] == 12


def test_midnight_carries_into_the_date_and_the_weekday():
    rtc = running_rtc()
    # 2028 is a leap year: 28 Feb runs into 29 Feb, and 29 Feb into 1 Mar.
    time_set(rtc, 128, 2, 28, 23, 59, 59, 99, weekday=1)
    rtc.advance(Rtc.CYCLES_PER_HUNDREDTH)
    assert time_get(rtc)[:8] == (128, 2, 29, 0, 0, 0, 0, 2)
    rtc.advance(86_400 * SECOND)
    assert time_get(rtc)[:8] == (128, 3, 1, 0, 0, 0, 0, 3)
    # 2026 is not, and New Year's Eve runs into the next year.
    time_set(rtc, 126, 2, 28, 23, 59, 59, 99, weekday=6)
    rtc.advance(Rtc.CYCLES_PER_HUNDREDTH)
    assert time_get(rtc)[:8] == (126, 3, 1, 0, 0, 0, 0, 0)
    time_set(rtc, 126, 12, 31, 23, 59, 59, 99, weekday=4)
    rtc.advance(Rtc.CYCLES_PER_HUNDREDTH)
    assert time_get(rtc)[:8] == (127, 1, 1, 0, 0, 0, 0, 5)


def test_the_firmwares_alarm_fires_once_a_second():
    # rtc.c arms RPT 7 (every second) at hundredths 0, and enables ALM.
    rtc = running_rtc()
    time_set(rtc, 117, 1, 1, 0, 0, 6, 40)
    alarm_set(rtc, 7, hundredths=0)
    rtc.write(INTEN, 4, 1)
    assert rtc.next_deadline() == 60 * Rtc.CYCLES_PER_HUNDREDTH  # 6.40 -> 7.00
    rtc.advance(59 * Rtc.CYCLES_PER_HUNDREDTH)
    assert rtc.read(INTSTAT, 4) == 0 and rtc.pending_irqs() == []
    rtc.advance(Rtc.CYCLES_PER_HUNDREDTH)
    assert rtc.read(INTSTAT, 4) == 1 and rtc.pending_irqs() == [IRQ_RTC]
    rtc.write(INTCLR, 4, 1)  # what the ISR at 0x0008DFE8 does first
    assert rtc.pending_irqs() == []
    assert rtc.next_deadline() == SECOND
    # The machine never runs past a deadline, so neither does this.
    for _ in range(10):
        rtc.advance(rtc.next_deadline())
    assert rtc.alarms == 11 and time_get(rtc)[5:7] == (17, 0)


def test_the_alarm_latches_without_its_enable_but_asks_for_nothing():
    rtc = running_rtc()
    time_set(rtc, 117, 1, 1, 0, 0, 0)
    alarm_set(rtc, 7)
    rtc.advance(SECOND)
    assert rtc.read(INTSTAT, 4) == 1 and rtc.pending_irqs() == []


def test_every_repeat_interval_finds_its_next_match():
    hundredth = Rtc.CYCLES_PER_HUNDREDTH
    cases = [  # (RPT, alarm fields, hundredths from 12:34:56.78 on Thu 4 Oct)
        (9, {}, 1),
        (8, {"hundredths": 5}, 7),                                   # .85
        (7, {"hundredths": 78}, 100),                                # strictly after now
        (6, {"second": 0, "hundredths": 0}, 322),                    # 12:35:00.00
        (5, {"minute": 30}, 55 * 6000 + 322),                        # 13:30:00.00
        (4, {"hour": 6}, (17 * 3600 + 25 * 60 + 3) * 100 + 22),      # 06:00 tomorrow
        (3, {"weekday": 4, "hour": 12, "minute": 34, "second": 56, "hundredths": 79}, 1),
        (2, {"date": 5}, (11 * 3600 + 25 * 60 + 3) * 100 + 22),      # 00:00 on the 5th
        (1, {"month": 11, "date": 4, "hour": 12, "minute": 34, "second": 56, "hundredths": 78},
         31 * 86_400 * 100),                                          # 4 Nov, same time
    ]
    for repeat, fields, expected in cases:
        rtc = running_rtc()
        time_set(rtc, 126, 10, 4, 12, 34, 56, 78, weekday=4)
        alarm_set(rtc, repeat, **fields)
        assert rtc.next_deadline() == expected * hundredth, \
            (repeat, rtc.next_deadline() // hundredth, expected)


def test_a_boot_sets_the_default_time_and_takes_the_alarm_every_second():
    # From power-on the counter reads year 0, so rtc.c's init (0x000B3CE0) puts
    # in its default -- 1 Jan 2017 (117), 00:00:06, a Sunday, which the face
    # shows as "12:00 SUN 01 JAN" -- and arms the alarm. Ten seconds of watch
    # time later the counter has moved ten seconds and the firmware's RTC ISR
    # has run once for each.
    m = Apollo3Machine(image_mod.load(paths.FIRMWARE), log=lambda *a, **k: None,
                       trace=None, fast_hook=True, ble=False)
    attach_mspi_devices(m, resource_blob=None, log=lambda *a, **k: None)
    rtc = m.bus.by_base[0x40004200]
    m.run(max_instructions=500_000_000, slice_size=4_000_000)
    t = rtc.reading()
    assert (t["year"], t["month"], t["date"], t["weekday"], t["hour"], t["minute"]) == \
        (117, 1, 1, 0, 0, 0), t
    elapsed = t["second"] - 6
    counts = {exception_name(k): v for k, v in m.cortexm.exception_counts.items()}
    assert elapsed >= 9, t
    assert counts.get("RTC") in (elapsed, elapsed + 1), (counts.get("RTC"), t)
    assert rtc.int_enable == 1 and (rtc.read(RTCCTL, 4) >> 1) & 7 == 7


if __name__ == "__main__":
    failures = 0
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            try:
                fn()
                print(f"ok   {name}")
            except AssertionError as exc:
                failures += 1
                print(f"FAIL {name}: {exc}")
    print("all passed" if not failures else f"{failures} failed")
    sys.exit(1 if failures else 0)
