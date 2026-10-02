"""The bumble seam: a real controller behind the NZ8801, and what it must answer.

``blelink.BumbleController`` puts a bumble ``Controller`` on a ``LocalLink``
behind the download protocol the firmware speaks first. Everything below is
about the split -- what the seam answers itself and what it hands to bumble --
because that split is where every stall so far has been:

* The download phase is not HCI and never reaches bumble.
* Vendor opcodes (OGF 0x3F) are answered here: bumble answers one with
  *silence*, which the firmware reads as a dead controller.
* The three LE Secure Connections primitives are answered here too, and are
  the reason this file exists. Bumble 0.0.229's handler for
  ``LE_Read_Local_P-256_Public_Key`` (0x2025) is a stub that returns nothing
  (the source says ``TODO``), and ``LE_Generate_DHKey`` (0x2026) and
  ``LE_Encrypt`` (0x2017) are not implemented at all. The firmware sends 0x2025
  as part of its HCI init, and with no answer its command queue never opens
  again -- Cordio waits for ``Num_HCI_Command_Packets`` before sending the
  next command -- so the watch never gets as far as advertising.
* Any other command bumble does not know gets the answer a real controller
  gives, ``Command Status`` with *Unknown HCI Command*, rather than silence.

The last two tests boot the firmware against all of it and check the thing the
whole plan rests on: that once HCI init completes the watch advertises.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_ble_link.py
"""
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw import image as image_mod
from normwatch.fw.blelink import (OPCODE_LE_ENCRYPT, OPCODE_LE_GENERATE_DHKEY,
                                  OPCODE_LE_READ_LOCAL_P256_PUBLIC_KEY,
                                  BumbleController, Radio)
from normwatch.fw.cortexm import IRQ_BLE
from normwatch.fw.devices import (attach_ble_controller, attach_motion_sensor,
                                  attach_mspi_devices, attach_pmu,
                                  attach_touch_panel)
from normwatch.fw.machine import Apollo3Machine
from normwatch.fw.peripherals import BleController, Bleif

REPO = Path(__file__).resolve().parents[3]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"

BLEIF_BASE = 0x5000C000
WATCH = "4C:59:80:12:44:F1"
PHONE = "AA:BB:CC:DD:EE:01"

#: HCI init finishes and LE_Set_Advertising_Enable goes out at ~4.5M; this
#: leaves room for it to go wrong slowly.
BUDGET = 20_000_000

LE_SET_ADVERTISING_ENABLE = 0x200A
LE_SET_ADVERTISING_PARAMETERS = 0x2006
LE_SET_ADVERTISING_DATA = 0x2008
LE_SET_SCAN_PARAMETERS = 0x200B
LE_SET_SCAN_ENABLE = 0x200C
READ_BD_ADDR = 0x1009
HCI_RESET = 0x0C03

_boot = None


def quiet(*a, **k):
    pass


def command(opcode: int, params: bytes = b"") -> bytes:
    return bytes([0x01, opcode & 0xFF, opcode >> 8, len(params)]) + params


def booted(**kwargs) -> BumbleController:
    """A seam past the download phase, so that the next packet is HCI."""
    controller = BumbleController(log=quiet, **kwargs)
    controller.from_host(bytes.fromhex("01eef1020000"))     # the go-ahead
    controller.take(5)                                     # its announcement
    assert controller.booted
    return controller


def frames(controller, *, wait: float = 0.0) -> list:
    """Every queued message, unframed: the running controller prefixes each
    with a two-byte little-endian length (0x0008900C)."""
    deadline = time.monotonic() + wait
    out = []
    while True:
        while controller.available() >= 2:
            header = controller.take(2)
            length = int.from_bytes(header, "little")
            out.append(controller.take(length))
        if out or time.monotonic() >= deadline:
            return out
        time.sleep(0.01)


def reply_to(controller, packet: bytes, *, wait: float = 0.0) -> list:
    controller.from_host(packet)
    return frames(controller, wait=wait)


# -- what never reaches bumble -----------------------------------------------

