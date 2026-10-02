"""``normwatch cmd --mac``: the physical watch over bleak, checked without one.

``physical.PhysicalPhone`` speaks to the real watch through this PC's own
adapter. These tests put a fake ``BleakClient`` where the watch would be -- it
records every write and answers the way the firmware does over 8001 (a CHECK
with its own response, a SET with the generic ack, nothing for a SET on 8003)
-- and check what the phone puts on the air:

* it subscribes to 8002 and 8004 before its first write, as ``BleWriteQueue``
  does, so no reply can arrive unheard;
* a frame goes to 8001 *with* response, then ``[03]`` to 8002 without --
  where normlink-cli, which this replaced, wrote to 8003;
* replies split across notifications are put back together;
* ``normwatch cmd --mac`` runs the same flow as the emulated watch's --
  checkInit, the bind only when it reads 0 -- and refuses the emulated
  watch's own options.

Against the watch itself (it must be awake and not connected to a phone):

    uv run normcmd 08 70 --payload 00 --mac 4C:59:80:12:44:F1

Run with:  uv run normtest test_physical
"""
import asyncio
import contextlib
import io
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch import physical
from normplus.watch.__main__ import main
from normplus.watch.fw.phone import CHECK, CHECK_RESPONSE, SET, WATCH, ack, frame
from normplus.watch.physical import PhysicalPhone, mac_address, short_uuid

BATTERY = 0x08
BIND_START, DATETIME, BIND_END = 0x93, 0x04, 0x94


# -- a watch where bleak would reach one -------------------------------------------

def _uuid(short: str) -> str:
    return f"0000{short}-0000-1000-8000-00805f9b34fb"


class _Char:
    def __init__(self, short: str) -> None:
        self.uuid = _uuid(short)


class _Service:
    def __init__(self, short: str, chars) -> None:
        self.uuid = _uuid(short)
        self.characteristics = [_Char(c) for c in chars]


class FakeWatch:
    """A ``BleakClient`` stand-in answering like the firmware's 8001 dispatcher.

    A frame written to 8001 is answered when ``[03]`` lands on 8002, in 20-byte
    notifications (the payload of a 23-byte ATT MTU). *init* is what checkInit
    reads; bindEnd sets it to 1.
    """

    def __init__(self, address, *, init: int = 1, services=("1800", "6006", "1530"),
                 answers: bool = True) -> None:
        self.address = address
        self.init = init
        self.answers = answers
        tables = {"1800": ["2A00"], "6006": ["8001", "8002", "8003", "8004"],
                  "1530": ["1531", "1532"]}
        self.services = [_Service(s, tables[s]) for s in services]
        self.is_connected = False
        self.log: list = []
        self.subscribed: dict = {}
        self._pending = None

    async def connect(self, timeout: float) -> None:
        if not self.answers:
            raise asyncio.TimeoutError     # what bleak raises when timeout runs out
        self.is_connected = True
        self.log.append(("connect",))

    async def pair(self) -> None:
        self.log.append(("pair",))

    async def disconnect(self) -> None:
        self.is_connected = False
        self.log.append(("disconnect",))

    async def start_notify(self, char, callback) -> None:
        self.subscribed[short_uuid(char.uuid)] = callback
        self.log.append(("subscribe", short_uuid(char.uuid)))

    async def write_gatt_char(self, char, data, response: bool) -> None:
        short, data = short_uuid(char.uuid), bytes(data)
        self.log.append(("write", short, data, response))
        if short in ("8001", "8003"):
            self._pending = (short, data)
        elif short == "8002" and data == b"\x03" and self._pending:
            reply = self._answer(*self._pending)
            self._pending = None
            for at in range(0, len(reply or b""), 20):
                self.subscribed["8002"](char, bytearray(reply[at:at + 20]))

    def _answer(self, char: str, data: bytes):
        cmd, action = data[1], data[2]
        if action == CHECK and cmd == BIND_END:
            return frame(BIND_END, CHECK_RESPONSE, bytes([self.init]))
        if action == CHECK and cmd == BATTERY:
            return frame(BATTERY, CHECK_RESPONSE, bytes([0x4D]) + bytes(20))   # 2 notifications
        if action == SET and char == "8003":
            return None                                   # the 8003 dispatcher drops it
        if action == SET:
            if cmd == BIND_END:
                self.init = 1
            return ack(cmd)
        return None


class FakePhysicalPhone(PhysicalPhone):
    """PhysicalPhone on a FakeWatch: the scan always finds it, nothing touches bleak."""

    made: list = []
    init = 1
    advertising = True
    answers = True
    gatt = ("1800", "6006", "1530")

    def __init__(self, *, log=None) -> None:
        def client(address):
            fake = FakeWatch(address, init=self.init, services=self.gatt,
                             answers=self.answers)
            FakePhysicalPhone.made.append(fake)
            return fake
        super().__init__(client_factory=client, **({"log": log} if log else {}))

    async def find(self, address: str = WATCH, *, timeout: float = 15.0) -> bool:
        self.seen[address] = "Norm2#00000"
        return self.advertising


@contextlib.contextmanager
def fake_watch(**attrs):
    saved = {k: getattr(FakePhysicalPhone, k) for k in attrs}
    for k, v in attrs.items():
        setattr(FakePhysicalPhone, k, v)
    FakePhysicalPhone.made = []
    real, physical.PhysicalPhone = physical.PhysicalPhone, FakePhysicalPhone
    try:
        yield FakePhysicalPhone.made
    finally:
        physical.PhysicalPhone = real
        for k, v in saved.items():
            setattr(FakePhysicalPhone, k, v)


