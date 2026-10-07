"""The watch's own sport records, and how a phone reads them back (#51, #85).

A fresh watch has none, so :app's sync only ever ran its empty paths against the
emulated one. The firmware writes them itself -- at the :29 and :59 minute ticks,
and when asked for the counts (``fw/health.py``) -- so moving its clock to just
before a tick is enough to give it a history, and walking its wrist puts steps in it.

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
from normplus.watch.fw.phone import EmulatedWatch

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