def test_the_download_is_answered_here_and_never_reaches_bumble():
    controller = BumbleController(log=quiet)
    controller.from_host(bytes.fromhex("01bbf1025406"))
    assert controller.take(5) == bytes.fromhex("04bbf10100"), "raw ack, not HCI"
    assert controller.hci_commands == [], "a download packet was counted as HCI"
    assert not controller.booted


def test_vendor_opcodes_are_answered_locally():
    controller = booted()
    (reply,) = reply_to(controller, command(0xFD04, b"\x01"))
    assert reply == bytes.fromhex("040e040104fd00"), reply.hex(" ")
    assert controller.vendor_commands == [0xFD04]
    # ...including the memory read the HAL's version gate depends on.
    address = controller.CONTROLLER_VERSION_ADDRESS.to_bytes(4, "little")
    (reply,) = reply_to(controller, command(0xFD02, address))
    assert reply[:7] == bytes.fromhex("040e080102fd00"), reply.hex(" ")
    assert int.from_bytes(reply[7:11], "little") == controller.CONTROLLER_VERSION


# -- what does, and how it comes back ----------------------------------------

def test_a_standard_command_is_answered_before_from_host_returns():
    # The emulator is synchronous; the seam waits for bumble's answer so the
    # firmware's receive path finds it on its first look.
    controller = booted()
    replies = reply_to(controller, command(HCI_RESET))
    assert replies == [bytes.fromhex("040e0401030c00")], [r.hex(" ") for r in replies]
    assert controller.hci_commands == [HCI_RESET]
    assert controller.vendor_commands == []


def test_the_controller_carries_the_watchs_address():
    controller = booted(address=WATCH)
    (reply,) = reply_to(controller, command(READ_BD_ADDR))
    assert reply[:7] == bytes.fromhex("040e0a01091000"), reply.hex(" ")
    assert reply[7:13] == bytes.fromhex(WATCH.replace(":", ""))[::-1]


def test_an_unparseable_packet_is_dropped_without_raising():
    controller = booted()
    # Declares five parameter bytes and carries one.
    controller.from_host(bytes.fromhex("01030c0500"))
    assert frames(controller, wait=0.1) == []


def test_a_command_bumble_does_not_know_still_gets_an_answer():
    """Silence is never what a controller says.

    Bumble builds a generic ``HCI_Command`` for an opcode it has no class for,
    and its dispatcher then logs an error and emits nothing. A real controller
    answers with Command Status, Unknown HCI Command (Vol 4 Part E 7.7.15),
    and that is what lets Cordio's command queue move on.
    """
    controller = booted()
    unknown = 0x0BFF                      # OGF 2, an OCF nothing defines
    (reply,) = reply_to(controller, command(unknown), wait=1.0)
    assert reply == bytes([0x04, 0x0F, 0x04, 0x01, 0x01, 0xFF, 0x0B]), reply.hex(" ")


# -- the LE Secure Connections primitives -------------------------------------

def test_le_encrypt_is_aes_128_in_hci_byte_order():
    """FIPS-197 C.1 through the HCI convention.

    7.8.22: "the most significant octet of the key corresponds to key[0]" in
    FIPS notation, and HCI sends multi-octet values least-significant first --
    so both inputs go over the wire reversed, and so does the answer.
    """
    key = bytes.fromhex("2b7e151628aed2a6abf7158809cf4f3c")
    plaintext = bytes.fromhex("6bc1bee22e409f96e93d7e117393172a")
    ciphertext = bytes.fromhex("3ad77bb40d7a3660a89ecaf32466ef97")
    controller = booted()
    (reply,) = reply_to(controller,
                        command(OPCODE_LE_ENCRYPT, key[::-1] + plaintext[::-1]))
    assert reply[:7] == bytes.fromhex("040e1401172000"), reply.hex(" ")
    assert reply[7:] == ciphertext[::-1], reply[7:].hex()
    assert OPCODE_LE_ENCRYPT not in controller.forwarded, "bumble cannot do this one"


