"""The watch's own sport records, and how a phone reads them back (#51, #85).

A fresh watch has none, so :app's sync only ever ran its empty paths against the
emulated one. The firmware writes them itself -- at the :29 and :59 minute ticks,
and when asked for the counts (``fw/health.py``) -- so moving its clock to just
before a tick is enough to give it a history, and walking its wrist puts steps in it.

Heart rate is measured the same way: a pulse under the emulated PAH8011, and the
firmware's driver and PixArt's algorithm store its rate (#87). And sleep: auto sleep
and the clock at bedtime, and the firmware's sleep algorithm records a still wrist (#88).

The second half is what the sync gets wrong (#85): one ``GET_SPORT_DATA`` is
answered with every record, indexed from 1, whatever index the request names. The
physical watch does the same; the replies from it pinned here were read on
2026-10-07.

Run with:  uv run normtest test_health_records
"""
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch.fw import health
from normplus.watch.fw.phone import SET, EmulatedWatch, ack, frame

REPO = Path(__file__).resolve().parents[2]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"
BOUND = Path(__file__).resolve().parent / "fixtures" / "bound-watch.zip"

#: The physical watch's answers, 2026-10-07: the counts, its first sport record and
#: its first heart-rate record.
PHYSICAL_COUNTS = bytes.fromhex("98030000 0f010000")
PHYSICAL_SPORT = bytes.fromhex("0100 14c5ac6a 00000000 e8800000 00000000 00000000 00 09 e8800000")
PHYSICAL_HR = bytes.fromhex("0100 367f2e6a 4a")


# -- reading the replies ----------------------------------------------------------

def test_the_counts_are_read_as_alldatatypecount_reads_them():
    assert health.counts(PHYSICAL_COUNTS) == {"sport": 920, "sleep": 0, "heart_rate": 271,
                                              "mood": 0}
    assert health.counts(bytes.fromhex("0500 0000")) == {"sport": 5, "sleep": 0}


def test_a_sport_record_is_read_as_getsportdata_reads_it():
    record = health.sport_record(PHYSICAL_SPORT)
    assert len(PHYSICAL_SPORT) == 28
    assert record == {"index": 1, "timestamp": 0x6AACC514, "steps": 0, "calories": 33000,
                      "distance": 0, "sport_time": 0, "avg_bpm": 0, "type": 9,
                      "static_calories": 33000}, record
    # 2026-09-18 04:59:00 UTC: a :59 tick.
    assert datetime.utcfromtimestamp(record["timestamp"]).minute == 59


def test_a_heart_rate_record_is_exactly_seven_bytes():
    assert health.heart_rate_record(PHYSICAL_HR) == {"index": 1, "timestamp": 0x6A2E7F36,
                                                     "bpm": 0x4A}
    assert health.heart_rate_record(PHYSICAL_HR + b"\x00") is None


def test_the_ticks_are_the_half_hours_last_minutes():
    ticks = health.slot_ticks(3, datetime(2026, 10, 7, 12, 10, 40))
    assert ticks == [datetime(2026, 10, 7, 10, 59), datetime(2026, 10, 7, 11, 29),
                     datetime(2026, 10, 7, 11, 59)], ticks
    assert health.slot_ticks(1, datetime(2026, 10, 7, 11, 29)) == [datetime(2026, 10, 7, 11, 29)]
    # The day's last record is the physical watch's 23:58:30, not 23:59 -- written at
    # midnight, so not there yet a minute before it.
    assert health.slot_ticks(3, datetime(2026, 10, 8, 0, 40)) == [
        datetime(2026, 10, 7, 23, 29), datetime(2026, 10, 7, 23, 58, 30),
        datetime(2026, 10, 8, 0, 29)]
    assert health.slot_ticks(1, datetime(2026, 10, 7, 23, 59, 30)) == [
        datetime(2026, 10, 7, 23, 29)]
    assert health.written_at(datetime(2026, 10, 7, 23, 58, 30)) == datetime(2026, 10, 8)
    assert health.written_at(datetime(2026, 10, 7, 23, 29)) == datetime(2026, 10, 7, 23, 29)


# -- the firmware writes them ---------------------------------------------------------

