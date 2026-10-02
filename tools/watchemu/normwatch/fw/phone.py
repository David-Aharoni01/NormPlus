"""A phone on the emulated watch's air, doing what the companion app does.

Two things live here so that ``normwatch cmd`` and ``tests/test_ble_end_to_end.py``
share one implementation rather than two copies drifting apart:

* :class:`EmulatedWatch` -- the firmware on a thread, with a bumble controller
  behind its NZ8801 seam, optionally started from a saved flash state and
  optionally serving the Android emulator's netsim endpoint.
* :class:`Phone` -- a bumble host on the same air. It scans, connects, pairs
  the way ``BondingPolicy`` asks (Just Works, bonding), discovers the GATT
  table and talks the 0x6F protocol over 6006 / 8001-8004.

A phone can sit on the air two ways. :meth:`Phone.on_air` puts it on the
radio's own loop with a controller of its own -- no socket, so it runs beside
a live AVD on 8877. :meth:`Phone.over_netsim` reaches the watch through the
netsim endpoint the way the Android emulator does, which is the path the
end-to-end test exists to pin.

The 0x6F frame (``Leaf.smali``, ``:protocol``'s ``PacketBuilder``)::

    [6F][cmd][action][len lo][len hi][payload ...][8F]
"""

from __future__ import annotations

import asyncio
import contextlib
import re
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Optional

from . import flashstate
from . import image as image_mod
from .blelink import AndroidLink, BumbleController, Radio, VirtualController
from .devices import (attach_ble_controller, attach_motion_sensor,
                      attach_mspi_devices, attach_pmu, attach_touch_panel)
from .machine import Apollo3Machine

#: The physical watch's address, which the emulated one uses by default.
WATCH = "4C:59:80:12:44:F1"
#: The phone's address on the air.
PHONE = "F0:F1:F2:F3:F4:F5"

#: ``ui_notify_poweroff_dlg.c``'s boot animation; the first-run setup screen is
#: drawn after it, and the bind's pairing dialog opens on top of that.
BOOT_ANIMATION_FRAMES = 134

FLAG_START = 0x6F
FLAG_END = 0x8F
#: :protocol's PacketDeframer: a declared length above this is not a frame start.
MAX_PAYLOAD_LEN = 512

#: Action bytes, as :protocol's ``Action`` (BluetoothCommandConstant.smali).
CHECK, SET, CHECK_RESPONSE, SET_RESPONSE = 0x70, 0x71, 0x80, 0x81
ACTIONS = {CHECK: "CHECK", SET: "SET", CHECK_RESPONSE: "CHECK_RESPONSE",
           SET_RESPONSE: "SET_RESPONSE"}
#: CommandCode.RESPONSE: the 8001 dispatcher's generic reply,
#: ``6F 01 81 02 00 <cmd> <status> 8F`` (built at 0x000393B8).
RESPONSE = 0x01

DATETIME = 0x04
BIND_START = 0x93
BIND_END = 0x94
#: BindStartCommand.MODE_QR_CODE -- what BindDevice.start6F sends for the Norm 2.
BIND_MODE_QR_CODE = 2


def quiet(*_a, **_k) -> None:
    pass


# -- the 0x6F frame ------------------------------------------------------------

def frame(cmd: int, action: int, payload: bytes = b"") -> bytes:
    """``PacketBuilder.build``: start, command, action, LE16 length, payload, end."""
    return (bytes([FLAG_START, cmd & 0xFF, action & 0xFF])
            + len(payload).to_bytes(2, "little") + bytes(payload) + bytes([FLAG_END]))


