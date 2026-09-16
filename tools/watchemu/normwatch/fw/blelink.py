"""The emulated watch's BLE stack, on a bumble virtual link.

:class:`NationzController` gets the firmware's Cordio host as far as speaking
HCI, but it is not a radio: it answers so the stack can start and no further.
This puts a real controller behind it -- a ``bumble.controller.Controller`` on
a ``bumble.link.LocalLink`` -- so the watch can actually advertise, be
connected to, and carry data, with no hardware anywhere.

Five things about the seam are worth knowing before changing it.

**The download phase never reaches bumble.** The NZ8801 bootloader exchange
(opcodes 0xF1/0xF2/0xF3, see :class:`NationzController`) is not HCI and bumble
would reject every packet of it. It is answered here, exactly as before, and
only once the controller has "booted" does anything get forwarded.

**Vendor commands are answered locally too.** Bumble has no handler for OGF
0x3F and, worse, replies to one by logging an error and emitting *nothing* --
which the firmware sees as a dead controller. The first thing across the link
after the download is the vendor command 0xFD04, so every vendor opcode gets a
benign Command Complete here. That is the same split
``tools/emulator/norm_emu_bridge.py`` already makes for Google's 0xFD53 on the
Android side; the vendor space belongs to whoever is emulating the part, not to
the generic stack.

**So are the LE Secure Connections primitives.** Bumble 0.0.229 does not have
them: its ``LE_Read_Local_P-256_Public_Key`` handler is a stub marked ``TODO``
that returns *sync* parameters for an *async* command, which its dispatcher
rejects, so nothing comes out; ``LE_Generate_DHKey`` and ``LE_Encrypt`` have no
handler at all. The firmware sends the first of those during HCI init and then
waits -- Cordio's command queue holds every later command until
``Num_HCI_Command_Packets`` says the controller can take one -- so the watch
never reached advertising. The three are answered here with real P-256 and
AES-128 from ``cryptography``, which bumble already depends on, rather than
with invented values: the phone on the other end does the same arithmetic and
checks it.

**Bumble runs on its own thread.** The emulator is synchronous and bumble is
asyncio, and ``Controller.send_hci_packet`` defers through ``call_soon``, so a
reply is never ready by the time ``on_hci_packet`` returns. A command therefore
hands off to the loop and waits briefly for *its* answer -- a Command Complete
or Command Status carrying its opcode -- which makes the exchange look
synchronous to the emulator and removes the race between the watch's own
timeouts and the loop getting a turn. Events the controller raises on its own
-- advertising reports, connection complete -- are queued whenever they arrive,
and the BLEIF raises the firmware's BLE interrupt for them.

**Bumble's controller has gaps a phone walks into**, and every one of them
looked like a hang: silence for an opcode it has no class for, LE data stamped
with the sender's random address (the watch uses its public one), a filter
accept list it never consults (Android connects through it), and no
``Read_Remote_Version_Information`` or ``LE_Connection_Update`` (Android will
not discover services without the first; the second jams the central's
command queue). :class:`VirtualController` and :class:`Air` close them, on
both ends of the link, and ``tests/test_ble_link.py`` pins each one.

The loop is shared on purpose. ``LocalLink`` dispatches every PDU with
``asyncio.get_running_loop().call_soon`` on the *sender's* loop, so two
controllers that are to hear each other must live on the same one. That is
what :class:`Radio` is: one thread, one loop, one link, and however many
controllers are on the air.

One consequence of that last point: while a link is up the machine has to be
paced against the wall clock. ``--idle-skip`` and the deadline quantum both
fast-forward *watch* time past idle, and a controller answering in real time
cannot keep up with a watch whose clock is running ahead of it.
"""

from __future__ import annotations

import asyncio
import collections
import sys
import threading
from pathlib import Path
from typing import Optional

from bumble import hci
from bumble.controller import Controller
from bumble.core import PhysicalTransport
from bumble.crypto import EccKey, e as aes_e
from bumble.link import LocalLink

from .devices import NationzController

#: OGF 0x3F. Every opcode in this space is the part manufacturer's, so it is
#: answered here rather than forwarded.
VENDOR_OGF = 0x3F