def test_the_firmware_writes_records_and_one_request_streams_them_all():
    watch = EmulatedWatch(IMAGE, RESOURCES, flash_state=BOUND)
    seen = {}

    async def flow(phone):
        await phone.find(watch.address)
        await phone.connect(watch.address)
        await phone.pair()
        await phone.discover()
        await phone.listen()
        seen["before"] = await health.ask_counts(phone)
        seen["ticks"] = await health.write_sport_records(
            phone, watch, 3, end=datetime(2026, 10, 7, 12, 10))
        seen["counts"] = await health.ask_counts(phone)
        count = seen["counts"]["sport"]
        seen["records"] = await health.read_records(phone, health.GET_SPORT_DATA, count)
        # The index in the request is not where the stream starts.
        seen["from_two"] = await health.read_records(phone, health.GET_SPORT_DATA, count,
                                                     payload=bytes([2, 0]))
        # Thirty-odd seconds of walking before 12:29, and that half hour has steps.
        seen["walk_tick"] = (await health.write_sport_records(
            phone, watch, 1, end=datetime(2026, 10, 7, 12, 40), walk=35))[0]
        seen["walked"] = await health.ask_counts(phone)
        seen["after_walk"] = await health.read_records(phone, health.GET_SPORT_DATA,
                                                       seen["walked"]["sport"])
        await phone.disconnect()

    try:
        watch.start(seconds=300)
        watch.drive(flow, timeout=400)
    finally:
        watch.stop()

    assert seen["before"] == {"sport": 0, "sleep": 0, "heart_rate": 0, "mood": 0}, seen["before"]
    counts = seen["counts"]
    # Three ticks, and the count request itself may write the half hour so far.
    assert counts["sport"] in (3, 4) and counts["sleep"] == 0 and counts["heart_rate"] == 0, counts
    records = [health.sport_record(r) for r in seen["records"]]
    assert all(len(r) == 28 for r in seen["records"]), [len(r) for r in seen["records"]]
    assert [r["index"] for r in records] == list(range(1, counts["sport"] + 1)), records
    stamps = [int(t.timestamp()) for t in seen["ticks"]]
    assert [r["timestamp"] for r in records[:3]] == stamps, (records, stamps)
    assert all(r["steps"] == 0 for r in records), "a still wrist walked"

    assert [health.sport_record(r)["index"] for r in seen["from_two"]][:2] == [1, 2], \
        "a request for index 2 did not start the stream from record 1"

    walked = [health.sport_record(r) for r in seen["after_walk"]]
    assert [r["index"] for r in walked] == list(range(1, seen["walked"]["sport"] + 1)), walked
    tick = [r for r in walked if r["timestamp"] == int(seen["walk_tick"].timestamp())]
    assert tick, (seen["walk_tick"], walked)
    assert tick[0]["steps"] > 0 and tick[0]["distance"] > 0, tick[0]


def test_a_delete_erases_sport_but_not_a_record_newer_than_the_count():
    # DELETE_SPORT_DATA SET [00] erases the whole sport ring -- but only if the count is still
    # the one the last TOTAL_SPORT_SLEEP_COUNT reported (0x00055C72: [0x10006BCC+0x420] against
    # +0x2F5). A record that lands in between is kept, and the delete is acknowledged all the
    # same. What :app's delete after syncing relies on (#91). The watch goes on writing after.
    watch = EmulatedWatch(IMAGE, RESOURCES, flash_state=BOUND)
    seen = {}

    async def delete():
        reply = await phone_.exchange(frame(health.DELETE_SPORT_DATA, SET, b"\x00"))
        await health.watch_seconds(watch.machine, 1.0)
        return reply

    async def flow(phone):
        nonlocal phone_
        phone_ = phone
        await phone.find(watch.address)
        await phone.connect(watch.address)
        await phone.pair()
        await phone.discover()
        await phone.listen()
        await health.write_sport_records(phone, watch, 2, end=datetime(2026, 10, 7, 12, 10))
        seen["counted"] = (await health.ask_counts(phone))["sport"]
        seen["read"] = len(await health.read_records(phone, health.GET_SPORT_DATA, seen["counted"]))
        # A :29 record after the count, as a tick in the middle of a sync would write it.
        await health.write_sport_records(phone, watch, 1, end=datetime(2026, 10, 7, 12, 40))
        seen["raced_ack"] = await delete()
        seen["after_raced"] = (await health.ask_counts(phone))["sport"]
        seen["ack"] = await delete()
        seen["after"] = sport_count(watch)
        ticks = await health.write_sport_records(phone, watch, 2, end=datetime(2026, 10, 7, 14, 10))
        seen["new"] = [health.sport_record(r)["timestamp"] for r in await health.read_records(
            phone, health.GET_SPORT_DATA, sport_count(watch))]
        seen["ticks"] = [int(t.timestamp()) for t in ticks]
        await phone.disconnect()

    phone_ = None
    try:
        watch.start(seconds=200)
        watch.drive(flow, timeout=300)
    finally:
        watch.stop()

    acked = ack(health.DELETE_SPORT_DATA)
    assert seen["read"] == seen["counted"] >= 2, seen
    assert seen["raced_ack"] == acked, seen["raced_ack"]
    # Nothing erased: the counted records and the raced one (and this count's own, if it wrote one).
    assert seen["after_raced"] >= seen["counted"] + 1, seen
    assert seen["ack"] == acked and seen["after"] == 0, seen
    assert seen["new"][:2] == seen["ticks"], seen


def sport_count(watch) -> int:
    """The sport ring's count, from the store's state (0x10006BCC + 0x2F5)."""
    return int.from_bytes(watch.machine.uc.mem_read(0x10006BCC + 0x2F5, 4), "little")