def cmd(*argv: str) -> tuple[int, str]:
    out = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
        status = main(["cmd", *argv])
    return status, out.getvalue()


# -- the phone -----------------------------------------------------------------------

def test_addresses_are_read_the_way_people_write_them():
    assert mac_address("4c:59:80:12:44:f1") == mac_address("4C-59-80-12-44-F1") == WATCH
    for bad in ("4C:59:80:12:44", "4C:59:80:12:44:F1:00", "watch", "4C5980 1244F1"):
        try:
            mac_address(bad)
        except ValueError:
            continue
        raise AssertionError(f"{bad!r} was accepted")
    assert short_uuid(_uuid("8001")) == "8001" and short_uuid(_uuid("fee7")) == "FEE7"


def test_it_listens_before_it_writes_and_writes_where_the_app_does():
    async def go():
        phone = FakePhysicalPhone()
        assert await phone.find()
        await phone.connect()
        await phone.pair()
        services = await phone.discover()
        await phone.listen()
        reply = await phone.exchange(frame(BATTERY, CHECK, b"\x00"), timeout=1)
        await phone.disconnect()
        return phone, services, reply

    phone, services, reply = asyncio.run(go())
    log = FakePhysicalPhone.made[-1].log
    assert services["6006"] == ["8001", "8002", "8003", "8004"], services
    assert log[:4] == [("connect",), ("pair",), ("subscribe", "8002"),
                       ("subscribe", "8004")], log
    assert log[4] == ("write", "8001", bytes.fromhex("6f08700100008f"), True), log[4]
    assert log[5] == ("write", "8002", b"\x03", False), log[5]
    assert log[-1] == ("disconnect",)
    # exchange() hands back the first notification: the first 20 bytes.
    assert reply == frame(BATTERY, CHECK_RESPONSE, bytes([0x4D]) + bytes(20))[:20], reply


def test_replies_are_reassembled_and_check_init_is_read():
    async def go():
        phone = FakePhysicalPhone()
        await phone.connect()
        await phone.discover()
        await phone.listen()
        init = await phone.check_init(timeout=1)
        phone.clear()
        await phone.send(frame(BATTERY, CHECK, b"\x00"))
        return init, await phone.replies(timeout=1, settle=0.1)

    init, replies = asyncio.run(go())
    assert init == 1
    assert [c for c, _ in replies] == ["8002", "8002"], replies
    assert b"".join(v for _, v in replies) == frame(BATTERY, CHECK_RESPONSE,
                                                     bytes([0x4D]) + bytes(20))


# -- the command ---------------------------------------------------------------------

def test_cmd_mac_asks_the_physical_watch_and_decodes_like_the_emulated_one():
    with fake_watch():
        status, out = cmd("BATTERY_POWER", "CHECK", "--payload", "00", "--mac", WATCH.lower())
    assert status == 0, out
    assert f"watch: the physical one at {WATCH}" in out, out
    assert "encryption not reported" in out, out
    assert "initialised (checkInit 1), not binding" in out, out
    assert "-> 8001  6f 08 70 01 00 00 8f" in out, out
    assert "= 0x08 BATTERY_POWER CHECK_RESPONSE [4d 00" in out, out


def test_cmd_mac_binds_only_a_watch_that_says_it_is_in_setup():
    with fake_watch(init=0) as made:
        status, out = cmd("08", "70", "--payload", "00", "--mac", WATCH)
    assert status == 0, out
    assert "in first-run setup (checkInit 0)" in out, out
    assert "bound (bind acknowledged, checkInit now 1)" in out, out
    writes = [entry[2] for entry in made[-1].log if entry[0] == "write" and entry[1] == "8001"]
    assert [w[1:3] for w in writes[:4]] == [b"\x94\x70", b"\x93\x71", b"\x04\x71", b"\x94\x71"], \
        [w.hex(" ") for w in writes]


def test_cmd_mac_reports_a_set_on_8003_as_unanswered():
    with fake_watch():
        status, out = cmd("SCREEN_BRIGHTNESS", "SET", "--payload", "3c", "--char", "8003",
                          "--no-bind", "--timeout", "0.5", "--mac", WATCH)
    assert status == 1, out
    assert "-> 8003" in out and "never acknowledged" in out, out


def test_cmd_mac_setup_failures_are_status_2():
    with fake_watch(advertising=False):
        status, out = cmd("08", "70", "--payload", "00", "--mac", WATCH)
    assert status == 2 and "never heard advertising -- is a phone connected to it?" in out, out
    with fake_watch(gatt=("1800",)):
        status, out = cmd("08", "70", "--payload", "00", "--mac", WATCH)
    assert status == 2 and "no 6006 service" in out and "bonded" in out, out
    with fake_watch(answers=False):
        status, out = cmd("08", "70", "--payload", "00", "--mac", WATCH)
    assert status == 2 and f"connecting to {WATCH} timed out after 30s" in out, out


def test_cmd_mac_refuses_the_emulated_watchs_options():
    status, out = cmd("08", "70", "--mac", WATCH, "--flash-state", "x.zip")
    assert status == 2 and "--flash-state and --save" in out, out
    status, out = cmd("08", "70", "--mac", WATCH, "--save")
    assert status == 2, out
    status, out = cmd("08", "70", "--mac", "4C:59:80")
    assert status == 2 and "not a Bluetooth address" in out, out


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