#: How long a command waits for its answer before the emulator carries on. The
#: firmware's own patience is far longer -- the receive path retries 5000 times
#: at 16us of watch time -- so this only has to beat the loop's scheduling.
COMMAND_TIMEOUT_S = 0.5
#: 7.8.12: connection intervals are in units of 1.25 ms.
CONNECTION_INTERVAL_UNIT_S = 0.00125
#: What Android asks for in its LE_Create_Connection (0x18) until someone
#: updates it -- the interval a connection has before ``Air`` hears otherwise.
DEFAULT_CONNECTION_INTERVAL_S = 0x18 * CONNECTION_INTERVAL_UNIT_S

#: The LE Controller commands answered here rather than by bumble. Vol 4 Part
#: E: 7.8.22 LE Encrypt, 7.8.36 LE Read Local P-256 Public Key, 7.8.37 LE
#: Generate DHKey.
OPCODE_LE_ENCRYPT = 0x2017
OPCODE_LE_READ_LOCAL_P256_PUBLIC_KEY = 0x2025
OPCODE_LE_GENERATE_DHKEY = 0x2026

#: LE Meta Event subevents that answer the last two (7.7.65.8, 7.7.65.9).
SUBEVENT_P256_PUBLIC_KEY_COMPLETE = 0x08
SUBEVENT_GENERATE_DHKEY_COMPLETE = 0x09

#: Event codes and status values used in the replies built here.
EVENT_COMMAND_COMPLETE = 0x0E
EVENT_COMMAND_STATUS = 0x0F
EVENT_LE_META = 0x3E
STATUS_SUCCESS = 0x00
STATUS_UNKNOWN_HCI_COMMAND = 0x01
STATUS_INVALID_PARAMETERS = 0x12

H4_COMMAND = 0x01
H4_EVENT = 0x04


def _opcode_ogf(opcode: int) -> int:
    return (opcode >> 10) & 0x3F