def test_the_p256_key_arrives_as_command_status_then_a_meta_event():
    """7.8.36: Command Status now, the key in an LE meta event when ready."""
    from cryptography.hazmat.primitives.asymmetric import ec

    controller = booted()
    status, complete = reply_to(controller,
                                command(OPCODE_LE_READ_LOCAL_P256_PUBLIC_KEY))
    assert status == bytes.fromhex("040f0400012520"), status.hex(" ")
    # 7.7.65.8: subevent 0x08, status, X (32), Y (32) -- 66 parameter bytes.
    assert complete[:5] == bytes.fromhex("043e420800"), complete.hex(" ")
    x = int.from_bytes(complete[5:37], "little")
    y = int.from_bytes(complete[37:69], "little")
    # A real point on the curve, not a plausible-looking one.
    ec.EllipticCurvePublicNumbers(x, y, ec.SECP256R1()).public_key()


def test_each_key_request_makes_a_fresh_key():
    controller = booted()
    _, first = reply_to(controller, command(OPCODE_LE_READ_LOCAL_P256_PUBLIC_KEY))
    _, second = reply_to(controller, command(OPCODE_LE_READ_LOCAL_P256_PUBLIC_KEY))
    assert first[5:] != second[5:]


def test_generate_dhkey_agrees_with_the_peer():
    """7.8.37: the peer's X and Y in, the shared secret out, all little-endian.

    Checked from the other side with an independent key: the secret the seam
    hands the firmware has to be the one the peer computes, or pairing fails
    at the DHKey check with nothing else to go on.
    """
    from bumble.crypto import EccKey

    controller = booted()
    _, complete = reply_to(controller, command(OPCODE_LE_READ_LOCAL_P256_PUBLIC_KEY))
    our_x, our_y = complete[5:37], complete[37:69]

    peer = EccKey.generate()
    status, dhkey = reply_to(controller, command(
        OPCODE_LE_GENERATE_DHKEY, peer.x[::-1] + peer.y[::-1]))
    assert status == bytes.fromhex("040f0400012620"), status.hex(" ")
    # 7.7.65.9: subevent 0x09, status, DHKey (32) -- 34 parameter bytes.
    assert dhkey[:5] == bytes.fromhex("043e220900"), dhkey.hex(" ")
    expected = peer.dh(our_x[::-1], our_y[::-1])[::-1]
    assert dhkey[5:] == expected


def test_a_dhkey_for_a_point_off_the_curve_is_refused_not_faked():
    controller = booted()
    reply_to(controller, command(OPCODE_LE_READ_LOCAL_P256_PUBLIC_KEY))
    status, complete = reply_to(controller,
                                command(OPCODE_LE_GENERATE_DHKEY, bytes(64)))
    assert status[3] == 0x00, "the command itself is accepted"
    assert complete[:4] == bytes.fromhex("043e2209"), complete.hex(" ")
    assert complete[4] != 0x00, "a secret was produced from a point that is not on the curve"


# -- two of them on one radio -------------------------------------------------

def test_two_controllers_on_one_radio_see_each_other():
    """The reason the loop is shared: bumble's LocalLink dispatches every PDU
    on the *sender's* running loop, so controllers that are to hear each
    other must live on the same one."""
    radio = Radio()
    watch = booted(address=WATCH, radio=radio, name="watch")
    phone = booted(address=PHONE, radio=radio, name="phone")
    assert watch.link is phone.link

    # 7.8.5 / 7.8.7 / 7.8.9 on the watch.
    reply_to(watch, command(LE_SET_ADVERTISING_PARAMETERS, bytes.fromhex(
        "a000a000000000000000000000000700")))
    reply_to(watch, command(LE_SET_ADVERTISING_DATA,
                            bytes([3]) + bytes.fromhex("020106").ljust(31, b"\x00")))
    (enabled,) = reply_to(watch, command(LE_SET_ADVERTISING_ENABLE, b"\x01"))
    assert enabled[6] == 0x00, enabled.hex(" ")
    assert watch.is_advertising

    # 7.8.10 / 7.8.11 on the phone.
    reply_to(phone, command(LE_SET_SCAN_PARAMETERS, bytes.fromhex("0110001000" "0000")))
    reply_to(phone, command(LE_SET_SCAN_ENABLE, b"\x01\x00"))

    seen = frames(phone, wait=2.0)
    reports = [f for f in seen if f[:2] == b"\x04\x3e" and f[3] in (0x02, 0x0D)]
    assert reports, [f.hex(" ") for f in seen]
    address = bytes.fromhex(WATCH.replace(":", ""))[::-1]
    assert any(address in r for r in reports), [r.hex(" ") for r in reports]

    reply_to(watch, command(LE_SET_ADVERTISING_ENABLE, b"\x00"))
    assert not watch.is_advertising
    radio.close()


