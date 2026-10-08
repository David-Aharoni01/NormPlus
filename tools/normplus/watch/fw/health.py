"""The watch's health records: what the firmware writes, and how a phone reads them (#51).

**Sport records are the firmware's own.** The step task (``system_step_task.c``) posts
message 0x1A to itself -- through the poster at ``0x0005E4F0``, entry ``0x000CC9A8`` of
its API table -- and its handler (``0x0003D6E8``) appends the record with the store's
routine at ``0x0003D8A4``. That routine keeps one ring of 8 KB internal-flash pages per
record type, listed at ``0x000C8F60``: sport is type 0, :data:`SPORT_PAGES`, 28-byte
records, 292 a page and 1168 in all; a full ring erases its oldest page to go on, and
usage past 90% of a ring is checked for. All of it is in the region the bind erases. A
record is written in two cases, both seen on the physical watch and on this one:

* **at the :29 and :59 minute ticks**, stamped with that minute (``hh:29:00``,
  ``hh:59:00``) -- except the day's last half hour, whose record is written at
  midnight and stamped **23:58:30** local time. 888 of the physical watch's first 920
  records are on these ticks, and every one of its days ends ``23:29, 23:58:30, 00:29``;
  this one, run through midnight, writes its 23:58:30 record at 00:00:00;
* **when a phone asks for the counts** (``TOTAL_SPORT_SLEEP_COUNT``), the part of
  the current half hour not yet written, stamped with the time asked. Not every
  time: two asks eleven seconds apart on the physical watch wrote one record.

Not every tick writes either: after the clock is set, the first :29/:59 tick usually
writes nothing, and what decides it is upstream of the poster, which is reached only
through the table and has not been read. So :func:`write_sport_records` gives this
watch a history by setting its clock to just before a tick, watching
``Apollo3Machine.flash_programs`` for the record, and crossing again if none came --
no waiting half an hour per record. A record's steps are whatever the firmware's
pedometer made of :attr:`devices.MotionSensor.motion` (see :class:`devices.Walking`).

**One request reads them all.** ``GET_SPORT_DATA`` (0x54) and ``GET_HEART_RATE_DATA``
(0x5B) are answered with a stream: every record, indexed from 1, one frame each, and
the index in the request is ignored -- the stream starts from record 1 whatever it
says, on the physical watch as on this one. The official app sends ``[00 00]`` and
``[00]`` (``MBluetooth.getSportData`` / ``getHeartRateData``) and keeps receiving until
it holds as many as the count said (``GetSportData.parse80BytesArray``), restarting
its 10 s timer on every frame (``Leaf.isTimeout``, ``setLastSendTime``). The physical
watch streamed 920 sport records in about 29 s and 271 heart-rate records in 9 s.

**Heart rate is measured, not written** (#87). With auto heart rate on
(``AUTO_HEART_RATE`` 0x5C, SET ``[minutes]``) the firmware opens the PAH8011 at every
interval, feeds its samples and the wrist's motion to PixArt's algorithm once per
accelerometer batch, and when the algorithm has a rate -- about a minute in -- stores a
7-byte record in the heart-rate ring (type 2, :data:`HEART_RATE_PAGES`). Put a
:class:`devices.Pulse` under the sensor and the record says its rate:
:func:`write_heart_rate_records` does that.

**Sleep is recorded only in sleep mode** (#88), which the wearer starts from the sleep
screen or auto sleep starts: with :data:`AUTO_SLEEP_SWITCH` set, the minute check at
``0x000585D2`` turns it on inside the ``AUTO_SLEEP`` window and off at its awake minute.
A session stores its start (0x10), every state the sleep algorithm reports from the
accelerometer batches (``0x0005D40A``), and at its end 0x11 and the last state, in the
sleep ring (type 1, :data:`SLEEP_PAGES`). A still wrist goes awake, light, deep at one,
two and five minutes. Two things a phone sees: while a session is on, the counts say
**no sleep** (the data callback's 0x52 case skips the count when sleep mode is on,
``0x00055C5C``), and a clock change of more than a couple of minutes ends the session,
stamped with the time before the change (``0x0003B478``). :func:`write_sleep_records`
uses both.
"""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta
from typing import Optional

from .bootrom import BootRom
from .phone import CHECK, DATETIME, SET, ack, datetime_payload, frame