class VirtualController(Controller):
    """Bumble's ``Controller``, minus the silence.

    For an opcode it has no class for, bumble builds a generic ``HCI_Command``
    and its dispatcher then answers with nothing at all -- a host stack reads
    that as a hung controller, and Android's aborts start-up over it (the
    Google vendor command 0xFD53, which is why ``norm_emu_bridge.py`` answers
    that one by hand). A controller answers every command, so this one does:
    vendor opcodes get the Command Complete the bridge already proved Android
    accepts, anything else the Command Status a real controller gives
    (7.7.15, Unknown HCI Command).
    """

    def __init__(self, *args, **kwargs) -> None:
        super().__init__(*args, **kwargs)
        #: 7.8.16: the filter accept list, which bumble accepts entries for
        #: and never consults.
        self.filter_accept_list: set = set()

    # -- the filter accept list, actually used ------------------------------

    def on_hci_le_clear_filter_accept_list_command(self, command):
        self.filter_accept_list.clear()
        return super().on_hci_le_clear_filter_accept_list_command(command)

    def on_hci_le_add_device_to_filter_accept_list_command(self, command):
        self.filter_accept_list.add(command.address)
        return super().on_hci_le_add_device_to_filter_accept_list_command(command)

    def on_hci_le_remove_device_from_filter_accept_list_command(self, command):
        self.filter_accept_list.discard(command.address)
        return super().on_hci_le_remove_device_from_filter_accept_list_command(command)

    def on_advertising_pdu(self, pdu) -> None:
        """Connect through the accept list, which is how Android connects.

        Bumble only ever matches an advertiser against the pending
        connection's ``peer_address``. Android's stack puts the peer on the
        filter accept list and issues ``LE_Extended_Create_Connection`` with
        ``initiator_filter_policy`` 1 and a zero peer address, so nothing
        ever matched and every connect timed out (``on_create_connection_
        timeout`` in its log). With policy 1 an advertiser on the list is the
        peer, and the pending command is told so before the connection is
        made from it.
        """
        pending = self.pending_le_connection
        if (pending is not None
                and getattr(pending, "initiator_filter_policy", 0) == 1
                and pdu.advertiser_address in self.filter_accept_list
                and pending.peer_address != pdu.advertiser_address):
            pending.peer_address = pdu.advertiser_address
            pending.peer_address_type = pdu.advertiser_address.address_type
        super().on_advertising_pdu(pdu)

    # -- advertising after a bond --------------------------------------------

    def on_hci_le_set_advertising_parameters_command(self, command):
        """7.8.5 with own_address_type 2 and 3, which bumble takes for random.

        Once it holds a bond the firmware adds the phone to the resolving list
        (0x2027, with an all-zero *local* IRK) and advertises with
        own_address_type 2: "a resolvable private address from the resolving
        list, or the public address if the list has no matching entry". This
        controller keeps no resolving list, so there is never a match and the
        address a real controller would fall back to is the public one; bumble
        instead reads anything but 0 as "the random address", which for the
        watch is 00:00:00:00:00:00. So after the first disconnect the watch
        came back on the air as a random all-zero address, the phone's accept
        list held its public one, and every reconnect timed out (0x93/147).
        Types 2 and 3 fall back to 0 and 1 here, which is what the spec says
        an empty resolving list amounts to.
        """
        own = int(command.own_address_type)
        if own in (2, 3):
            command.own_address_type = own - 2
        return super().on_hci_le_set_advertising_parameters_command(command)

    # -- the silence --------------------------------------------------------

    def on_hci_command(self, command: hci.HCI_Command):
        """Every command bumble has no handler for lands here.

        Bumble's own version returns status parameters, which its dispatcher
        only turns into a packet for a *sync* command; for an async one, or
        one with no class at all, the return value is logged as an error and
        nothing goes out. So the async and classless cases answer for
        themselves here and return None.
        """
        unknown = hci.HCI_ErrorCode.UNKNOWN_HCI_COMMAND_ERROR
        if isinstance(command, hci.HCI_SyncCommand):
            return hci.HCI_StatusReturnParameters(status=unknown)
        if _opcode_ogf(command.op_code) == VENDOR_OGF:
            self.send_hci_packet(hci.HCI_Command_Complete_Event(
                num_hci_command_packets=1, command_opcode=command.op_code,
                return_parameters=hci.HCI_StatusReturnParameters(status=unknown)))
        else:
            self._send_hci_command_status(unknown, command.op_code)
        return None

    def on_hci_read_remote_version_information_command(
            self, command: hci.HCI_Read_Remote_Version_Information_Command) -> None:
        """7.1.23, which bumble leaves unhandled.

        Android's GATT client will not start service discovery until it has
        the peer's version ("Pausing service discovery till remote version
        is read"), so answering this with Unknown HCI Command left every
        connection to the watch stalled before the first ATT request. The
        answer is what the peer controller says about itself (7.7.12).
        """
        self._send_hci_command_status(hci.HCI_ErrorCode.SUCCESS, command.op_code)
        connection = self.find_connection_by_handle(command.connection_handle)
        peer = (self.link.find_le_controller(connection.peer_address)
                if connection is not None and self.link is not None else None)
        if connection is None or peer is None:
            self.send_hci_packet(hci.HCI_Read_Remote_Version_Information_Complete_Event(
                status=hci.HCI_ErrorCode.UNKNOWN_CONNECTION_IDENTIFIER_ERROR,
                connection_handle=command.connection_handle,
                version=0, manufacturer_name=0, subversion=0))
            return
        self.send_hci_packet(hci.HCI_Read_Remote_Version_Information_Complete_Event(
            status=hci.HCI_ErrorCode.SUCCESS,
            connection_handle=connection.handle,
            version=peer.lmp_version,
            manufacturer_name=peer.manufacturer_company_identifier,
            subversion=peer.lmp_subversion))

    def on_hci_le_connection_update_command(
            self, command: hci.HCI_LE_Connection_Update_Command) -> None:
        """7.8.18, which bumble leaves unhandled.

        A peripheral asks for new parameters through L2CAP and the central's
        host turns that into this command; with no answer the host's command
        queue stops and nothing else it wants to say gets out -- the watch
        asks for 15ms/latency 4/4s right after pairing, so this was the first
        thing to jam. There is no link layer under a ``LocalLink`` to
        negotiate anything, so the update is granted as asked and both ends
        get the LE Connection Update Complete event (7.7.65.3).
        """
        self._send_hci_command_status(hci.HCI_ErrorCode.SUCCESS, command.op_code)
        connection = self.find_le_connection_by_handle(command.connection_handle)
        if connection is None:
            return
        ends = [(self, connection)]
        if self.link is not None:
            peer = self.link.find_le_controller(connection.peer_address)
            peer_connection = (peer.le_connections.get(connection.self_address)
                               if peer is not None else None)
            if peer_connection is not None:
                ends.append((peer, peer_connection))
        for controller, end in ends:
            controller.send_hci_packet(hci.HCI_LE_Connection_Update_Complete_Event(
                status=hci.HCI_ErrorCode.SUCCESS,
                connection_handle=end.handle,
                connection_interval=command.connection_interval_max,
                peripheral_latency=command.max_latency,
                supervision_timeout=command.supervision_timeout))
        if isinstance(self.link, Air):
            self.link.set_interval(connection.self_address, connection.peer_address,
                                   command.connection_interval_max * CONNECTION_INTERVAL_UNIT_S)

    def create_le_connection(self, peer_address) -> None:
        """Bumble's, plus telling the air the interval the connection has."""
        pending = self.pending_le_connection
        super().create_le_connection(peer_address)
        connection = self.le_connections.get(peer_address)
        if pending is not None and connection is not None and isinstance(self.link, Air):
            if isinstance(pending, hci.HCI_LE_Extended_Create_Connection_Command):
                interval = pending.connection_interval_maxs[0]
            else:
                interval = pending.connection_interval_max
            self.link.set_interval(connection.self_address, peer_address,
                                   interval * CONNECTION_INTERVAL_UNIT_S)


