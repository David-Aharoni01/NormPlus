"""The physical watch, over this PC's own Bluetooth adapter.

``normwatch cmd --mac MAC`` asks the real watch what ``normwatch cmd`` asks
the emulated one: the same flow (pair, checkInit, the bind only if it reads 0,
the frame to 8001 and the ``[03]`` trigger) and every reply decoded by the same
code in ``fw/phone.py``. Where the two answers differ, the watches differ, not
the tools -- which makes it the way to check an emulator finding on hardware.

It replaced normlink-cli (the ``:cli`` Gradle module and its Python bridge).
That wrote to 8003 whenever the watch had it, and the firmware's 8003
dispatcher throws a handler's result away, so every SET it sent timed out
(README, "Binding").

:class:`PhysicalPhone` is :class:`fw.phone.Phone` over bleak instead of a
bumble host. The watch keeps one connection: the phone's companion app, ``:app``
in the AVD (through the dongle -- another adapter, but the same watch) and this
cannot all hold it at once.

**On Windows the watch must be bonded with the PC first.** Unbonded,
characteristic discovery fails (``GattCommunicationStatus.Unreachable``): the
watch drops the link during Windows' slow live discovery. Bonded, Windows has
the GATT table cached and discovery is instant. The bond has to be made with a
*custom* ceremony -- ``ConfirmOnly`` at protection level ``None``, the request
accepted from a ``PairingRequested`` handler -- because the default one is
refused by this headless Just Works peripheral (the watch's Pairing Response
asks for bonding, no MITM, legacy pairing; README, "A real stack behind the
seam"). The bond is the operating system's, made once and kept. All of this
was learned against the watch by normlink-cli's bridge, and is carried over
unchanged.
"""

from __future__ import annotations

import asyncio
import contextlib
import re
import sys
from typing import Optional

from .fw.phone import WATCH, Conversation, quiet

#: The 0x6F service, and the characteristics a phone listens to.
MAIN_SERVICE = "6006"
NOTIFY = ("8002", "8004")
#: The Apollo DFU service and its two characteristics: control in and every
#: reply out on 1531, image data in on 1532 (docs/protocol.md, "Apollo DFU").
DFU_SERVICE = "1530"
DFU_CONTROL, DFU_DATA = "1531", "1532"
_MAC = re.compile(r"^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")


def mac_address(text: str) -> str:
    """A Bluetooth address as ``4C:59:80:12:44:F1``, or ValueError."""
    mac = text.strip().upper().replace("-", ":")
    if not _MAC.match(mac):
        raise ValueError(f"{text!r} is not a Bluetooth address (like {WATCH})")
    return mac


def short_uuid(uuid) -> str:
    """``00008001-0000-1000-8000-00805f9b34fb`` -> ``8001``: the 16-bit part of
    a UUID on the Bluetooth base, which is how bleak spells every one."""
    return str(uuid)[4:8].upper()


async def bond_windows(address: str, *, log=quiet) -> bool:
    """Make sure Windows holds a bond with the watch, making one if it does not.

    The ceremony norm2_probe's ``pair`` established: ConfirmOnly, protection
    level None, accepted in the PairingRequested handler. Returns whether it
    made one; raises RuntimeError with the WinRT status if it fails.
    """
    from winrt.windows.devices.bluetooth import BluetoothLEDevice
    from winrt.windows.devices.enumeration import (DevicePairingKinds,
                                                   DevicePairingProtectionLevel,
                                                   DevicePairingResultStatus)

    device = await BluetoothLEDevice.from_bluetooth_address_async(
        int(address.replace(":", ""), 16))
    if device is None:
        raise RuntimeError(f"Windows cannot open {address}")
    pairing = device.device_information.pairing
    if pairing.is_paired:
        return False
    log(f"bonding {address} with this PC, once: Just Works (ConfirmOnly, protection None)")
    custom = pairing.custom
    token = custom.add_pairing_requested(lambda _sender, request: request.accept())
    try:
        result = await custom.pair_with_protection_level_async(
            DevicePairingKinds.CONFIRM_ONLY, DevicePairingProtectionLevel.NONE)
    finally:
        custom.remove_pairing_requested(token)
    if result.status not in (DevicePairingResultStatus.PAIRED,
                             DevicePairingResultStatus.ALREADY_PAIRED):
        raise RuntimeError(f"Windows could not bond with the watch: {result.status.name}. "
                           "Is it awake, on its face, and not connected to a phone?")
    return True