def test_a_bonded_watch_still_advertises_its_public_address():
    """After a bond the firmware advertises with own_address_type 2 -- a
    resolvable private address from the resolving list, or the public address
    when the list has no entry to make one from (it adds the phone with an
    all-zero local IRK). Bumble reads anything but 0 as "the random address",
    which for the watch is all zeros, so the watch came back from its first
    disconnect as 00:00:00:00:00:00 and no reconnect ever matched it."""
    radio = Radio()
    watch = booted(address=WATCH, radio=radio, name="watch")
    phone = booted(address=PHONE, radio=radio, name="phone")
    # 7.8.38 with the phone's identity and a zero local IRK, as the firmware
    # sends it, then 7.8.5 with own_address_type 2.
    reply_to(watch, command(0x2027, b"\x00" + bytes.fromhex(PHONE.replace(":", ""))[::-1]
                            + bytes(16) + bytes(16)))
    reply_to(watch, command(LE_SET_ADVERTISING_PARAMETERS, bytes.fromhex(
        "c800c800" "00" "02" "00" "000000000000" "07" "00")))
    reply_to(watch, command(LE_SET_ADVERTISING_DATA,
                            bytes([3]) + bytes.fromhex("020106").ljust(31, b"\x00")))
    (enabled,) = reply_to(watch, command(LE_SET_ADVERTISING_ENABLE, b"\x01"))
    assert enabled[6] == 0x00, enabled.hex(" ")

    reply_to(phone, command(LE_SET_SCAN_PARAMETERS, bytes.fromhex("0110001000" "0000")))
    reply_to(phone, command(LE_SET_SCAN_ENABLE, b"\x01\x00"))
    seen = frames(phone, wait=2.0)
    reports = [f for f in seen if f[:2] == b"\x04>" and f[3] in (0x02, 0x0D)]
    assert reports, [f.hex(" ") for f in seen]
    address = bytes.fromhex(WATCH.replace(":", ""))[::-1]
    # Public (address type 0) and the watch's own, not a random all-zero one.
    assert all(address in r for r in reports), [r.hex(" ") for r in reports]
    assert all(bytes(6) not in r[5:] for r in reports), [r.hex(" ") for r in reports]
    radio.close()


def collect(controller, wanted, *, timeout: float = 3.0) -> list:
    """Frames until ``wanted(frame)`` is true for one of them, or time is up."""
    deadline = time.monotonic() + timeout
    seen = []
    while time.monotonic() < deadline:
        seen.extend(frames(controller))
        if any(wanted(f) for f in seen):
            break
        time.sleep(0.02)
    return seen


def advertise(watch) -> None:
    reply_to(watch, command(LE_SET_ADVERTISING_PARAMETERS, bytes.fromhex(
        "a000a000000000000000000000000700")))
    (enabled,) = reply_to(watch, command(LE_SET_ADVERTISING_ENABLE, b"\x01"))
    assert enabled[6] == 0x00, enabled.hex(" ")