TOTAL_SPORT_SLEEP_COUNT = 0x52
GET_SPORT_DATA = 0x54
GET_HEART_RATE_DATA = 0x5B
#: AUTO_HEART_RATE: SET [minutes between measurements], 0 for off (HeartRateFrequency.smali).
AUTO_HEART_RATE = 0x5C
GET_SLEEP_DATA = 0x56
#: The deletes, each SET [00] (MBluetooth.deleteSportData and friends). Each erases its type's
#: whole ring (0x0003DA14). Sport only if its count is still the one the last
#: TOTAL_SPORT_SLEEP_COUNT reported (0x00055C72); sleep and heart rate whatever came since (#91).
DELETE_SPORT_DATA = 0x53
DELETE_SLEEP_DATA = 0x55
DELETE_HEART_RATE_DATA = 0x5A
#: AUTO_SLEEP: the bedtime and awake time (AutoSleep.smali).
AUTO_SLEEP = 0x58
SWITCH_SETTING = 0x90
#: SWITCH_BIT_AUTO_SLEEP (SwitchSettingCommand.BIT_AUTO_SLEEP): the firmware's minute
#: check tests it (``tst [0x10006BCC + 0x34C], #8`` at ``0x000585DA``).
AUTO_SLEEP_SWITCH = 0x8

#: The sport ring, from the store's page table at 0x000C8F60 (``4, 0xE0000, 0xE2000,
#: 0xE4000, 0xE6000``). The first record goes to 0x000E0000, the next to 0x000E001C.
SPORT_PAGES = (0x000E0000, 0x000E2000, 0x000E4000, 0x000E6000)
SPORT_RECORDS = SPORT_PAGES[0]
#: A record in flash is the wire record without its 2-byte index, padded to 28.
SPORT_RECORD_BYTES = 28
#: The minutes whose tick writes the half hour's record.
SLOT_MINUTES = (29, 59)
#: ...except at the end of the day: that record is stamped 23:58:30, and written at midnight.
DAY_END = (23, 58, 30)


#: The heart-rate ring: type 2 in the store's page table at 0x000C8F60 (``2, 0xEE000,
#: 0xF0000``), where a measurement's records land.
HEART_RATE_PAGES = (0x000EE000, 0x000F0000)