class Air(LocalLink):
    """Bumble's ``LocalLink`` with data from a public address delivered, and
    delivered when a radio would deliver it.

    ``LocalLink.send_acl_data`` stamps every LE data PDU with the sender's
    *random* address, on the assumption that an LE controller always uses one.
    The watch does not: its firmware advertises and connects with its public
    address (own_address_type 0 in its LE_Set_Advertising_Parameters), so its
    controller's random address is all zeros, every ATT response it sends
    arrives at the phone from ``00:00:00:00:00:00``, and the phone's controller
    drops it with "no connection". The connection itself knows which address
    it was made with, so that is what goes on the PDU.

    It also delivers *now*, and an LE connection does not: data crosses only
    at connection events, one every connection interval (7.8.12; the watch
    asks for 15 ms right after pairing, Android starts it at 30). The
    firmware relies on that. Its 8001 command dispatcher (0x00036208) hands
    the reply to the radio and then, some milliseconds later, clears the
    receive buffer (0x00036294) -- and a frame that arrives in between is
    cleared with it and answered ``6F 01 81 02 00 00 02`` when its trigger
    comes. Over a real link the phone's next write cannot arrive inside that
    window, because the reply and the write are two connection events apart.
    Over ``call_soon`` it arrived every time, and the bind handshake failed at
    its second command. So each direction of each connection is a lane that
    holds what is sent and flushes it, in order, one interval later.

    Whose interval, though. The window is milliseconds of the *watch's* time,
    and the watch does not always keep up with the wall clock: painting the
    pairing dialog's animation puts the emulator well behind it, and in that
    state 30 wall-clock milliseconds are a few of the watch's -- the write
    landed in the window again, from a phone that had waited two intervals.
    So a lane whose destination has a clock attached (``attach_clock``; the
    emulated watch's machine) counts its interval on that clock, through the
    machine's own scheduler, and the phone simply sees a slow peripheral. A
    lane into the phone counts wall-clock time, which is what the phone has.
    """

    def __init__(self) -> None:
        super().__init__()
        #: frozenset({address, address}) -> seconds; see ``set_interval``.
        self.intervals: dict = {}
        self._lanes: dict = {}
        #: address -> (machine, radio) for destinations that keep watch time.
        self.clocks: dict = {}

    def attach_clock(self, address, machine, radio) -> None:
        """Deliver to *address* on *machine*'s clock rather than the wall's."""
        self.clocks[address] = (machine, radio)

    def set_interval(self, address_a, address_b, seconds: float) -> None:
        self.intervals[frozenset((address_a, address_b))] = seconds

    def interval(self, address_a, address_b) -> float:
        return self.intervals.get(frozenset((address_a, address_b)),
                                  DEFAULT_CONNECTION_INTERVAL_S)

    def send_acl_data(self, sender_controller, destination_address, transport, data):
        if transport == PhysicalTransport.LE:
            connection = sender_controller.le_connections.get(destination_address)
            if connection is not None:
                destination_controller = self.find_le_controller(destination_address)
                if destination_controller is not None:
                    self._queue(connection.self_address, destination_address,
                                destination_controller, transport, data)
                return
        super().send_acl_data(sender_controller, destination_address, transport, data)

    def _queue(self, source, destination, controller, transport, data) -> None:
        lane = self._lanes.get((source, destination))
        if lane is None:
            lane = self._lanes[(source, destination)] = collections.deque()
        lane.append((controller, transport, data))
        if len(lane) != 1:
            return
        interval = self.interval(source, destination)
        clock = self.clocks.get(destination)
        if clock is None:
            asyncio.get_running_loop().call_later(interval, self._flush, source, destination)
            return
        machine, radio = clock
        at = machine._instructions + int(interval * machine.CYCLES_PER_SECOND)
        # post() is the thread-safe way onto the emulator thread; schedule()
        # then fires at the watch-time deadline, and the flush comes back to
        # this loop, where everything bumble lives.
        machine.post(lambda: machine.schedule(
            at, lambda: radio.call(self._flush, source, destination), "connection event"),
            "connection event")

    def _flush(self, source, destination) -> None:
        """One connection event: everything queued since the last one, in order."""
        lane = self._lanes.get((source, destination))
        if not lane:
            return
        pending = list(lane)
        lane.clear()
        for controller, transport, data in pending:
            controller.on_link_acl_data(source, transport, data)


