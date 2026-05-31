"""BLE connection and communication management."""

import asyncio
import sys
import time
from typing import Optional

try:
    from bleak import BleakScanner, BleakClient
    from bleak.backends.characteristic import BleakGATTCharacteristic
    from bleak.backends.device import BLEDevice
except ImportError:
    raise ImportError("bleak not installed. Run: pip install bleak")

from . import protocol


class Norm2Probe:
    def __init__(self):
        self.client: Optional[BleakClient] = None
        self.mac: Optional[str] = None
        self.notify_queue: asyncio.Queue = asyncio.Queue()
        self.deframer = protocol.PacketDeframer()
        self.use_trigger = True
        self.write_char = protocol.CHAR_WRITE_8001
        self.notify_chars: list[str] = []
        # WinRT objects — kept alive to hold the BLE connection open
        self._winrt_device = None
        self._winrt_session = None   # GattSession(maintain_connection=True)
        self._winrt_write_char = None

    # ── Connection ─────────────────────────────────────────────────────────

    async def scan(self, duration: float = 10.0):
        print(f"Scanning all BLE devices for {duration}s ...")
        seen: dict = {}

        def cb(device, adv):
            if device.address not in seen:
                seen[device.address] = device
                name = device.name or "?"
                svc_hint = ""
                if adv.service_uuids:
                    shorts = [str(u)[4:8].upper() for u in adv.service_uuids]
                    svc_hint = f"  svcs=[{', '.join(shorts)}]"
                norm_marker = "  <- NORM2" if (name and "NORM" in name.upper()) or \
                    protocol.SERVICE_MAIN.lower() in [str(u).lower() for u in (adv.service_uuids or [])] else ""
                print(f"  {device.address}  name={name}{svc_hint}{norm_marker}")

        scanner = BleakScanner(detection_callback=cb)
        await scanner.start()
        await asyncio.sleep(duration)
        await scanner.stop()
        print(f"Scan complete. {len(seen)} devices found.")
        return list(seen.values())

    async def connect(self, mac: str):
        print(f"Scanning briefly to locate {mac} ...")
        device = await BleakScanner.find_device_by_address(mac, timeout=15.0)
        if device is None:
            print(f"  Device {mac} not found. Is the watch awake and in range?")
            return
        print(f"  Found: {device.name or '?'}  address={device.address}")

        # On Windows, the watch must be BONDED or Windows refuses characteristic
        # discovery (GATT returns Unreachable; the watch's watchdog drops the link
        # during slow live discovery). Once bonded, Windows caches the GATT table
        # and standard discovery is instant. Auto-bond via custom Just Works pairing.
        if sys.platform == "win32":
            if not await self._ensure_paired(mac):
                print("  Could not bond with the watch — cannot continue on Windows.")
                return

        import traceback
        self.client = BleakClient(device)
        try:
            await self.client.connect(timeout=30.0)
        except Exception as e:
            print(f"  FAILED: {type(e).__name__}: {e}")
            traceback.print_exc()
            return

        n = len(list(self.client.services))
        if n == 0:
            print("  0 services discovered -- something went wrong")
            if sys.platform == "win32":
                print("  The watch may have dropped the bond. Re-run: python -m norm2_probe pair " + mac)
            return
        print(f"  Connected — {n} services discovered.")
        self.mac = mac
        await self._setup_notifications()

    async def _ensure_paired(self, mac: str) -> bool:
        """Return True if the device is bonded (pairing if necessary)."""
        try:
            from winrt.windows.devices.bluetooth import BluetoothLEDevice
            mac_int = int(mac.replace(":", ""), 16)
            dev = await BluetoothLEDevice.from_bluetooth_address_async(mac_int)
            if dev is not None and dev.device_information.pairing.is_paired:
                return True
        except Exception as e:
            print(f"  [pairing check] {type(e).__name__}: {e}")
        print("  Not bonded yet — initiating one-time pairing ...")
        return await self.pair(mac)

    # DevicePairingResultStatus enum → name
    _PAIR_STATUS = {
        0: "Paired", 1: "NotReadyToPair", 2: "NotPaired", 3: "AlreadyPaired",
        4: "ConnectionRejectedByDevice", 5: "TooManyConnections", 6: "HardwareFailure",
        7: "AuthenticationTimeout", 8: "AuthenticationNotAllowed", 9: "AuthenticationFailure",
        10: "NoPairingPacket", 11: "ConnectionRejected", 12: "RejectedByHandler",
        13: "RemoveDeviceFailed", 14: "AlreadyPaired", 15: "OperationAlreadyInProgress",
        16: "RequiredHandlerNotRegistered", 17: "RejectedByHandler", 18: "RemoteDeviceHasAssociation",
        19: "Failed",
    }

    async def pair(self, mac: str) -> bool:
        """
        Pair (bond) the watch with Windows using a *custom* Just Works ceremony.

        The Norm 2 expects BLE bonding — its Android companion calls
        BluetoothDevice.createBond() (see bluetooth_bond/BluetoothUtils.smali).
        The watch is a headless "Just Works" peripheral: no PIN, no MITM. The
        default DeviceInformation.Pairing.PairAsync() negotiates a protection
        level / ceremony the watch rejects (ConnectionRejected / status 19).

        The fix is CUSTOM pairing: register a PairingRequested handler that
        auto-accepts, and explicitly request DevicePairingKinds.ConfirmOnly with
        DevicePairingProtectionLevel.None (Just Works). Once bonded, Windows caches
        the GATT table so service/characteristic discovery is instant and the
        watch's connection watchdog never fires — unblocking BOTH this probe and
        the Kotlin CLI (which connects to the now-cached device by address).

        Returns True on success or if already paired.
        """
        if sys.platform != "win32":
            print("  pair command is Windows-only.")
            return False

        try:
            from winrt.windows.devices.bluetooth import BluetoothLEDevice
            from winrt.windows.devices.enumeration import (
                DevicePairingKinds, DevicePairingProtectionLevel,
            )
        except ImportError as e:
            print(f"  Missing WinRT enumeration package: {e}")
            print("  Run: pip install winrt-Windows.Devices.Enumeration")
            return False

        try:
            mac_int = int(mac.replace(":", ""), 16)

            # Warm the OS BLE cache via bleak (handles WinRT delegate marshaling)
            print(f"  Scanning to locate {mac} ...")
            await BleakScanner.find_device_by_address(mac, timeout=15.0)

            print(f"  Getting device {mac} ...")
            dev = await BluetoothLEDevice.from_bluetooth_address_async(mac_int)
            if dev is None:
                print("  Device not found (not advertising?). Wake the watch and try again.")
                return False
            print(f"  Found: {dev.name!r}")

            pairing = dev.device_information.pairing
            if pairing.is_paired:
                print("  Already paired with Windows!")
                return True

            custom = pairing.custom

            def on_pairing_requested(sender, args):
                kind = args.pairing_kind
                print(f"    [PairingRequested] kind={kind} — auto-accepting")
                try:
                    if kind == DevicePairingKinds.PROVIDE_PIN:
                        args.accept_with_password("000000")
                    else:
                        # ConfirmOnly / ConfirmPinMatch / DisplayPin → accept()
                        args.accept()
                except Exception as ex:
                    print(f"    [PairingRequested] accept failed: {ex}")

            token = custom.add_pairing_requested(on_pairing_requested)
            try:
                # Try ceremonies from least to most strict. Just Works first.
                attempts = [
                    ("ConfirmOnly + None", DevicePairingKinds.CONFIRM_ONLY,
                     DevicePairingProtectionLevel.NONE),
                    ("ConfirmOnly + Encryption", DevicePairingKinds.CONFIRM_ONLY,
                     DevicePairingProtectionLevel.ENCRYPTION),
                    ("ProvidePin + None", DevicePairingKinds.PROVIDE_PIN,
                     DevicePairingProtectionLevel.NONE),
                ]
                for label, kinds, level in attempts:
                    print(f"  Custom pairing: {label} ...")
                    # pywinrt renames the 2-arg PairAsync overload
                    result = await custom.pair_with_protection_level_async(kinds, level)
                    status = int(result.status)
                    name = self._PAIR_STATUS.get(status, f"Unknown({status})")
                    if status in (0, 3):
                        print(f"  [OK] Pairing succeeded: {name}")
                        print("  Windows GATT cache is now populated -- connect is instant.")
                        return True
                    print(f"    -> {name} (status={status})")
                    if status == 19 or name == "OperationAlreadyInProgress":
                        await asyncio.sleep(1.0)
                print("  All custom pairing ceremonies failed.")
                print("  Make sure the watch is awake and on its watch face (not in a menu).")
                return False
            finally:
                custom.remove_pairing_requested(token)

        except Exception as e:
            import traceback
            print(f"  Pairing error: {type(e).__name__}: {e}")
            traceback.print_exc()
            return False

    async def _connect_windows_no_pairing(self, mac: str, device: "BLEDevice") -> bool:
        """
        Connect to Norm 2 on Windows.

        Preferred path: device already paired via Windows Settings or `python -m norm2_probe pair <MAC>`.
        Paired = Windows GATT cache is populated = service discovery is instant = watchdog never fires.

        Fallback: GattSession(maintain_connection=True) + concurrent keepalive + limited bleak discovery.
        This only works if the watch's watchdog window is long enough for ~15 ATT round trips.
        If it fails, pair first: `python -m norm2_probe pair <MAC>` (one-time, no user interaction
        for Just Works BLE devices).
        """
        import traceback

        # Step 1: Get WinRT char 8001 handle using GattSession(maintain_connection=True)
        # This is the critical fix -- without the session the link drops mid-discovery.
        write_char_winrt = await self._winrt_get_write_char(mac)

        if write_char_winrt is None:
            print("  [WinRT] Could not get char 8001 -- no keepalive channel available.")
            print("  [WinRT] Attempting limited bleak discovery anyway (service 6006 only).")
        else:
            self._winrt_write_char = write_char_winrt

        # Keepalive: BATTERY_POWER CHECK [0x6F 0x08 0x70 0x01 0x00 0x00 0x8F]
        # Small, valid, no side effects. Tells the watch a real app is connected.
        keepalive = bytes([0x6F, 0x08, 0x70, 0x01, 0x00, 0x00, 0x8F])

        async def _send_keepalive_winrt() -> bool:
            if write_char_winrt is None:
                return False
            try:
                from winrt.windows.storage.streams import DataWriter
                from winrt.windows.devices.bluetooth.genericattributeprofile import (
                    GattCommunicationStatus, GattWriteOption,
                )
                writer = DataWriter()
                writer.write_bytes(keepalive)
                buf = writer.detach_buffer()
                result = await write_char_winrt.write_value_with_result_and_option_async(
                    buf, GattWriteOption.WRITE_WITH_RESPONSE
                )
                return result.status == GattCommunicationStatus.SUCCESS
            except Exception as ex:
                print(f"    [keepalive] {type(ex).__name__}: {ex}")
                return False

        # Step 2: Send initial keepalive immediately -- satisfies the watchdog
        if write_char_winrt is not None:
            print(f"  [WinRT] Sending initial keepalive [{keepalive.hex()}] ...")
            ok = await _send_keepalive_winrt()
            if ok:
                print("  [WinRT] Keepalive OK -- watchdog satisfied")
            else:
                print("  [WinRT] Keepalive FAILED -- will attempt discovery anyway")

        # Step 3: Background keepalive every 1s during bleak discovery
        stop = asyncio.Event()
        ka_count = [0]
        ka_fail = [0]

        async def _keepalive_loop():
            while not stop.is_set():
                try:
                    await asyncio.wait_for(stop.wait(), timeout=1.0)
                except asyncio.TimeoutError:
                    pass
                if stop.is_set():
                    break
                try:
                    ok = await _send_keepalive_winrt()
                    ka_count[0] += 1
                    if ok:
                        print(f"  [keepalive #{ka_count[0]}] sent")
                    else:
                        ka_fail[0] += 1
                        print(f"  [keepalive #{ka_count[0]}] FAILED ({ka_fail[0]} failures)")
                except asyncio.CancelledError:
                    return

        ka_task = asyncio.create_task(_keepalive_loop())

        # Step 4: bleak connect -- limited to service 6006 to reduce ATT round trips
        success = False
        try:
            print("  [bleak] Connecting (service 6006 only) ...")
            self.client = BleakClient(device, services={protocol.SERVICE_MAIN})
            try:
                await self.client.connect(timeout=30.0)
            except Exception as e:
                if not self.client.is_connected:
                    print(f"  [bleak] FAILED: {type(e).__name__}: {e}")
                    traceback.print_exc()
                    return False
                print(f"  [bleak] Exception but is_connected=True: {e}")

            n_svcs = len(list(self.client.services))
            print(f"  [bleak] Connected! {n_svcs} services discovered.")
            if n_svcs == 0:
                print("  [bleak] 0 services -- discovery failed.")
                return False

            self.mac = mac
            await self._setup_notifications()
            success = True
            return True

        finally:
            stop.set()
            ka_task.cancel()
            try:
                await ka_task
            except asyncio.CancelledError:
                pass
            if not success and self.client and self.client.is_connected:
                try:
                    await self.client.disconnect()
                except Exception:
                    pass

    async def _winrt_get_write_char(self, mac: str):
        """
        Get a direct WinRT handle to char 8001 using only 2 targeted ATT queries.

        KEY FIX: Creates GattSession(maintain_connection=True) before any ATT queries.
        Without this, Windows's BLE power manager drops the link between queries,
        causing get_characteristics_for_uuid_async to return status=1 (Unreachable).

        With maintain_connection=True, Windows holds the BLE link open indefinitely
        (reconnects if dropped), so all ATT queries complete successfully.

        The GattSession is stored in self._winrt_session to prevent GC.
        Bleak creates its own GattSession for the same device -- multiple sessions
        to the same device are supported on Windows (they share the BLE link).
        """
        try:
            from uuid import UUID as _UUID
            from winrt.windows.devices.bluetooth import BluetoothLEDevice
            from winrt.windows.devices.bluetooth.genericattributeprofile import (
                GattCommunicationStatus, GattSession,
            )

            mac_int = int(mac.replace(":", ""), 16)

            print("  [WinRT] Getting device ...")
            dev = await BluetoothLEDevice.from_bluetooth_address_async(mac_int)
            if dev is None:
                print("  [WinRT] Device not found (not advertising?)")
                return None
            print(f"  [WinRT] Device: {dev.name!r}")
            self._winrt_device = dev  # keep alive

            # CRITICAL: Create GattSession with maintain_connection=True BEFORE queries.
            # This is what bleak's backend does -- it holds the BLE link open so ATT
            # round trips don't get cancelled by Windows power management.
            print("  [WinRT] Creating GattSession (maintain_connection=True) ...")
            session = await GattSession.from_device_id_async(dev.bluetooth_device_id)
            session.maintain_connection = True
            self._winrt_session = session  # must keep reference -- GC = session closed = link drops
            print(f"  [WinRT] GattSession ready  MTU={session.max_pdu_size}")

            # Targeted ATT query: Find By Type Value for UUID 6006 (1 round trip)
            print("  [WinRT] Querying service 6006 ...")
            r = await dev.get_gatt_services_for_uuid_async(_UUID(protocol.SERVICE_MAIN))
            if r is None or r.status != GattCommunicationStatus.SUCCESS:
                status = r.status if r else "None"
                print(f"  [WinRT] Service 6006 failed: status={status}")
                return None
            svcs = list(r.services)
            if not svcs:
                print("  [WinRT] Service 6006 not found on device")
                return None
            print("  [WinRT] Got service 6006")

            # Targeted ATT query: Read By Type for UUID 8001 in service 6006 (1 round trip).
            # Retry up to 5 times: maintain_connection=True will reconnect if the device drops,
            # but the first query might fail during the brief gap before reconnect completes.
            print("  [WinRT] Querying char 8001 ...")
            for attempt in range(5):
                # Re-fetch the service on each retry: the service object may be stale after reconnect.
                if attempt > 0:
                    await asyncio.sleep(0.5)
                    print(f"  [WinRT] Retry {attempt}/4: re-fetching service 6006 ...")
                    r_svc = await dev.get_gatt_services_for_uuid_async(_UUID(protocol.SERVICE_MAIN))
                    if r_svc is None or r_svc.status != GattCommunicationStatus.SUCCESS:
                        print(f"  [WinRT] Service re-fetch failed: status={r_svc.status if r_svc else 'None'}")
                        continue
                    fresh_svcs = list(r_svc.services)
                    if not fresh_svcs:
                        print("  [WinRT] Service 6006 not found on retry")
                        continue
                    svc = fresh_svcs[0]
                else:
                    svc = svcs[0]

                r = await svc.get_characteristics_for_uuid_async(_UUID(protocol.CHAR_WRITE_8001))
                if r is not None and r.status == GattCommunicationStatus.SUCCESS:
                    chars = list(r.characteristics)
                    if chars:
                        print(f"  [WinRT] Got char 8001 (attempt {attempt + 1}) -- ready to send keepalives")
                        return chars[0]
                    print("  [WinRT] Char 8001 not found in service 6006")
                    return None
                status = r.status if r else "None"
                print(f"  [WinRT] Char 8001 status={status} (attempt {attempt + 1}/5)")

            print("  [WinRT] All char 8001 retries failed")
            return None

        except Exception as e:
            import traceback
            print(f"  [WinRT] {type(e).__name__}: {e}")
            traceback.print_exc()
            return None

    def _on_disconnect(self, client):
        print(f"\n[!] Disconnected from {client.address}")

    async def _setup_notifications(self):
        """Enable CCCD on all notify characteristics in service 6006."""
        self.notify_chars = []
        self.deframer = protocol.PacketDeframer()

        has_8003 = False
        for svc in self.client.services:
            if str(svc.uuid).lower() == protocol.SERVICE_MAIN.lower():
                for char in svc.characteristics:
                    short = str(char.uuid)[4:8].upper()
                    if short == "8003":
                        has_8003 = True

        self.write_char = protocol.CHAR_WRITE_8003 if has_8003 else protocol.CHAR_WRITE_8001
        print(f"  Write char: {self.write_char[4:8].upper()} (8003 present: {has_8003})")

        for char_uuid in [protocol.CHAR_NOTIFY_8002, protocol.CHAR_NOTIFY_8004, protocol.CHAR_8005]:
            try:
                await self.client.start_notify(char_uuid, self._on_notify)
                self.notify_chars.append(char_uuid)
                print(f"  Notifications enabled: {char_uuid[4:8].upper()}")
            except Exception as e:
                print(f"  Notifications NOT available on {char_uuid[4:8].upper()}: {e}")

    def _on_notify(self, characteristic: BleakGATTCharacteristic, data: bytearray):
        short = str(characteristic.uuid)[4:8].upper()
        raw_hex = bytes(data).hex()

        if bytes(data) == protocol.COMMAND_REPEAT_ECHO:
            print(f"  <- {short} [{raw_hex}]  [COMMAND_REPEAT_ECHO -- init ack]")
        else:
            print(f"  <- {short} [{raw_hex}]")

        self.notify_queue.put_nowait((str(characteristic.uuid), bytes(data)))

    # ── Sending ────────────────────────────────────────────────────────────

    async def write_raw(self, data: bytes, char_uuid: str = None, response: bool = True):
        char_uuid = char_uuid or self.write_char
        short = char_uuid[4:8].upper()
        print(f"  -> {short} [{data.hex()}]{'  (no-response)' if not response else ''}")
        await self.client.write_gatt_char(char_uuid, data, response=response)

    async def trigger(self):
        """Write [0x03] to 8002 -- tells watch to emit its buffered response."""
        print(f"  -> 8002 [03]  (trigger)")
        try:
            await self.client.write_gatt_char(protocol.CHAR_NOTIFY_8002, bytes([0x03]), response=False)
        except Exception as e:
            print(f"  [trigger failed: {e}]")

    async def send_and_await(
        self,
        cmd_byte: int,
        action: int,
        payload: bytes = b"",
        timeout: float = 10.0,
        use_trigger: Optional[bool] = None,
    ) -> Optional[dict]:
        """Send a command and wait for the matching response."""
        if use_trigger is None:
            use_trigger = self.use_trigger

        packet = protocol.build_packet(cmd_byte, action, payload)
        cmd_name = protocol.CMD_NAMES.get(cmd_byte, f"0x{cmd_byte:02X}")
        act_name = protocol.ACTION_NAMES.get(action, f"0x{action:02X}")
        print(f"\n  -> {cmd_name}/{act_name}  payload=[{payload.hex()}]  frame=[{packet.hex()}]")

        # Drain any stale notifications before sending
        while not self.notify_queue.empty():
            self.notify_queue.get_nowait()

        deframer = protocol.PacketDeframer()
        await self.write_raw(packet)

        if use_trigger:
            await self.trigger()

        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            try:
                _uuid, data = await asyncio.wait_for(
                    self.notify_queue.get(), timeout=min(remaining, 0.5)
                )
                pkt = deframer.feed(data)
                if pkt and protocol.matches(pkt, cmd_byte, action):
                    print(f"  [OK] {protocol.fmt_packet(pkt)}")
                    return pkt
                elif pkt:
                    print(f"  [unsolicited] {protocol.fmt_packet(pkt)}")
            except asyncio.TimeoutError:
                continue

        print(f"  [TIMEOUT] {timeout}s -- no response to {cmd_name}/{act_name}")
        return None

    # ── Info dump ──────────────────────────────────────────────────────────

    async def info(self):
        if not self.client:
            print("Not connected.")
            return
        print(f"\n=== Services on {self.mac} ===")
        for svc in self.client.services:
            short_svc = str(svc.uuid)[4:8].upper()
            print(f"\nService {svc.uuid}  ({short_svc})")
            for char in svc.characteristics:
                short_c = str(char.uuid)[4:8].upper()
                props = ",".join(char.properties)
                descriptors = [str(d.uuid)[4:8].upper() for d in char.descriptors]
                desc_str = f"  descriptors=[{', '.join(descriptors)}]" if descriptors else ""
                print(f"  Char {char.uuid}  ({short_c})  props=[{props}]{desc_str}")

    # ── Watch mode ─────────────────────────────────────────────────────────

    async def watch(self, duration: float = 10.0):
        """Passively listen for incoming notifications without sending anything."""
        print(f"Watching for {duration}s (no command sent) ...")
        deframer = protocol.PacketDeframer()
        while not self.notify_queue.empty():
            self.notify_queue.get_nowait()

        deadline = time.monotonic() + duration
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            try:
                _uuid, data = await asyncio.wait_for(
                    self.notify_queue.get(), timeout=min(remaining, 0.2)
                )
                pkt = deframer.feed(data)
                if pkt:
                    print(f"  -> parsed: {protocol.fmt_packet(pkt)}")
            except asyncio.TimeoutError:
                continue
        print("Watch period ended.")

    async def disconnect(self):
        if self.client and self.client.is_connected:
            await self.client.disconnect()
            print("Disconnected.")
        # Release WinRT session -- this lets Windows drop the BLE link if no one else holds it
        self._winrt_session = None
        self._winrt_device = None
        self._winrt_write_char = None