class PhysicalPhone(Conversation):
    """One bleak client, one connection to the physical watch, and its notifications.

    *client_factory* makes the client for an address or a found device
    (``BleakClient`` unless a test passes a fake).
    """

    def __init__(self, *, client_factory=None, log=quiet) -> None:
        super().__init__()
        self.log = log
        self._client_factory = client_factory
        self._device = None
        self.client = None
        self.seen: dict = {}
        self.services: dict = {}
        self.chars: dict = {}
        self.paired = False

    @classmethod
    @contextlib.asynccontextmanager
    async def open(cls, *, log=quiet):
        """A phone that lets go of the watch at the end, however the flow ends."""
        phone = cls(log=log)
        try:
            yield phone
        finally:
            with contextlib.suppress(Exception):
                await phone.disconnect()

    async def find(self, address: str = WATCH, *, timeout: float = 15.0) -> bool:
        """Scan until *address* advertises; whether it was heard."""
        from bleak import BleakScanner

        self._device = await BleakScanner.find_device_by_address(address, timeout=timeout)
        if self._device is not None:
            self.seen[self._device.address] = self._device.name
        return self._device is not None

    async def connect(self, address: str = WATCH, *, timeout: float = 30.0) -> None:
        """Bond first on Windows (see the module docstring), then connect; bleak
        discovers the services as part of connecting.

        A connect straight after a fresh bond is tried twice: the first one
        after the bond was made on 2026-10-02 ran out its 30 s, and the next,
        a minute later, connected in 1.3 s.
        """
        bonded_now = False
        if sys.platform == "win32" and self._client_factory is None:
            bonded_now = await bond_windows(address, log=self.log)
            self.paired = True
        if self._client_factory is None:
            from bleak import BleakClient
            self._client_factory = BleakClient
        for attempt in (1, 2) if bonded_now else (1,):
            self.client = self._client_factory(self._device or address)
            try:
                await self.client.connect(timeout=timeout)
                return
            except asyncio.TimeoutError:
                if attempt == 2 or not bonded_now:
                    raise RuntimeError(f"connecting to {address} timed out after "
                                       f"{timeout:g}s") from None
                self.log(f"the first connect after bonding timed out after {timeout:g}s; "
                         "trying once more")

    async def pair(self, *, timeout: float = 20.0) -> None:
        """Already bonded on Windows (in :meth:`connect`); elsewhere bleak pairs."""
        if not self.paired:
            await asyncio.wait_for(self.client.pair(), timeout)
            self.paired = True

    @property
    def encrypted(self) -> Optional[bool]:
        """bleak does not report the link's encryption: None, for "not known"."""
        return None

    async def discover(self, *, timeout: float = 30.0) -> dict:
        """The GATT table, as {service: [characteristics]} by their 16-bit parts."""
        services = list(self.client.services)
        self.services = {short_uuid(s.uuid): [short_uuid(c.uuid) for c in s.characteristics]
                         for s in services}
        main = next((s for s in services if short_uuid(s.uuid) == MAIN_SERVICE), None)
        if main is None:
            raise RuntimeError(f"the watch has no {MAIN_SERVICE} service (found "
                               f"{', '.join(sorted(self.services)) or 'none'}); "
                               "is it bonded with this PC?")
        self.chars = {short_uuid(c.uuid): c for c in main.characteristics}
        return self.services

    async def listen(self) -> None:
        """Subscribe to 8002 and 8004, before the first write."""
        for name in NOTIFY:
            await self.client.start_notify(
                self.chars[name], lambda _char, value, name=name: self._heard(name, value))

    async def send(self, data: bytes, *, char: str = "8001", trigger: bool = True) -> None:
        """Write a frame, then ``[03]`` to 8002: "process it now", as the app does."""
        await self.client.write_gatt_char(self.chars[char], data, response=True)
        if trigger:
            await self.client.write_gatt_char(self.chars["8002"], b"\x03", response=False)

    async def request_mtu(self, mtu: int = 247) -> int:
        """The MTU is the OS's to negotiate here; this reports what it got.

        WinRT settles it when the link comes up and bleak only reads it back, so
        unlike the bumble phone there is nothing to ask for -- worth knowing for a
        dump, where the MTU decides how many notifications an answer takes (#69).
        """
        return getattr(self.client, "mtu_size", 0) or 0

    # -- the Apollo DFU service ------------------------------------------------
    #
    # The same four calls the bumble phone has (``fw.phone.Phone``), so one OTA
    # sender drives the emulated watch and this one. Both characteristics are
    # write-without-response only, and every reply is a notification on 1531.

    async def open_dfu(self, *, mtu: int = 247) -> None:
        """Find the DFU service and listen to 1531, the one that notifies."""
        service = next((s for s in self.client.services
                        if short_uuid(s.uuid) == DFU_SERVICE), None)
        if service is None:
            raise RuntimeError(f"the watch has no {DFU_SERVICE} DFU service")
        self.dfu = {short_uuid(c.uuid): c for c in service.characteristics}
        missing = [n for n in (DFU_CONTROL, DFU_DATA) if n not in self.dfu]
        if missing:
            raise RuntimeError(f"the DFU service has no {', '.join(missing)}")
        self._dfu_queue: asyncio.Queue = asyncio.Queue()
        await self.client.start_notify(
            self.dfu[DFU_CONTROL],
            lambda _char, value: self._dfu_queue.put_nowait(bytes(value)))

    async def _dfu_reply(self, timeout: float) -> Optional[bytes]:
        try:
            return await asyncio.wait_for(self._dfu_queue.get(), timeout)
        except asyncio.TimeoutError:
            return None

    async def dfu_command(self, data: bytes, *, timeout: float = 5.0) -> Optional[bytes]:
        """A control command to 1531; the reply, or None (BT_PARAM has none)."""
        while not self._dfu_queue.empty():
            self._dfu_queue.get_nowait()
        await self.client.write_gatt_char(self.dfu[DFU_CONTROL], data, response=False)
        return await self._dfu_reply(timeout)

    async def dfu_piece(self, piece: bytes, *, timeout: float = 5.0,
                        write_size: int = 0x80) -> Optional[bytes]:
        """One data piece to 1532, in *write_size* writes; the watch's answer."""
        for at in range(0, len(piece), write_size):
            await self.client.write_gatt_char(
                self.dfu[DFU_DATA], piece[at:at + write_size], response=False)
        return await self._dfu_reply(timeout)

    async def disconnect(self) -> None:
        if self.client is not None and self.client.is_connected:
            await self.client.disconnect()