class Radio:
    """The air: one asyncio loop on one thread, and the ``LocalLink`` on it.

    Every controller that is to hear the others is created on this loop, and
    every packet into bumble is posted to it. Nothing else touches bumble
    from another thread.
    """

    def __init__(self, name: str = "bumble-radio") -> None:
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        self.link = None
        self._stop: Optional[asyncio.Event] = None
        self._started = threading.Event()
        self._failure: Optional[BaseException] = None
        self._thread = threading.Thread(target=self._run, name=name, daemon=True)
        self._thread.start()
        self._started.wait(timeout=10)
        if self._failure is not None:
            raise self._failure
        if self.loop is None:
            raise RuntimeError("the bumble loop did not start")

    def _run(self) -> None:
        try:
            asyncio.run(self._serve())
        except BaseException as exc:            # surfaced on the caller's thread
            self._failure = exc
            self._started.set()

    async def _serve(self) -> None:
        self.loop = asyncio.get_running_loop()
        self.link = Air()
        self._stop = asyncio.Event()
        self._started.set()
        # Nothing else to do here: work arrives through call_soon_threadsafe.
        await self._stop.wait()

    def call(self, fn, *args) -> None:
        """Run ``fn(*args)`` on the loop, without waiting."""
        self.loop.call_soon_threadsafe(fn, *args)

    def run(self, coro, timeout: float = 10.0):
        """Run a coroutine on the loop and wait for its result."""
        return asyncio.run_coroutine_threadsafe(coro, self.loop).result(timeout)

    def close(self) -> None:
        if self.loop is not None and self._stop is not None:
            self.loop.call_soon_threadsafe(self._stop.set)
        self._thread.join(timeout=5)