def test_a_phone_connects_the_way_android_does_and_reads_the_watchs_version():
    """Three things bumble's controller does not do, each found against the AVD.

    Android puts the peer on the filter accept list and connects with
    ``initiator_filter_policy`` 1 and a zero peer address -- bumble never
    consulted the list, so every connect timed out. Its GATT client then
    reads the remote version before it will discover anything -- bumble has
    no handler, so discovery paused for ever. And once connected the watch
    asks for new connection parameters, which the phone's host turns into
    ``LE_Connection_Update`` -- unhandled too, and it jammed the host's
    command queue. ``VirtualController`` does all three.
    """
    radio = Radio()
    watch = booted(address=WATCH, radio=radio, name="watch")
    phone = booted(address=PHONE, radio=radio, name="phone")
    advertise(watch)
    address = bytes.fromhex(WATCH.replace(":", ""))[::-1]

    # 7.8.16 then 7.8.12 with the accept list, peer address all zeros.
    reply_to(phone, command(0x2011, b"\x00" + address))
    (status,) = reply_to(phone, command(0x200D, bytes.fromhex(
        "1000 1000 01 00 000000000000 00 1800 2800 0000 2a00 0000 0000".replace(" ", ""))))
    assert status[:2] == b"\x04\x0f" and status[3] == 0x00, status.hex(" ")

    def connection_complete(f):
        return f[:2] == b"\x04\x3e" and f[3] == 0x01
    seen = collect(phone, connection_complete)
    complete = next((f for f in seen if connection_complete(f)), None)
    assert complete is not None, [f.hex(" ") for f in seen]
    assert complete[4] == 0x00, "connection failed"
    assert complete[9:15] == address, complete.hex(" ")
    handle = complete[5:7]
    # The watch's side saw the same connection.
    watch_seen = collect(watch, connection_complete)
    assert any(connection_complete(f) for f in watch_seen), \
        [f.hex(" ") for f in watch_seen]

    # 7.1.23: Command Status, then 7.7.12 with what the watch's controller
    # says about itself -- the values Android recorded from the real one.
    seen = reply_to(phone, command(0x041D, handle))
    seen += collect(phone, lambda f: f[:2] == b"\x04\x0c")
    version = next((f for f in seen if f[:2] == b"\x04\x0c"), None)
    assert version is not None, [f.hex(" ") for f in seen]
    assert version[3] == 0x00 and version[4:6] == handle, version.hex(" ")
    assert version[6] == BumbleController.LMP_VERSION
    assert int.from_bytes(version[7:9], "little") == BumbleController.MANUFACTURER
    assert int.from_bytes(version[9:11], "little") == BumbleController.LMP_SUBVERSION

    # 7.8.18: granted as asked, and both ends hear about it (7.7.65.3).
    params = bytes.fromhex("0c000c0004009001") + bytes(4)
    first = reply_to(phone, command(0x2013, handle + params))

    def updated(f):
        return f[:2] == b"\x04\x3e" and f[3] == 0x03
    for end, seen in ((phone, first), (watch, [])):
        seen = seen + collect(end, updated)
        event = next((f for f in seen if updated(f)), None)
        assert event is not None, (end.name, [f.hex(" ") for f in seen])
        assert event[4] == 0x00 and event[7:13] == bytes.fromhex("0c0004009001"), event.hex(" ")
    radio.close()


# -- encryption: the watch is asked for its key ---------------------------------

LTK = bytes(range(16))
RAND = bytes.fromhex("0102030405060708")
EDIV = 0x1234


def connected():
    """A phone connected to the watch; returns both ends and both handles."""
    radio = Radio()
    watch = booted(address=WATCH, radio=radio, name="watch")
    phone = booted(address=PHONE, radio=radio, name="phone")
    advertise(watch)
    address = bytes.fromhex(WATCH.replace(":", ""))[::-1]
    reply_to(phone, command(0x200D, bytes.fromhex(
        "1000 1000 00 00".replace(" ", "")) + address + bytes.fromhex(
        "00 1800 2800 0000 2a00 0000 0000".replace(" ", ""))))

    def connection_complete(f):
        return f[:2] == b"\x04\x3e" and f[3] == 0x01
    ends = []
    for end in (phone, watch):
        seen = collect(end, connection_complete)
        complete = next((f for f in seen if connection_complete(f)), None)
        assert complete is not None and complete[4] == 0x00, [f.hex(" ") for f in seen]
        ends.append(complete[5:7])
    return radio, watch, phone, ends[0], ends[1]


def encryption_change(f):
    return f[:2] == b"\x04\x08"


def disconnected(f):
    return f[:2] == b"\x04\x05"


