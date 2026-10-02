"""``normwatch cmd``: one 0x6F question to the emulated watch, answered by its firmware.

The first half is the framing and decoding in ``fw/phone.py``, checked against
:protocol's rules (PacketBuilder, PacketDeframer, DateTimeCommand). The second
half runs the command itself, end to end, for the cases it exists to answer:

* a CHECK over 8001 comes back with the watch's own answer;
* DEVICE_VERSION with the payload the app sends ([06], WatchCommands.kt) is
  *refused* -- the generic ack with status 1 -- which is not what a timeout
  looks like, and is the thing normlink-cli could never see;
* a SET written to 8003 gets no reply at all (the 8003 dispatcher throws the
  handler's result away; README "Binding");
* the bind is only "done" once checkInit reads 1, which is about two seconds
  after bindEnd is acknowledged -- a state saved before that is an unbound
  watch. ``--save`` then gives a bound watch that the next run starts from.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_cmd.py
"""
import contextlib
import io
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.__main__ import main
from normwatch.fw.phone import (CHECK, SET, Deframer, ack, action_byte, command_byte,
                                datetime_payload, describe, frame)

REPO = Path(__file__).resolve().parents[3]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"
_tmp = Path(tempfile.mkdtemp(prefix="watchemu-cmd-"))


# -- framing and decoding --------------------------------------------------------

def test_a_frame_is_what_packetbuilder_builds():
    assert frame(0x08, CHECK, b"\x00") == bytes.fromhex("6f08700100008f")
    assert frame(0x93, SET, b"\x02") == bytes.fromhex("6f93710100028f")
    assert frame(0x01, 0x70) == bytes.fromhex("6f017000008f")       # empty payload
    assert len(frame(0x76, SET, bytes(300))) == 306
    assert frame(0x76, SET, bytes(300))[3:5] == (300).to_bytes(2, "little")


def test_the_deframer_reassembles_chunks_and_trusts_the_length():
    d = Deframer()
    whole = frame(0x03, 0x80, b"\x06A0.2\x8fB01")          # an 0x8F inside the payload
    assert d.feed(whole[:4]) == []
    assert d.feed(whole[4:9]) == []
    assert d.feed(whole[9:]) == [whole]
    # Garbage before a frame is skipped; two frames in one chunk both come out.
    two = b"\x00\x12" + frame(0x08, 0x80, b"\x4d") + frame(0x01, 0x81, b"\x07\x00")
    assert Deframer().feed(two) == [frame(0x08, 0x80, b"\x4d"), frame(0x01, 0x81, b"\x07\x00")]


def test_a_start_byte_that_is_not_a_start_is_stepped_over():
    # An implausible length (PacketDeframer's MAX_PAYLOAD_LEN is 512)...
    assert Deframer().feed(b"\x6f\x00\x00\xff\xff" + frame(0x08, 0x80, b"\x4d")) == \
        [frame(0x08, 0x80, b"\x4d")]
    # ...and a length that does not land on 0x8F.
    assert Deframer().feed(b"\x6f\x01\x02\x01\x00\x55\x00" + frame(0x08, 0x80, b"\x4d")) == \
        [frame(0x08, 0x80, b"\x4d")]


def test_describe_names_the_command_and_reads_the_generic_ack():
    assert describe(frame(0x08, 0x80, b"\x4d")) == "0x08 BATTERY_POWER CHECK_RESPONSE [4d]"
    assert describe(ack(0x03, 1)) == "ack for 0x03 DEVICE_VERSION: status 1 (refused)"
    assert describe(ack(0x94)) == "ack for 0x94 BIND_END: status 0 (ok)"
    assert describe(b"\x6f\x08\x70\x05\x00\x00\x8f") == "length says 5, carries 1"
    assert describe(b"\x01\x02") == "not a 0x6F frame"


def test_commands_and_actions_by_name_or_hex():
    assert command_byte("08") == command_byte("0x08") == command_byte("battery_power") == 0x08
    assert command_byte("BIND_START") == 0x93
    assert action_byte("check") == action_byte("70") == 0x70
    assert action_byte("SET") == 0x71
    for bad, parse in (("BRIGHTNESS", command_byte), ("100", command_byte), ("go", action_byte)):
        try:
            parse(bad)
        except ValueError as exc:
            assert repr(bad) in str(exc), exc
        else:
            raise AssertionError(f"{bad!r} was accepted")


def test_the_clock_is_datetimecommands_layout():
    plus3 = datetime(2026, 9, 16, 8, 3, 0, tzinfo=timezone(timedelta(hours=3)))
    assert datetime_payload(plus3) == bytes.fromhex("ea07091008030000000103 00".replace(" ", ""))
    minus = datetime(2026, 1, 2, 23, 59, 58, tzinfo=timezone(-timedelta(hours=4, minutes=30)))
    # Sign 0 for '-', then hours and minutes of the offset; reHome stays 0.
    assert datetime_payload(minus) == bytes.fromhex("ea070102173b3a0000" "00041e")
    assert datetime_payload(plus3, re_home=True)[8] == 1


# -- the command, end to end ---------------------------------------------------

def run(*argv: str) -> tuple[int, str]:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        status = main(["cmd", *argv, "--image", str(IMAGE), "--resources", str(RESOURCES)])
    return status, out.getvalue()


def test_a_check_over_8001_gets_the_watchs_answer():
    status, out = run("08", "70", "--payload", "00", "--no-bind")
    assert status == 0, out
    assert "-> 8001  6f 08 70 01 00 00 8f" in out, out
    assert "= 0x08 BATTERY_POWER CHECK_RESPONSE [" in out, out


def test_device_version_as_the_app_asks_for_it_is_refused():
    status, out = run("DEVICE_VERSION", "CHECK", "--payload", "06", "--no-bind")
    assert status == 0, out
    assert "<- 8002  6f 01 81 02 00 03 01 8f" in out, out
    assert "status 1 (refused)" in out, out


def test_a_set_over_8003_is_never_answered():
    status, out = run("SCREEN_BRIGHTNESS", "SET", "--payload", "3c", "--no-bind",
                      "--char", "8003", "--timeout", "3")
    assert status == 1, out
    assert "-> 8003" in out and "no reply within 3s" in out, out
    assert "never acknowledged" in out, out


def test_a_bind_saved_with_save_is_a_bound_watch_next_time():
    state = _tmp / "bound.zip"
    status, out = run("BIND_END", "CHECK", "--payload", "00", "--flash-state", str(state), "--save")
    assert status == 0, out
    assert "binding once the setup screen is up" in out, out
    assert "checkInit now 1" in out, out
    assert "= 0x94 BIND_END CHECK_RESPONSE [01]" in out, out
    assert "flash saved" in out and state.exists(), out

    status, out = run("BIND_END", "CHECK", "--payload", "00", "--flash-state", str(state))
    assert status == 0, out
    assert "initialised (checkInit 1), not binding" in out, out
    assert "flash saved" not in out, "a run without --save wrote the state"


def test_a_missing_state_without_save_is_an_error_not_a_fresh_watch():
    status = main(["cmd", "08", "70", "--flash-state", str(_tmp / "nope.zip")])
    assert status == 2


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