class BumbleController(NationzController):
    """A bumble ``Controller`` behind the NZ8801 bootloader.

    ``radio`` lets several of these share one loop and one ``LocalLink``,
    which is how the watch and a phone end up able to see each other. Left
    out, one is made, and the watch is alone on it.
    """

    def __init__(self, address: str = "4C:59:80:12:44:F1", *, radio=None,
                 name: str = "watch", log=print) -> None:
        super().__init__(log=log)
        self.address = address
        self.name = name
        #: Opcodes answered here rather than by bumble, in order.
        self.vendor_commands: list = []
        #: Opcodes actually handed to bumble, in order.
        self.forwarded: list = []
        #: Opcodes bumble had no answer for, so this seam answered instead.
        self.unanswered: list = []
        #: The P-256 key pair behind the last LE_Read_Local_P-256_Public_Key.
        self.ecc_key = None
        #: Log every packet across the seam once the link is HCI. Cheap to
        #: leave off, and the first thing to turn on when a phone and the
        #: watch disagree about what was said.
        self.trace = False
        self._lock = threading.Lock()
        self._answered = threading.Event()
        self._awaiting: Optional[int] = None
        self.radio = radio if radio is not None else Radio()
        self._controller = self.radio.run(self._create())

    #: What the physical watch's controller says about itself, as Android
    #: recorded it in the AVD's bond record (bt_config.conf: LmpVer 8,
    #: LmpSubVer 809, Manufacturer 96) -- Bluetooth 4.2, company 0x0060. The
    #: phone reads it back with Read_Remote_Version_Information and keys
    #: interop decisions on it, so the emulated watch says the same.
    LMP_VERSION = 8
    LMP_SUBVERSION = 809
    MANUFACTURER = 96

    async def _create(self):
        # Created on the loop: Controller.__init__ needs a running one.
        controller = VirtualController(self.name, host_sink=self, link=self.radio.link,
                                       public_address=self.address)
        controller.hci_version = controller.lmp_version = self.LMP_VERSION
        controller.lmp_subversion = self.LMP_SUBVERSION
        controller.manufacturer_company_identifier = self.MANUFACTURER
        return controller

    # -- the bumble side, all of it on the radio's thread ---------------------

    def on_packet(self, packet: bytes) -> None:
        """Bumble's host sink: a packet from the controller to the firmware.

        It goes out length-prefixed like anything else the running controller
        says -- bumble produces bare H4 and knows nothing about the framing
        0x0008900C expects.
        """
        packet = bytes(packet)
        if self.trace and self.log:
            self.log(f"  [hci] <- {packet.hex(' ')}")
        self._event(packet)
        if self._awaiting is not None and _answers(packet, self._awaiting):
            self._answered.set()

    # -- thread-safe queueing -------------------------------------------------

    def to_host(self, data: bytes) -> None:
        with self._lock:
            super().to_host(data)

    def has_data(self) -> bool:
        with self._lock:
            return super().has_data()

    def available(self) -> int:
        with self._lock:
            return super().available()

    def take(self, count: int) -> bytes:
        with self._lock:
            return super().take(count)

    # -- the seam -------------------------------------------------------------

    def from_host(self, packet: bytes) -> None:
        if not self.booted:
            # Still the NZ8801 bootloader; none of this is HCI.
            super().from_host(packet)
            return

        # Record it the way the base class would, then decide where it goes.
        self.sent.append(bytes(packet))
        if self.trace and self.log:
            self.log(f"  [hci] -> {bytes(packet).hex(' ')}")
        if len(packet) < 4 or packet[0] != H4_COMMAND:
            self._forward(packet, opcode=None)
            return

        opcode = packet[1] | (packet[2] << 8)
        self.hci_commands.append(opcode)
        if _opcode_ogf(opcode) == VENDOR_OGF:
            self.vendor_commands.append(opcode)
            self._answer_vendor(opcode, packet)
            return
        local = self._LOCAL.get(opcode)
        if local is not None:
            local(self, opcode, packet[4:4 + packet[3]])
            return
        self._forward(packet, opcode=opcode)

    def _forward(self, packet: bytes, *, opcode: Optional[int]) -> None:
        try:
            parsed = hci.HCI_Packet.from_bytes(bytes(packet))
        except Exception as exc:
            if self.log:
                self.log(f"  [ble] bumble could not parse {packet.hex(' ')}: {exc}")
            return
        if opcode is not None:
            self.forwarded.append(opcode)
            self._awaiting = opcode
            self._answered.clear()
        self.radio.call(self._controller.on_hci_packet, parsed)
        if opcode is None:
            # ACL data has no reply to wait for.
            return
        # Wait for the answer so the exchange looks synchronous to the
        # emulator: a Command Complete or Command Status for this opcode,
        # not just anything the controller happens to say meanwhile.
        answered = self._answered.wait(COMMAND_TIMEOUT_S)
        self._awaiting = None
        if not answered:
            # Bumble has no class for this opcode, so it built a generic
            # command, and its dispatcher answers one of those with silence.
            # A controller answers every command; the honest answer for one it
            # does not know is Command Status, Unknown HCI Command (7.7.15).
            self.unanswered.append(opcode)
            if self.log:
                self.log(f"  [ble] bumble did not answer 0x{opcode:04X}; "
                         "answering Unknown HCI Command")
            self._command_status(opcode, STATUS_UNKNOWN_HCI_COMMAND)

    # -- answered here --------------------------------------------------------

    def _answer_vendor(self, opcode: int, packet: bytes) -> None:
        # Bumble answers a vendor opcode with silence, which the firmware
        # reads as a dead controller.
        if opcode == self.OPCODE_READ_MEMORY and len(packet) >= 8:
            address = int.from_bytes(packet[4:8], "little")
            self._command_complete(
                opcode, self.memory.get(address, 0).to_bytes(4, "little"))
        else:
            self._command_complete(opcode, b"")

    def _le_encrypt(self, opcode: int, params: bytes) -> None:
        """7.8.22: AES-128 of one block, key and data both least-significant
        octet first on the wire, the answer likewise."""
        if len(params) != 32:
            self._command_complete(opcode, b"", STATUS_INVALID_PARAMETERS)
            return
        self._command_complete(opcode, aes_e(params[:16], params[16:]))

    def _le_read_local_p256_public_key(self, opcode: int, params: bytes) -> None:
        """7.8.36: Command Status now, then the key in a meta event.

        A fresh pair each time, as the spec has it: the host asks again
        whenever it wants a new one, and Cordio does at every pairing.
        """
        self.ecc_key = EccKey.generate()
        self._command_status(opcode, STATUS_SUCCESS)
        # 7.7.65.8: subevent, status, X (32, LE), Y (32, LE).
        self._meta_event(bytes([SUBEVENT_P256_PUBLIC_KEY_COMPLETE, STATUS_SUCCESS])
                         + self.ecc_key.x[::-1] + self.ecc_key.y[::-1])

    def _le_generate_dhkey(self, opcode: int, params: bytes) -> None:
        """7.8.37: the peer's public key in, the shared secret out.

        The peer sends X then Y, each least-significant octet first, and the
        DHKey goes back the same way (7.7.65.9). A point that is not on the
        curve gets a failure status rather than a secret -- that is the
        invalid-public-key check pairing relies on.
        """
        if len(params) != 64:
            self._command_status(opcode, STATUS_INVALID_PARAMETERS)
            return
        if self.ecc_key is None:
            self.ecc_key = EccKey.generate()
        self._command_status(opcode, STATUS_SUCCESS)
        try:
            secret = self.ecc_key.dh(params[:32][::-1], params[32:][::-1])[::-1]
        except ValueError:
            self._meta_event(bytes([SUBEVENT_GENERATE_DHKEY_COMPLETE,
                                    STATUS_INVALID_PARAMETERS]) + bytes(32))
            return
        self._meta_event(bytes([SUBEVENT_GENERATE_DHKEY_COMPLETE, STATUS_SUCCESS])
                         + secret)

    _LOCAL = {
        OPCODE_LE_ENCRYPT: _le_encrypt,
        OPCODE_LE_READ_LOCAL_P256_PUBLIC_KEY: _le_read_local_p256_public_key,
        OPCODE_LE_GENERATE_DHKEY: _le_generate_dhkey,
    }

    # -- event builders -------------------------------------------------------

    def _command_complete(self, opcode: int, returned: bytes,
                          status: int = STATUS_SUCCESS) -> None:
        params = bytes([0x01, opcode & 0xFF, opcode >> 8, status]) + returned
        self._event(bytes([H4_EVENT, EVENT_COMMAND_COMPLETE, len(params)]) + params)

    def _command_status(self, opcode: int, status: int) -> None:
        params = bytes([status, 0x01, opcode & 0xFF, opcode >> 8])
        self._event(bytes([H4_EVENT, EVENT_COMMAND_STATUS, len(params)]) + params)

    def _meta_event(self, params: bytes) -> None:
        self._event(bytes([H4_EVENT, EVENT_LE_META, len(params)]) + params)

    # -- reporting ------------------------------------------------------------

    def attach(self, machine) -> None:
        """Called by ``attach_ble_controller``: data to the watch now crosses
        the air on the watch's clock (see ``Air``)."""
        self.machine = machine
        self.radio.call(self.link.attach_clock, self._controller.public_address,
                        machine, self.radio)

    @property
    def link(self):
        return self.radio.link

    @property
    def controller(self):
        """The bumble ``Controller``. Touch it only from the radio's loop."""
        return self._controller

    @property
    def is_advertising(self) -> bool:
        return bool(self._controller is not None and self._controller.is_advertising)

    def summary(self) -> str:
        base = super().summary()
        vendor = len(self.vendor_commands)
        local = len(self.hci_commands) - vendor - len(self.forwarded)
        parts = [f"{len(self.forwarded)} HCI commands to bumble",
                 f"{vendor} vendor and {local} LE crypto answered locally"]
        if self.unanswered:
            parts.append(f"{len(self.unanswered)} unknown to bumble "
                         f"({', '.join(f'0x{o:04X}' for o in self.unanswered[:4])})")
        parts.append("advertising" if self.is_advertising else "not advertising")
        parts.append(f"address {self.address}")
        return f"{base}\n  BLE link: " + ", ".join(parts)