def test_the_firmware_measures_a_pulse_and_stores_the_rate():
    # Auto heart rate on, a 72 bpm pulse under the PAH8011: the firmware's own
    # driver and PixArt's algorithm measure it, and the record it stores says 72
    # (#87). Then the stream gives it back, 7 bytes, as GetHeartRateData reads it.
    watch = EmulatedWatch(IMAGE, RESOURCES, flash_state=BOUND)
    seen = {}

    async def flow(phone):
        await phone.find(watch.address)
        await phone.connect(watch.address)
        await phone.pair()
        await phone.discover()
        await phone.listen()
        await health.set_clock(phone, datetime(2026, 10, 7, 10, 0, 50))
        seen["stored"] = await health.write_heart_rate_records(phone, watch, 1, bpm=72)
        seen["counts"] = await health.ask_counts(phone)
        seen["records"] = await health.read_records(phone, health.GET_HEART_RATE_DATA,
                                                    seen["counts"]["heart_rate"])
        await phone.disconnect()

    try:
        watch.start(seconds=300)
        watch.drive(flow, timeout=400)
    finally:
        watch.stop()

    assert seen["stored"] == 1, seen
    assert seen["counts"]["heart_rate"] >= 1, seen["counts"]
    records = [health.heart_rate_record(r) for r in seen["records"]]
    assert all(records), [r.hex(" ") for r in seen["records"]]
    assert [r["index"] for r in records] == list(range(1, len(records) + 1)), records
    assert all(abs(r["bpm"] - 72) <= 2 for r in records), records
    assert watch.heart.wrist is None, "the pulse was left under the sensor"


def test_the_firmware_records_a_sleep_session_and_one_request_streams_it():
    # Auto sleep on and the clock at bedtime: the firmware's own window check starts a
    # session, its sleep algorithm calls a still wrist awake after a minute, and moving
    # the clock ends the session (#88). One GET_SLEEP_DATA streams it, 10 bytes a
    # record. While a session is on, the counts say there is no sleep.
    watch = EmulatedWatch(IMAGE, RESOURCES, flash_state=BOUND)
    bedtime = datetime(2026, 10, 6, 23, 0)
    seen = {}

    async def flow(phone):
        await phone.find(watch.address)
        await phone.connect(watch.address)
        await phone.pair()
        await phone.discover()
        await phone.listen()
        seen["before"] = await health.ask_counts(phone)
        seen["mask"] = await health.switches(phone)
        seen["nights"] = await health.write_sleep_records(
            phone, watch, 1, end=datetime(2026, 10, 7, 12, 0), asleep=75)
        seen["mask_after"] = await health.switches(phone)
        seen["counts"] = await health.ask_counts(phone)
        seen["records"] = await health.read_records(phone, health.GET_SLEEP_DATA,
                                                    seen["counts"]["sleep"])
        # DELETE_SLEEP_DATA erases them, with no count to check against (#91).
        reply = await phone.exchange(frame(health.DELETE_SLEEP_DATA, SET, b"\x00"))
        await health.watch_seconds(watch.machine, 1.0)
        seen["deleted"] = (reply, (await health.ask_counts(phone))["sleep"])
        # Asleep again: a new session's records are there, and the count hides them.
        await health.set_switches(phone, seen["mask"] | health.AUTO_SLEEP_SWITCH)
        stored = health.sleep_records_written(watch.machine)
        await health.set_clock(phone, datetime(2026, 10, 7, 22, 59, 50))
        for _ in range(40):
            if health.sleep_records_written(watch.machine) > stored:
                break
            await health.watch_seconds(watch.machine, 0.5)
        seen["asleep"] = health.sleep_records_written(watch.machine) > stored
        seen["asleep_counts"] = await health.ask_counts(phone)
        await phone.disconnect()

    try:
        watch.start(seconds=300)
        watch.drive(flow, timeout=400)
    finally:
        watch.stop()

    assert seen["before"]["sleep"] == 0, seen["before"]
    assert seen["nights"] == [(bedtime, datetime(2026, 10, 7, 7, 0))], seen["nights"]
    assert seen["mask_after"] == seen["mask"], "the switches were not put back"
    records = [health.sleep_record(r) for r in seen["records"]]
    assert all(records), [r.hex(" ") for r in seen["records"]]
    assert [r["index"] for r in records] == list(range(1, seen["counts"]["sleep"] + 1)), records
    assert [r["type"] for r in records] == [health.SLEEP_START, health.SLEEP_AWAKE,
                                            health.SLEEP_END, health.SLEEP_AWAKE], records
    assert all(r["rest"] == bytes(3) for r in records), records
    start, end = records[0]["timestamp"], records[2]["timestamp"]
    assert start == int(bedtime.timestamp()), (start, bedtime)
    # Ended by the clock moving to the morning, and stamped with the time it moved from.
    assert 75 <= end - start <= 90, (start, end)
    assert seen["deleted"] == (ack(health.DELETE_SLEEP_DATA), 0), seen["deleted"]
    assert seen["asleep"], "auto sleep did not start a second session"
    assert seen["asleep_counts"]["sleep"] == 0, seen["asleep_counts"]


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