def start_encryption(phone, watch, phone_handle, watch_handle):
    """7.8.24 from the phone; returns the LTK request the watch's host got."""
    told = reply_to(phone, command(0x2019, phone_handle + RAND
                                   + EDIV.to_bytes(2, "little") + LTK))
    status = next((f for f in told if f[:2] == b"\x04\x0f"), None)
    assert status is not None and status[3] == 0x00, [f.hex(" ") for f in told]

    def ltk_request(f):
        return f[:2] == b"\x04\x3e" and f[3] == 0x05
    seen = collect(watch, ltk_request)
    request = next((f for f in seen if ltk_request(f)), None)
    assert request is not None, [f.hex(" ") for f in seen]
    # 7.7.65.5: the handle, then Rand and EDIV exactly as the phone gave them.
    assert request[4:6] == watch_handle, request.hex(" ")
    assert request[6:14] == RAND and request[14:16] == EDIV.to_bytes(2, "little"), \
        request.hex(" ")
    # And the phone has not been told anything yet: the watch has not answered.
    told += frames(phone, wait=0.2)
    assert not any(encryption_change(f) for f in told), \
        ("the phone was told before the watch answered", [f.hex(" ") for f in told])
    return request


def test_the_watch_is_asked_for_its_key_and_the_right_one_encrypts():
    """Bumble's controller reported every link encrypted without asking the
    peripheral's host for its key, so the firmware never looked up a bond and
    could never refuse one. The right key from the watch encrypts both ends."""
    radio, watch, phone, phone_handle, watch_handle = connected()
    start_encryption(phone, watch, phone_handle, watch_handle)
    seen = reply_to(watch, command(0x201A, watch_handle + LTK))
    seen += collect(watch, encryption_change)
    # 7.8.25's Command Complete first, then 7.7.8.
    kinds = [f[:2] for f in seen]
    assert b"\x04\x0e" in kinds and b"\x04\x08" in kinds, [f.hex(" ") for f in seen]
    assert kinds.index(b"\x04\x0e") < kinds.index(b"\x04\x08"), [f.hex(" ") for f in seen]
    change = next(f for f in seen if encryption_change(f))
    assert change[3] == 0x00 and change[4:6] == watch_handle and change[6] == 1, change.hex(" ")
    seen = collect(phone, encryption_change)
    change = next((f for f in seen if encryption_change(f)), None)
    assert change is not None, [f.hex(" ") for f in seen]
    assert change[3] == 0x00 and change[4:6] == phone_handle and change[6] == 1, change.hex(" ")
    assert watch.controller.ltk_answers == ["match"]
    radio.close()


def test_a_watch_with_no_key_leaves_the_phone_unencrypted_and_connected():
    """What a watch that lost its bond does: a negative reply, and the phone
    hears PIN or Key Missing (0x06). The connection stays up."""
    radio, watch, phone, phone_handle, watch_handle = connected()
    start_encryption(phone, watch, phone_handle, watch_handle)
    reply_to(watch, command(0x201B, watch_handle))
    seen = collect(phone, encryption_change)
    change = next((f for f in seen if encryption_change(f)), None)
    assert change is not None, [f.hex(" ") for f in seen]
    assert change[3] == 0x06 and change[6] == 0, change.hex(" ")
    assert not any(disconnected(f) for f in seen + frames(watch, wait=0.2))
    assert watch.controller.ltk_answers == ["negative"]
    radio.close()


def test_a_wrong_key_drops_the_link_on_both_ends():
    """On air a key mismatch is a MIC failure on the first encrypted PDU:
    both ends lose the connection with 0x3D and nobody hears "wrong key"."""
    radio, watch, phone, phone_handle, watch_handle = connected()
    start_encryption(phone, watch, phone_handle, watch_handle)
    answered = reply_to(watch, command(0x201A, watch_handle + bytes(16)))
    for end, handle, seen in ((phone, phone_handle, []), (watch, watch_handle, answered)):
        seen = seen + collect(end, disconnected)
        event = next((f for f in seen if disconnected(f)), None)
        assert event is not None, (end.name, [f.hex(" ") for f in seen])
        assert event[4:6] == handle and event[6] == 0x3D, event.hex(" ")
        assert not any(encryption_change(f) and f[3] == 0 for f in seen), \
            [f.hex(" ") for f in seen]
    assert watch.controller.ltk_answers == ["mismatch"]
    radio.close()