def datetime_payload(now: Optional[datetime] = None, *, re_home: bool = False) -> bytes:
    """``DateTimeCommand.setPayload``'s twelve bytes, from a local time.

    ``[yLo][yHi][mo][d][h][mi][s][0][reHome][tzSign][tzHour][tzMin]``; the sign
    is 1 for '+' (``getTimeZone4City``). re_home moves the physical hands and
    belongs to calibration only.
    """
    now = now or datetime.now()
    if now.tzinfo is None:          # a naive time is local time
        now = now.astimezone()
    offset = int(now.utcoffset().total_seconds() // 60)
    minutes = abs(offset)
    return bytes([now.year & 0xFF, now.year >> 8, now.month, now.day, now.hour,
                  now.minute, now.second, 0, 1 if re_home else 0,
                  1 if offset >= 0 else 0, minutes // 60, minutes % 60])


# -- the Apollo OTA protocol (DFU service 1530) ------------------------------------
#
# As the companion app speaks it (cn.appscomm.ota.OtaApolloCommand / OtaService)
# and as this firmware's ew_mod_ota_protocol.c answers it (the handler at
# 0x0003B798). Control commands go to 0x1531, image data to 0x1532, both write
# without response -- the only write either characteristic has. Every reply is a
# notification on 0x1531: [command][status 01 = ok][...].

#: UPGRADE_MODE (0x0E) SET -- MBluetooth.enterUpdateMode. A mode switch inside
#: the running application: acknowledged on 8001, no reset.
UPGRADE_MODE = 0x0E
OTA_BT_PARAM = bytes([0x10, 0x02])
#: The update types the firmware's SET handler accepts (0x0003B8A8): it stores
#: a slot for each of 1-4 and answers anything else [02 00]. 4 is the resource
#: partition -- cn.appscomm.bluetooth.ota's UPDATE_TYPE_PICTURE_LANGUAGE. The
#: 8 in cn.appscomm.ota's getUpdateType ("Picture...") is refused.
OTA_TYPE_MCU, OTA_TYPE_TOUCH, OTA_TYPE_HEART_RATE, OTA_TYPE_RESOURCES = 1, 2, 3, 4
#: The last byte of the SET header (PACKAGE_COUNT).
OTA_PACKAGE_COUNT = 0x0A
#: The app's write size for the resource type (OtaApolloCommand.create:
#: 0x80 for type 8 there, 0x14 otherwise); needs an ATT MTU of at least 131.
OTA_WRITE_SIZE = 0x80


def apollo_crc(data: bytes) -> bytes:
    """OtaUtil.getApolloCrcCheck: CRC-16/CCITT, init 0xFFFF, as [lo, hi, 0, 0].

    The firmware's CRC step (04) accepts it: a full resource update answers
    [04 01] (README, "Rehearsing an OTA").
    """
    crc = 0xFFFF
    for byte in data:
        crc = ((crc << 8) | (crc >> 8)) & 0xFFFF
        crc ^= byte
        crc ^= (crc & 0xFF) >> 4
        crc ^= (crc << 12) & 0xFFFF
        crc ^= ((crc & 0xFF) << 5) & 0xFFFF
    return bytes([crc & 0xFF, crc >> 8, 0, 0])


def ota_init(content: bytes) -> bytes:
    """NOTE_01_INIT: [01][content length, LE32]. The firmware wants exactly 5 bytes."""
    return bytes([0x01]) + len(content).to_bytes(4, "little")


def ota_set_header(update_type: int, address: bytes, content: bytes) -> bytes:
    """NOTE_02_SET, 15 bytes: [02][type][address 4][length 4][crc 4][package count]."""
    return (bytes([0x02, update_type]) + bytes(address) + len(content).to_bytes(4, "little")
            + apollo_crc(content) + bytes([OTA_PACKAGE_COUNT]))


def ota_pieces(content: bytes) -> list:
    """The data, cut the way addMiddleCommand cuts it: each 2048-byte page in ten
    200-byte pieces and a 48-byte one, the remainder in 200s. The watch answers
    each piece; a piece that completes a NAND page is answered [03 01 02 ...]."""
    cuts = {0}
    pages = len(content) // 0x800
    at = 0
    for _ in range(pages):
        for i in range(11):
            at += 48 if i == 10 else 200
            cuts.add(at)
    remainder, step = len(content) % 0x800, 200
    while step < remainder:
        cuts.add(pages * 0x800 + step)
        step += 200
    cuts.discard(len(content))
    cuts = sorted(cuts)
    return [content[a:(cuts[i + 1] if i + 1 < len(cuts) else len(content))]
            for i, a in enumerate(cuts)]


def ack(cmd: int, status: int = 0) -> bytes:
    """The 8001 dispatcher's generic reply to a SET: ``6F 01 81 02 00 <cmd> <status> 8F``."""
    return frame(RESPONSE, SET_RESPONSE, bytes([cmd, status]))


class Deframer:
    """Whole frames out of notification chunks, by :protocol's rules.

    Length-guided like ``PacketDeframer``: the length in bytes 3..4 says where
    a frame ends, so an 0x8F inside a payload is never taken for the end. A
    start whose length is implausible, or whose frame does not end in 0x8F, was
    not a start, and the search moves one byte on.
    """

    def __init__(self) -> None:
        self.buffer = bytearray()

    def feed(self, chunk: bytes) -> list[bytes]:
        self.buffer += chunk
        out = []
        while True:
            start = self.buffer.find(FLAG_START)
            if start < 0:
                self.buffer.clear()
                break
            del self.buffer[:start]
            if len(self.buffer) < 5:
                break
            length = self.buffer[3] | (self.buffer[4] << 8)
            if length > MAX_PAYLOAD_LEN:
                del self.buffer[:1]
                continue
            size = length + 6
            if len(self.buffer) < size:
                break
            if self.buffer[size - 1] != FLAG_END:
                del self.buffer[:1]
                continue
            out.append(bytes(self.buffer[:size]))
            del self.buffer[:size]
        return out


def _protocol_names() -> dict[int, str]:
    """Command names from :protocol's CommandCode.kt, the single source of them.

    Read for display only; without the file every command is shown in hex.
    """
    source = (Path(__file__).resolve().parents[4] / "protocol/src/main/kotlin/com/"
              "norm2hacked/protocol/CommandCode.kt")
    try:
        text = source.read_text(encoding="utf-8")
    except OSError:
        return {}
    names = {}
    for name, value in re.findall(r"^\s*([A-Z][A-Z0-9_]*)\((0x[0-9A-Fa-f]+)", text, re.M):
        names.setdefault(int(value, 16) & 0xFF, name)
    return names


COMMANDS = _protocol_names()


def command_byte(text: str) -> int:
    """A command given as hex (``08``, ``0x08``) or a CommandCode name."""
    by_name = {name: code for code, name in COMMANDS.items()}
    if text.upper() in by_name:
        return by_name[text.upper()]
    return _hex_byte(text, f"a hex byte (08) or a CommandCode name (BATTERY_POWER)")


def action_byte(text: str) -> int:
    """An action given as hex (``70``) or an Action name (``check``)."""
    by_name = {name: code for code, name in ACTIONS.items()}
    if text.upper() in by_name:
        return by_name[text.upper()]
    return _hex_byte(text, "a hex byte (70) or CHECK / SET")


def _hex_byte(text: str, wanted: str) -> int:
    try:
        value = int(text, 16)
    except ValueError:
        value = -1
    if not 0 <= value <= 0xFF:
        raise ValueError(f"{text!r} is not {wanted}")
    return value


def command_name(code: int) -> str:
    name = COMMANDS.get(code)
    return f"0x{code:02X} {name}" if name else f"0x{code:02X}"


def describe(data: bytes) -> str:
    """One line for a whole frame: command, action, payload -- or what is wrong."""
    if len(data) < 6 or data[0] != FLAG_START or data[-1] != FLAG_END:
        return "not a 0x6F frame"
    length = data[3] | (data[4] << 8)
    payload = data[5:-1]
    if len(payload) != length:
        return f"length says {length}, carries {len(payload)}"
    action = ACTIONS.get(data[2], f"action 0x{data[2]:02X}")
    if data[1] == RESPONSE and data[2] == SET_RESPONSE and length == 2:
        status = payload[1]
        verdict = "ok" if status == 0 else "refused" if status == 1 else "error"
        return f"ack for {command_name(payload[0])}: status {status} ({verdict})"
    return f"{command_name(data[1])} {action} [{payload.hex(' ')}]"


# -- the watch ----------------------------------------------------------------

class EmulatedWatch:
    """The firmware on a thread, on the air, ready for a phone.

    Paced against the wall clock (``realtime``), because a phone answers it in
    real time. With *netsim_port* it also serves the Android emulator's
    endpoint, which is how :meth:`Phone.over_netsim` (and an AVD) reach it.
    """

    def __init__(self, image, resources, *, address: str = WATCH,
                 flash_state=None, netsim_port: Optional[int] = None, log=quiet) -> None:
        self.machine = Apollo3Machine(image_mod.load(image), log=log, trace=None,
                                      fast_hook=True, ble=True, realtime=True)
        self.devices = attach_mspi_devices(self.machine, resource_blob=resources, log=log)
        if flash_state is not None:
            flashstate.load(flash_state, self.machine, self.devices["nand"], log=log)
        attach_touch_panel(self.machine, log=log)
        attach_motion_sensor(self.machine, log=log)
        attach_pmu(self.machine, log=log)
        self.radio = Radio()
        self.controller = attach_ble_controller(
            self.machine, BumbleController(address, radio=self.radio, log=log), log=log)
        self.android = (AndroidLink(self.radio, netsim_port, log=log)
                        if netsim_port is not None else None)
        self.address = address
        self.stats = None
        self._thread: Optional[threading.Thread] = None

    @property
    def display(self):
        return self.devices["display"]

    def start(self, *, seconds: float = 600) -> None:
        """Run the CPU on its own thread for up to *seconds* of watch time."""
        def worker() -> None:
            self.stats = self.machine.run(max_instructions=int(seconds * 48_000_000),
                                          slice_size=4_000_000)
        self._thread = threading.Thread(target=worker, name="watch-cpu", daemon=True)
        self._thread.start()

    def stop(self, *, save_to=None) -> bool:
        """Stop the CPU and the radio; save the flash to *save_to* if given.

        Saves only once the CPU has stopped -- a save taken while it runs can
        catch a page between its erase and its program. Returns whether it did.
        """
        self.machine.stop_requested = True
        if self._thread is not None:
            self._thread.join(timeout=60)
        if self.android is not None:
            self.android.close()
        self.radio.close()
        if self._thread is not None and self._thread.is_alive():
            return False
        if save_to is not None:
            flashstate.save(save_to, self.machine, self.devices["nand"])
        return True

    async def past_boot_animation(self, *, timeout: float = 60) -> bool:
        """Wait for the setup screen and for the emulator to catch up with the wall clock.

        The bind's pairing dialog opens on top of the setup screen, so it has to
        be drawn. And while the emulator is behind real time -- drawing that
        screen puts it there -- the firmware's few-millisecond window between
        handing over a reply and clearing its 8001 buffer is tens of
        milliseconds of the phone's time (see ``Air``).
        """
        deadline = time.monotonic() + timeout
        while self.display.frames <= BOOT_ANIMATION_FRAMES:
            if time.monotonic() > deadline:
                return False
            await asyncio.sleep(0.1)
        while self.machine.realtime_lag >= 0.01:
            if time.monotonic() > deadline:
                return False
            await asyncio.sleep(0.1)
        return True

    def drive(self, flow, *, timeout: float, keystore=None, address: str = PHONE):
        """Run ``await flow(phone)`` with a phone on this watch's air.

        Over netsim when this watch serves it, otherwise on the radio's own
        loop. Raises whatever the flow raises, or TimeoutError.
        """
        async def go():
            opener = (Phone.over_netsim(self.android.port, address=address, keystore=keystore)
                      if self.android is not None
                      else Phone.on_air(self.radio, address=address, keystore=keystore))
            async with opener as phone:
                return await flow(phone)

        if self.android is not None:
            return asyncio.run(asyncio.wait_for(go(), timeout))
        return self.radio.run(asyncio.wait_for(go(), timeout), timeout + 5)


# -- the phone ------------------------------------------------------------------

class Phone:
    """One bumble host, one connection to the watch, and its notifications.

    Every notification from 8002 and 8004 lands on one queue, subscribed
    before anything is written -- the same ordering ``BleWriteQueue`` keeps so
    that a reply cannot arrive before anyone is listening for it.
    """

    def __init__(self, device) -> None:
        from bumble.pairing import PairingConfig, PairingDelegate

        self.device = device
        # BondingPolicy: Just Works, bonding, no MITM.
        device.pairing_config_factory = lambda _connection: PairingConfig(
            sc=True, mitm=False, bonding=True,
            delegate=PairingDelegate(io_capability=PairingDelegate.NO_OUTPUT_NO_INPUT))
        self.seen: dict = {}
        self.connection = None
        self.peer = None
        self.services: dict = {}
        self.chars: dict = {}
        self.paired = False
        #: Every notification, in order, as (characteristic, bytes).
        self.notifications: list = []
        self._queue: asyncio.Queue = asyncio.Queue()

    @classmethod
    @contextlib.asynccontextmanager
    async def on_air(cls, radio, *, address: str = PHONE, keystore=None):
        """A phone with a controller of its own on *radio*'s air.

        Must run on the radio's loop (``EmulatedWatch.drive`` sees to that):
        ``LocalLink`` only connects controllers that share one.
        """
        from bumble.device import Device
        from bumble.hci import Address

        controller = VirtualController("phone", link=radio.link, public_address=address)
        device = Device.with_hci("phone", Address(address), controller, controller)
        if keystore is not None:
            device.keystore = keystore
        await device.power_on()
        try:
            yield cls(device)
        finally:
            radio.link.remove_controller(controller)

    @classmethod
    @contextlib.asynccontextmanager
    async def over_netsim(cls, port: int, *, address: str = PHONE, keystore=None):
        """A phone reaching the watch through the netsim endpoint, as the AVD does."""
        from bumble.device import Device
        from bumble.hci import Address
        from bumble.transport import open_transport

        async with await open_transport(f"android-netsim:localhost:{port}") as (src, sink):
            device = Device.with_hci("phone", Address(address), src, sink)
            if keystore is not None:
                device.keystore = keystore
            await device.power_on()
            yield cls(device)

    @property
    def keystore(self):
        """The phone's half of any bond it made; hand it to a later phone."""
        return self.device.keystore

    async def find(self, address: str = WATCH, *, timeout: float = 10.0) -> bool:
        """Scan until *address* advertises. ``seen`` keeps what was heard."""
        self.device.on("advertisement", lambda adv: self.seen.setdefault(
            str(adv.address), (adv.data.get(0x09), bytes(adv.data_bytes))))
        await self.device.start_scanning(legacy=True)
        deadline = time.monotonic() + timeout
        while not any(a.startswith(address) for a in self.seen):
            if time.monotonic() > deadline:
                break
            await asyncio.sleep(0.1)
        await self.device.stop_scanning()
        return any(a.startswith(address) for a in self.seen)

    async def connect(self, address: str = WATCH, *, timeout: float = 10.0) -> None:
        self.connection = await self.device.connect(f"{address}/P", timeout=timeout)
        self.connection.on(self.connection.EVENT_PAIRING,
                           lambda *_a: setattr(self, "paired", True))

    async def pair(self, *, timeout: float = 20.0) -> None:
        await asyncio.wait_for(self.connection.pair(), timeout)

    async def encrypt(self, *, timeout: float = 10.0) -> None:
        """Encrypt with keys from an earlier pairing; raises if the watch has none."""
        await asyncio.wait_for(self.connection.encrypt(), timeout)

    @property
    def encrypted(self) -> bool:
        return bool(self.connection is not None and self.connection.is_encrypted)

    async def discover(self, *, timeout: float = 30.0) -> dict:
        """The GATT table, as {service: [characteristics]} by their last four digits."""
        from bumble.device import Peer

        self.peer = Peer(self.connection)
        await asyncio.wait_for(self.peer.discover_all(), timeout)
        self.services = {str(s.uuid)[-4:]: [str(c.uuid)[-4:] for c in s.characteristics]
                         for s in self.peer.services}
        main = next(s for s in self.peer.services if str(s.uuid).endswith("6006"))
        self.chars = {str(c.uuid)[-4:]: c for c in main.characteristics}
        return self.services

    async def listen(self) -> None:
        """Subscribe to 8002 and 8004, before the first write."""
        for name in ("8002", "8004"):
            def on_value(value, name=name):
                item = (name, bytes(value))
                self.notifications.append(item)
                self._queue.put_nowait(item)
            await self.peer.subscribe(self.chars[name], on_value)

    async def send(self, data: bytes, *, char: str = "8001", trigger: bool = True) -> None:
        """Write a frame, then ``[03]`` to 8002: "process it now", as the app does."""
        await self.peer.write_value(self.chars[char], data, with_response=True)
        if trigger:
            await self.peer.write_value(self.chars["8002"], b"\x03", with_response=False)

    def clear(self) -> None:
        """Forget notifications nobody has read, so the next reply is the next one."""
        while not self._queue.empty():
            self._queue.get_nowait()

    async def exchange(self, data: bytes, *, char: str = "8001", trigger: bool = True,
                       timeout: float = 10.0) -> Optional[bytes]:
        """Send a frame; the next notification is its reply, or None.

        Sent the moment it is called, on purpose: the firmware clears its 8001
        buffer some milliseconds *after* handing over a reply, and a frame that
        lands in between is wiped and answered ``00 02``. ``Air`` keeps that
        from happening the way a radio does (one connection event per
        interval), so back-to-back exchanges are a test of it.
        """
        self.clear()
        await self.send(data, char=char, trigger=trigger)
        try:
            _, value = await asyncio.wait_for(self._queue.get(), timeout)
            return value
        except asyncio.TimeoutError:
            return None

    async def replies(self, *, timeout: float, settle: float = 0.5) -> list:
        """Notifications from now on: until a whole frame has come and *settle*
        seconds pass without another, or until *timeout*."""
        got, deframer, whole = [], Deframer(), False
        deadline = time.monotonic() + timeout
        while True:
            left = deadline - time.monotonic()
            if left <= 0:
                break
            try:
                item = await asyncio.wait_for(self._queue.get(), min(left, settle) if whole else left)
            except asyncio.TimeoutError:
                break
            got.append(item)
            whole = whole or bool(deframer.feed(item[1]))
        return got

    async def check_init(self, *, timeout: float = 10.0) -> Optional[int]:
        """MBluetooth.checkInit: 0x94 CHECK; the reply's byte is the init flag."""
        reply = await self.exchange(frame(BIND_END, CHECK, b"\x00"), timeout=timeout)
        if reply and reply[:5] == bytes([FLAG_START, BIND_END, CHECK_RESPONSE, 1, 0]):
            return reply[5]
        return None

    async def bind(self, *, clock: Optional[bytes] = None, timeout: float = 10.0) -> dict:
        """BindDevice.start6F for the Norm 2: bindStart (QR mode), setDateTime, bindEnd.

        Back to back, as the app's queue sends them: bindStart opens a 300-frame
        window on the watch that only bindEnd closes. Returns each reply.
        """
        clock = clock if clock is not None else datetime_payload()
        return {
            "start": await self.exchange(frame(BIND_START, SET, bytes([BIND_MODE_QR_CODE])),
                                         timeout=timeout),
            "datetime": await self.exchange(frame(DATETIME, SET, clock), timeout=timeout),
            "end": await self.exchange(frame(BIND_END, SET, b"\x01"), timeout=timeout),
        }

    # -- OTA -------------------------------------------------------------------

    async def open_dfu(self, *, mtu: int = 247) -> None:
        """Find the DFU service, raise the MTU for the data writes, and listen
        to 0x1531 -- the only one of its characteristics that notifies."""
        await self.peer.request_mtu(mtu)
        service = next(s for s in self.peer.services if str(s.uuid)[-4:].upper() == "1530")
        self.dfu = {str(c.uuid)[-4:]: c for c in service.characteristics}
        self._dfu_queue: asyncio.Queue = asyncio.Queue()
        await self.peer.subscribe(self.dfu["1531"],
                                  lambda value: self._dfu_queue.put_nowait(bytes(value)))

    async def _dfu_reply(self, timeout: float) -> Optional[bytes]:
        try:
            return await asyncio.wait_for(self._dfu_queue.get(), timeout)
        except asyncio.TimeoutError:
            return None

    async def dfu_command(self, data: bytes, *, timeout: float = 5.0) -> Optional[bytes]:
        """A control command to 0x1531; the reply, or None. (BT_PARAM has none:
        the app waits 500 ms after it and carries on.)"""
        while not self._dfu_queue.empty():
            self._dfu_queue.get_nowait()
        await self.peer.write_value(self.dfu["1531"], data, with_response=False)
        return await self._dfu_reply(timeout)

    async def dfu_piece(self, piece: bytes, *, timeout: float = 5.0) -> Optional[bytes]:
        """One data piece to 0x1532, in OTA_WRITE_SIZE writes; the watch's answer."""
        for at in range(0, len(piece), OTA_WRITE_SIZE):
            await self.peer.write_value(self.dfu["1532"], piece[at:at + OTA_WRITE_SIZE],
                                        with_response=False)
        return await self._dfu_reply(timeout)

    async def disconnect(self) -> None:
        if self.connection is not None:
            await self.connection.disconnect()