#: Where the Android emulator's netsim server lives: ``tools/emulator``, which
#: serves the same endpoint with a real dongle behind it and owns the fix for
#: emulator 36.x's ``packet`` field. Imported from there rather than copied.
ANDROID_TOOLS = Path(__file__).resolve().parents[3] / "emulator"

#: The port ``launch-emulator.ps1`` points ``-packet-streamer-endpoint`` at.
NETSIM_PORT = 8877

#: What the phone's controller calls itself. Any address the watch does not
#: have will do; the Android stack reads it back with Read_BD_ADDR.
PHONE_ADDRESS = "AA:BB:CC:DD:EE:01"


class AndroidLink:
    """The Android emulator's netsim endpoint, on this radio.

    The emulator's guest Bluetooth stack talks HCI to whatever serves
    ``-packet-streamer-endpoint``; normally that is ``norm_emu_bridge.py`` with
    a USB dongle behind it. This serves the same endpoint with a
    :class:`VirtualController` behind it instead, on the same
    ``LocalLink`` as the watch's -- so the phone in the AVD and the watch in
    the emulator are two controllers on one piece of air, with no hardware
    anywhere.
    """

    def __init__(self, radio: Radio, port: int = NETSIM_PORT, *,
                 address: str = PHONE_ADDRESS, name: str = "phone",
                 log=print) -> None:
        self.radio = radio
        self.port = port
        self.address = address
        self.name = name
        self.log = log
        self.transport = None
        self.controller = None
        self.radio.run(self._open())

    async def _open(self) -> None:
        if str(ANDROID_TOOLS) not in sys.path:
            sys.path.insert(0, str(ANDROID_TOOLS))
        import netsim_transport

        self.transport = await netsim_transport.open_controller_transport_fixed(
            "localhost", self.port, {})
        self.controller = VirtualController(
            self.name, host_source=self.transport.source,
            host_sink=self.transport.sink, link=self.radio.link,
            public_address=self.address)
        # Android's stack asserts that its controller supports Secure Simple
        # Pairing -- btm_sec_dev_reset: "only controllers with SSP is
        # supported", a fatal abort three times over and then Bluetooth is
        # simply off -- and bumble's controller, being LE-only, does not
        # claim it. It is a BR/EDR feature bit; claiming it changes nothing
        # on the LE side, which is all the watch has.
        self.controller.lmp_features |= (
            hci.LmpFeatureMask.SECURE_SIMPLE_PAIRING_CONTROLLER_SUPPORT)
        if self.log:
            self.log(f"  [ble] Android netsim endpoint on localhost:{self.port} "
                     f"(phone controller {self.address})")

    @property
    def connected(self) -> bool:
        """Whether the Android emulator is currently attached."""
        server = getattr(self.transport, "source", None)
        return getattr(server, "device", None) is not None

    def close(self) -> None:
        if self.transport is not None:
            self.radio.run(self.transport.close())
            self.transport = None


def _answers(packet: bytes, opcode: int) -> bool:
    """Whether an H4 event is the reply to ``opcode``.

    Command Complete carries the opcode at bytes 4..5, Command Status at 5..6
    (Vol 4 Part E 7.7.14, 7.7.15).
    """
    if len(packet) < 7 or packet[0] != H4_EVENT:
        return False
    if packet[1] == EVENT_COMMAND_COMPLETE:
        return packet[4] | (packet[5] << 8) == opcode
    if packet[1] == EVENT_COMMAND_STATUS:
        return packet[5] | (packet[6] << 8) == opcode
    return False