def heart_rate_records_written(machine) -> int:
    """Programs into the heart-rate ring so far: one for every record stored."""
    return sum(machine.flash_programs[page // BootRom.PAGE_SIZE] for page in HEART_RATE_PAGES)


#: The sleep ring: type 1 in the store's page table at 0x000C8F60 (``2, 0xE8000,
#: 0xEA000``). 8 bytes a record in flash, ``[time 4][type 1][00 00 00]``.
SLEEP_PAGES = (0x000E8000, 0x000EA000)
#: A sleep record on the wire: the index, then the flash record.
SLEEP_RECORD_BYTES = 10
#: Record types, as the firmware writes them (``0x0005D40A``: the sleep algorithm's state
#: 2, 1, 0, -1 becomes 0, 1, 2, 3) and the official app reads them
#: (``SleepNewDBService``: each lasts until the next record).
SLEEP_START, SLEEP_END = 0x10, 0x11
SLEEP_DEEP, SLEEP_LIGHT, SLEEP_AWAKE = 0, 1, 2


def sleep_records_written(machine) -> int:
    """Programs into the sleep ring so far: one for every record stored."""
    return sum(machine.flash_programs[page // BootRom.PAGE_SIZE] for page in SLEEP_PAGES)


def records_written(machine) -> int:
    """Programs into the sport ring so far: one for every record appended."""
    return sum(machine.flash_programs[page // BootRom.PAGE_SIZE] for page in SPORT_PAGES)


# -- what the replies say ---------------------------------------------------------

def counts(payload: bytes) -> dict:
    """``TOTAL_SPORT_SLEEP_COUNT``'s reply, read as ``AllDataTypeCount.parse80BytesArray``
    reads it: LE16 counts, sport then sleep, and heart rate, mood and blood pressure
    only when the reply is long enough to hold them."""
    names = ("sport", "sleep", "heart_rate", "mood", "blood_pressure")
    return {name: int.from_bytes(payload[2 * i:2 * i + 2], "little")
            for i, name in enumerate(names) if len(payload) >= 2 * i + 2}


def sport_record(payload: bytes) -> dict:
    """One ``GET_SPORT_DATA`` reply, as ``GetSportData.parse80BytesArray`` reads it."""
    def le(start: int, end: int) -> int:
        return int.from_bytes(payload[start:end + 1], "little")
    return {"index": le(0, 1), "timestamp": le(2, 5), "steps": le(6, 9),
            "calories": le(10, 13), "distance": le(14, 17),
            "sport_time": le(18, 21) if len(payload) > 0x12 else 0,
            "avg_bpm": payload[22] if len(payload) > 0x16 else 0,
            "type": payload[23] if len(payload) > 0x17 else 0,
            "static_calories": le(24, 27) if len(payload) > 0x1B else 0}


def heart_rate_record(payload: bytes) -> Optional[dict]:
    """One ``GET_HEART_RATE_DATA`` reply: ``GetHeartRateData`` takes exactly 7 bytes."""
    if len(payload) != 7:
        return None
    return {"index": int.from_bytes(payload[0:2], "little"),
            "timestamp": int.from_bytes(payload[2:6], "little"), "bpm": payload[6]}


def sleep_record(payload: bytes) -> Optional[dict]:
    """One ``GET_SLEEP_DATA`` reply: 10 bytes from the firmware (its handler sends 10,
    ``0x0003A2EC``), of which ``GetSleepData`` reads the first 7 -- index, time, type."""
    if len(payload) != SLEEP_RECORD_BYTES:
        return None
    return {"index": int.from_bytes(payload[0:2], "little"),
            "timestamp": int.from_bytes(payload[2:6], "little"), "type": payload[6],
            "rest": payload[7:]}


def payload_of(whole: bytes) -> bytes:
    """The payload of a whole 0x6F frame."""
    return whole[5:5 + int.from_bytes(whole[3:5], "little")]


# -- asking ------------------------------------------------------------------------

async def ask_counts(phone, *, timeout: float = 5.0) -> Optional[dict]:
    """Ask for the counts, as the sync does first. Note that this can write a record."""
    for whole in await phone.stream(frame(TOTAL_SPORT_SLEEP_COUNT, CHECK, b"\x00"),
                                    idle=timeout, code=TOTAL_SPORT_SLEEP_COUNT):
        return counts(payload_of(whole))
    return None


async def read_records(phone, code: int, count: int, *, payload: Optional[bytes] = None,
                       idle: float = 10.0) -> list:
    """One request, and the stream it starts: up to *count* records' payloads.

    As the official app reads them: ``[00 00]`` for sport, ``[00]`` for heart rate,
    then frames until the one indexed *count* has come, or *idle* seconds pass
    without one.
    """
    if payload is None:
        payload = bytes(2) if code == GET_SPORT_DATA else b"\x00"
    wholes = await phone.stream(frame(code, CHECK, payload), idle=idle, code=code,
                                last=lambda f: int.from_bytes(f[5:7], "little") >= count)
    return [payload_of(w) for w in wholes]


# -- making the firmware write them --------------------------------------------------

async def watch_seconds(machine, seconds: float) -> None:
    """Wait for *seconds* of WATCH time, however far behind the wall clock it runs."""
    until = machine.cycles + int(seconds * machine.CYCLES_PER_SECOND)
    while machine.cycles < until:
        await asyncio.sleep(0.05)


def slot_ticks(count: int, end: datetime) -> list:
    """The stamps of the last *count* records the firmware would have written by *end*,
    oldest first: every :29 and :59, with 23:58:30 for the day's last half hour (written
    at midnight, see :func:`written_at`)."""
    def stamped(tick: datetime) -> datetime:
        if (tick.hour, tick.minute) == (23, 59):
            return tick.replace(minute=DAY_END[1], second=DAY_END[2])
        return tick

    tick = end.replace(second=0, microsecond=0)
    while tick.minute not in SLOT_MINUTES:
        tick -= timedelta(minutes=1)
    # From one slot on, since 23:58:30 is before the 23:59 that stands for it.
    ticks = [stamped(tick + timedelta(minutes=30 * (1 - i))) for i in range(count + 2)]
    return sorted(t for t in ticks if written_at(t) <= end)[-count:]


def written_at(stamp: datetime) -> datetime:
    """When the firmware writes the record stamped *stamp*: the tick itself, but
    midnight for the day's last."""
    if (stamp.hour, stamp.minute, stamp.second) == DAY_END:
        return stamp.replace(hour=0, minute=0, second=0) + timedelta(days=1)
    return stamp


async def set_clock(phone, when: datetime) -> bool:
    """DATETIME SET, as ``DateTimeCommand.setPayload`` builds it; True if acknowledged."""
    reply = await phone.exchange(frame(DATETIME, SET, datetime_payload(when)))
    return reply == ack(DATETIME)


async def _record_lands(machine, before: int, seconds: float) -> bool:
    """Wait up to *seconds* of watch time for the firmware to append a record."""
    until = machine.cycles + int(seconds * machine.CYCLES_PER_SECOND)
    while machine.cycles < until:
        if records_written(machine) > before:
            return True
        await asyncio.sleep(0.02)
    return records_written(machine) > before


async def write_sport_records(phone, watch, count: int, *, end: Optional[datetime] = None,
                              walk: float = 0.0, motion=None, tries: int = 3, log=None) -> list:
    """Have the firmware write *count* sport records, one per half hour up to *end*.

    For each of the last *count* record times before *end* (default: now; see
    :func:`slot_ticks`) the clock is set to two seconds before it and the watch runs until
    the firmware has appended the record -- the first tick after a clock set usually
    writes nothing, so a tick is crossed up to *tries* times. With *walk* seconds the
    wrist walks for that long before each tick (*motion*, default a
    :class:`devices.Walking`), so the record has steps in it. *watch* is an
    ``EmulatedWatch``. The clock is left at *end*. Returns the record times.

    Best on a watch with no records yet: on one with a history, the new records go
    after the old ones whatever their times.
    """
    from .devices import Walking

    machine, sensor = watch.machine, watch.motion
    motion = motion or Walking()
    end = end or datetime.now().replace(microsecond=0)
    ticks = slot_ticks(count, end)
    # Two seconds: set one second before a tick and the tick never writes (three tries out
    # of three), as if the firmware had not finished taking the new time when it came.
    lead = 2 + walk
    crossings = 0

    async def clock(when: datetime) -> None:
        if not await set_clock(phone, when):
            raise RuntimeError(f"the watch did not acknowledge the clock set to {when}")

    for n, tick in enumerate(ticks, 1):
        for attempt in range(1, tries + 1):
            crossings += 1
            before = records_written(machine)
            await clock(written_at(tick) - timedelta(seconds=lead))
            if walk:
                sensor.motion = motion
                await watch_seconds(machine, walk)
                sensor.motion = None
            if await _record_lands(machine, before, lead - walk + 2):
                break
        else:
            raise RuntimeError(f"no record at {tick} after crossing it {tries} times")
        if log is not None and (n == count or n % 25 == 0):
            log(f"{n}/{count} records, the last at {tick:%Y-%m-%d %H:%M:%S} "
                f"({crossings} crossings so far)")
    await clock(end)
    return ticks


async def set_auto_heart_rate(phone, minutes: int) -> bool:
    """AUTO_HEART_RATE SET [minutes] (0 turns it off); True if acknowledged."""
    reply = await phone.exchange(frame(AUTO_HEART_RATE, SET, bytes([minutes & 0xFF])))
    return reply == ack(AUTO_HEART_RATE)


async def write_heart_rate_records(phone, watch, count: int, *, bpm: float = 72.0,
                                   per_record: float = 150.0, log=None) -> int:
    """Have the firmware measure and store *count* heart-rate records from a pulse.

    A :class:`devices.Pulse` at *bpm* goes under the PAH8011, auto heart rate goes
    on at every minute, and the watch runs until its firmware has stored *count*
    records -- each one a measurement, PixArt's algorithm and all, about a minute of
    watch time -- or *per_record* seconds of watch time pass without the next one.
    Auto heart rate is turned off and the wrist taken away again afterwards.
    Returns how many were stored. *watch* is an ``EmulatedWatch``.
    """
    from .devices import Pulse

    machine, sensor = watch.machine, watch.heart
    if not await set_auto_heart_rate(phone, 1):
        raise RuntimeError("the watch did not acknowledge AUTO_HEART_RATE")
    sensor.wrist = Pulse(bpm)
    start = heart_rate_records_written(machine)
    stored = 0
    try:
        while stored < count:
            before = heart_rate_records_written(machine)
            until = machine.cycles + int(per_record * machine.CYCLES_PER_SECOND)
            while machine.cycles < until and heart_rate_records_written(machine) == before:
                await asyncio.sleep(0.1)
            now = heart_rate_records_written(machine) - start
            if now == stored:
                break
            stored = now
            if log is not None:
                log(f"{stored}/{count} heart-rate records")
    finally:
        sensor.wrist = None
        await set_auto_heart_rate(phone, 0)
    return stored


# -- sleep ---------------------------------------------------------------------------

async def switches(phone) -> int:
    """SWITCH_SETTING CHECK: the 4-byte LE switch mask (``SwitchSetting.parse80BytesArray``)."""
    reply = await phone.exchange(frame(SWITCH_SETTING, CHECK, b"\x00"))
    if reply is None:
        raise RuntimeError("the watch did not answer SWITCH_SETTING")
    return int.from_bytes(payload_of(reply)[:4], "little")


async def set_switches(phone, mask: int) -> bool:
    """SWITCH_SETTING SET: ``[00]`` and the whole mask, LE (``MBluetooth.setSwitchSetting``)."""
    reply = await phone.exchange(frame(SWITCH_SETTING, SET, b"\x00" + mask.to_bytes(4, "little")))
    return reply == ack(SWITCH_SETTING)


async def sleep_window(phone) -> bytes:
    """AUTO_SLEEP CHECK: ``[bed hour, bed minute, awake hour, awake minute, remind cycle]``."""
    reply = await phone.exchange(frame(AUTO_SLEEP, CHECK, b"\x00"))
    if reply is None:
        raise RuntimeError("the watch did not answer AUTO_SLEEP")
    return payload_of(reply)[:5]


async def set_sleep_window(phone, window: bytes) -> bool:
    """AUTO_SLEEP SET, the five bytes :func:`sleep_window` reads (``AutoSleep.smali``)."""
    reply = await phone.exchange(frame(AUTO_SLEEP, SET, bytes(window)))
    return reply == ack(AUTO_SLEEP)


def nights(count: int, end: datetime, *, bed=(23, 0), awake=(7, 0)) -> list:
    """The last *count* nights that are over by *end*: ``(bedtime, awake time)`` pairs."""
    morning = end.replace(hour=awake[0], minute=awake[1], second=0, microsecond=0)
    if morning > end:
        morning -= timedelta(days=1)
    length = timedelta(hours=awake[0] - bed[0], minutes=awake[1] - bed[1]) % timedelta(days=1)
    mornings = [morning - timedelta(days=n) for n in range(count)][::-1]
    return [(m - length, m) for m in mornings]


async def write_sleep_records(phone, watch, count: int, *, end: Optional[datetime] = None,
                              asleep: float = 330.0, bed=(23, 0), awake=(7, 0), motion=None,
                              tries: int = 3, log=None) -> list:
    """Have the firmware record *count* sleep sessions, one a night, the last by *end*.

    Auto sleep goes on (:data:`AUTO_SLEEP_SWITCH`) with a *bed* to *awake* window, and
    for each night the clock is set to ten seconds before bedtime: at the minute the
    firmware's own window check turns sleep mode on and stores the session's start
    (0x10). The wrist lies still (or does *motion*) for *asleep* seconds of watch time
    while the sleep algorithm classifies it -- lying still, it says awake after a minute,
    light after two and deep after five, so the default has all three. Then the clock is
    moved half a minute past the awake time. That is a time change, and a time change
    ends a session the way waking does (0x11 and the last state) but **stamped with the
    time the clock was moved from**: a session is as long as the watch ran it, not a
    night. The window ends at the awake minute, so no session starts again.
    The switch mask and the window are put back afterwards and the clock left at *end*.
    Returns the nights. *watch* is an ``EmulatedWatch``.
    """
    machine, sensor = watch.machine, watch.motion
    end = end or datetime.now().replace(microsecond=0)
    plan = nights(count, end, bed=bed, awake=awake)

    async def clock(when: datetime) -> None:
        if not await set_clock(phone, when):
            raise RuntimeError(f"the watch did not acknowledge the clock set to {when}")

    async def lands(before: int, seconds: float) -> bool:
        until = machine.cycles + int(seconds * machine.CYCLES_PER_SECOND)
        while machine.cycles < until and sleep_records_written(machine) <= before:
            await asyncio.sleep(0.05)
        return sleep_records_written(machine) > before

    mask, window = await switches(phone), await sleep_window(phone)
    if not await set_switches(phone, mask | AUTO_SLEEP_SWITCH):
        raise RuntimeError("the watch did not acknowledge SWITCH_SETTING")
    if not await set_sleep_window(phone, bytes([*bed, *awake, window[4]])):
        raise RuntimeError("the watch did not acknowledge AUTO_SLEEP")
    try:
        for n, (night, morning) in enumerate(plan, 1):
            for _attempt in range(tries):
                before = sleep_records_written(machine)
                await clock(night - timedelta(seconds=10))
                if await lands(before, 20):
                    break
            else:
                raise RuntimeError(f"sleep mode did not start at {night} in {tries} tries")
            sensor.motion = motion
            try:
                await watch_seconds(machine, asleep)
            finally:
                sensor.motion = None
            before = sleep_records_written(machine)
            await clock(morning + timedelta(seconds=30))
            if not await lands(before, 10):
                raise RuntimeError(f"the night of {night:%Y-%m-%d} did not end")
            await watch_seconds(machine, 2)
            if log is not None:
                log(f"{n}/{count} nights, {night:%Y-%m-%d %H:%M} to {morning:%H:%M}: "
                    f"{sleep_records_written(machine)} sleep records so far")
    finally:
        await set_sleep_window(phone, window)
        await set_switches(phone, mask)
        await clock(end)
    return plan