# -- the interrupt that carries an unsolicited event ---------------------------

def test_the_bleif_raises_the_ble_interrupt_when_data_arrives_unasked():
    """The firmware finds an event it did not ask for through IRQ 12.

    ``HciDrvRadioBoot`` ends (0x0004704E) with INTCLR 0x281, INTEN 0x281 and
    ``NVIC_EnableIRQ(12)``; the ISR (0x000889CC -> 0x00046EE8) reads
    INTSTAT & INTEN, clears it, and wakes the HCI driver's task. Bit 7 of that
    mask is BLECIRQ -- the same "controller has data" that BSTATUS bit 7
    carries, latched on its rising edge.
    """
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.controller = BleController()
    bleif.write(Bleif.INTEN, 4, 0x281)
    assert bleif.pending_irqs() == [], "nothing has arrived"
    bleif.controller.to_host(bytes.fromhex("0700043e0501000000"))
    assert bleif.pending_irqs() == [IRQ_BLE]
    assert bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_BLECIRQ
    # The ISR clears what it saw. The data is still there, but the edge is
    # gone: a level here would re-enter the ISR until the task got to read.
    bleif.write(Bleif.INTCLR, 4, 0x281)
    assert bleif.pending_irqs() == []
    assert not bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_BLECIRQ
    # The task reads it the way the firmware does, a transfer through the
    # FIFO, and the line drops with the last word.
    bleif.write(Bleif.CMD, 4, (9 << 8) | Bleif.CMD_READ)
    for _ in range(3):
        bleif.read(Bleif.FIFOPOP, 4)
    assert not bleif.read(Bleif.BSTATUS, 4) & Bleif.BSTATUS_HAS_DATA
    # With BLECIRQ masked -- which the blocking transfers do (INTEN 0x201 at
    # 0x00089274) -- the next arrival latches but does not interrupt...
    bleif.write(Bleif.INTCLR, 4, 0xFFFFFFFF)
    bleif.write(Bleif.INTEN, 4, 0x201)
    bleif.controller.to_host(b"\x02\x00\x04\x0e")
    assert bleif.pending_irqs() == []
    assert bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_BLECIRQ
    # ...until the mask comes off again.
    bleif.write(Bleif.INTEN, 4, 0x281)
    assert bleif.pending_irqs() == [IRQ_BLE]


def test_the_interrupt_is_not_raised_with_nothing_behind_the_fifo():
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.write(Bleif.INTEN, 4, 0x281)
    assert bleif.pending_irqs() == []


# -- and the firmware against all of it --------------------------------------

def boot():
    global _boot
    if _boot is not None:
        return _boot
    img = image_mod.load(IMAGE)
    m = Apollo3Machine(img, log=quiet, trace=None, fast_hook=True, ble=True)
    attach_mspi_devices(m, resource_blob=RESOURCES, log=quiet)
    attach_touch_panel(m, log=quiet)
    attach_motion_sensor(m, log=quiet)
    attach_pmu(m, log=quiet)
    controller = attach_ble_controller(m, BumbleController(log=quiet), log=quiet)
    stats = m.run(max_instructions=BUDGET, slice_size=25_000)
    _boot = (m, controller, stats)
    return _boot


def test_the_firmware_gets_through_hci_init_and_advertises():
    m, controller, stats = boot()
    assert not m.faults, list(m.faults)
    assert OPCODE_LE_READ_LOCAL_P256_PUBLIC_KEY in controller.hci_commands, \
        "HCI init never reached the P-256 request"
    assert LE_SET_ADVERTISING_ENABLE in controller.hci_commands, (
        "init finished but the watch never asked to advertise; the last "
        f"commands were {[hex(c) for c in controller.hci_commands[-6:]]}")
    assert controller.is_advertising, "advertising was requested but bumble is not advertising"


def test_the_firmware_takes_the_ble_interrupt():
    m, _, _ = boot()
    assert m.cortexm.exception_counts.get(16 + IRQ_BLE, 0) > 0, \
        "IRQ 12 was never delivered -- Bleif.pending_irqs is not wired in"


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
