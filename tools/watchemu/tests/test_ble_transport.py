"""The BLEIF FIFO, and the firmware's first HCI packet.

Once power-up worked (test_ble_powerup.py) the firmware's Cordio host started
driving the transport, and every field below came out of watching it rather
than out of a datasheet:

* ``am_hal_ble_wakeup_set`` (0x0008A6B0) drives PWRCMD bits [3:2] -- 3 to wake
  the controller, 2 to let it sleep.
* The send path (0x000891F0) wakes it, then waits up to 300 x 160us at
  0x00089590 for **BSTATUS bit 3**. That is the controller answering "awake,
  SPI ready", and it is the single gate on the whole transport: with it clear
  the firmware retries for 48ms, gives up, and does it all again.
* 0x00089672 then writes CMD -- direction in [4:0], TSIZE in [19:8] -- and the
  loop at 0x0008931E pushes 32-bit words into FIFOPUSH while **FIFOPTR bits
  [15:8]** (free space in the write FIFO) is at least 4, finishing on
  **INTSTAT bit 0**.
* The receive path waits at 0x00089C92 for **BSTATUS bit 7**, then asks for
  five bytes. Its drain loop at 0x000899B0 reads the available count from
  **FIFOPTR bits [23:16]** and pops words from FIFOPOP at 0x00089A10.

The firmware's first words to its radio are ``01 bb f1 02 54 06``, and that
turned out to be the first command of a firmware download rather than anything
standard: the watch's controller is a **Nationz NZ8801** that boots empty. See
:class:`NationzController` for the protocol, which the firmware itself spells
out -- it builds the reply it expects and memcmps against it at 0x00089CEA.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_ble_transport.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw import image as image_mod
from normwatch.fw.devices import (NationzController, attach_ble_controller,
                                  attach_motion_sensor,
                                  attach_mspi_devices, attach_pmu,
                                  attach_touch_panel)
from normwatch.fw.machine import Apollo3Machine
from normwatch.fw.peripherals import BleController, Bleif

REPO = Path(__file__).resolve().parents[3]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"

BLEIF_BASE = 0x5000C000
#: The transport runs at ~0.27s of watch time, well inside this.
BUDGET = 40_000_000

#: The firmware's first packet: H4 command, opcode 0xF1BB, two bytes of
#: parameters. Six bytes, which is why the first CMD is 0x00000601.
FIRST_PACKET = bytes.fromhex("01bbf1025406")

_img = image_mod.load(IMAGE)
_runs = {}


def quiet(*a, **k):
    pass


class Answering(BleController):
    """Answers every command with a Command Complete carrying status 0.

    Not what the real controller says -- that is still to be worked out -- but
    enough to prove the receive path moves bytes.
    """

    def from_host(self, packet):
        super().from_host(packet)
        if len(packet) >= 3 and packet[0] == 0x01:
            opcode = packet[1] | (packet[2] << 8)
            self.to_host(bytes([0x04, 0x0E, 0x04, 0x01,
                                opcode & 0xFF, opcode >> 8, 0x00]))


def boot(kind):
    """kind: None for no controller, 'recording', or 'answering'."""
    if kind in _runs:
        return _runs[kind]
    m = Apollo3Machine(_img, log=quiet, trace=None, fast_hook=True, ble=True)
    attach_mspi_devices(m, resource_blob=RESOURCES, log=quiet)
    attach_touch_panel(m, log=quiet)
    attach_motion_sensor(m, log=quiet)
    attach_pmu(m, log=quiet)
    controller = None
    if kind == "recording":
        controller = attach_ble_controller(m, BleController(), log=quiet)
    elif kind == "answering":
        controller = attach_ble_controller(m, Answering(), log=quiet)
    elif kind == "nationz":
        controller = attach_ble_controller(m, log=quiet)   # the default
    m.run(max_instructions=BUDGET, slice_size=25_000)
    _runs[kind] = (m, m.bus.by_base[BLEIF_BASE], controller)
    return _runs[kind]


# -- the register fields, against the sites that read them -------------------

def test_bstatus_reports_nothing_when_no_controller_is_fitted():
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.storage[Bleif.PWRCMD] = Bleif.PWRCMD_WAKE
    status = bleif.read(Bleif.BSTATUS, 4)
    assert (status >> 8) & 0x7 == 3, "power-on poll (0x0008A422) must still pass"
    assert not status & Bleif.BSTATUS_SPI_READY, "claimed a controller that is not there"
    assert not status & Bleif.BSTATUS_HAS_DATA


def test_bit_3_follows_the_firmwares_own_wake_request():
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.controller = BleController()
    assert not bleif.read(Bleif.BSTATUS, 4) & Bleif.BSTATUS_SPI_READY
    # am_hal_ble_wakeup_set(handle, 1) at 0x0008A6B0: PWRCMD bits [3:2] = 3.
    bleif.write(Bleif.PWRCMD, 4, 0x0D)
    assert bleif.awake
    assert bleif.read(Bleif.BSTATUS, 4) & Bleif.BSTATUS_SPI_READY
    # ...and (handle, 0) puts bits [3:2] back to 2.
    bleif.write(Bleif.PWRCMD, 4, 0x09)
    assert not bleif.awake
    assert not bleif.read(Bleif.BSTATUS, 4) & Bleif.BSTATUS_SPI_READY


def test_bit_7_means_the_controller_has_something():
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.controller = BleController()
    assert not bleif.read(Bleif.BSTATUS, 4) & Bleif.BSTATUS_HAS_DATA
    bleif.controller.to_host(b"\x04\x0e\x04\x01\x00")
    assert bleif.read(Bleif.BSTATUS, 4) & Bleif.BSTATUS_HAS_DATA


def test_fifoptr_puts_each_count_in_the_field_that_reads_it():
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.controller = BleController()
    # 0x0008931E reads [15:8] as room to push; 0x000899B4 reads [23:16] as
    # bytes available. Putting either in the wrong field stalls that path --
    # the placeholder this replaced reported 0x20 in [23:16] and 0 in [15:8],
    # so the send loop waited for room for ever.
    ptr = bleif.read(Bleif.FIFOPTR, 4)
    assert (ptr >> 8) & 0xFF == Bleif.FIFO_DEPTH, "no room to push"
    assert (ptr >> 16) & 0xFF == 0, "claimed unread bytes"
    bleif.controller.to_host(b"12345")
    bleif.write(Bleif.CMD, 4, (5 << 8) | Bleif.CMD_READ)
    # Rounded up to a whole word: the FIFO holds words, and the drain loop
    # refuses to move at all below four bytes (0x000899C2). See the two-byte
    # read below, which deadlocks against that threshold otherwise.
    assert (bleif.read(Bleif.FIFOPTR, 4) >> 16) & 0xFF == 8


def test_a_two_byte_read_is_still_above_the_drain_threshold():
    # The post-download announcement is read two bytes at a time (0x00089F64).
    # Reporting a literal 2 here parks the firmware for ever against its own
    # "at least four bytes" check, which looks exactly like a dead controller.
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.controller = BleController()
    bleif.controller.to_host(b"\x04\xee\xf1\x01\x00")
    bleif.write(Bleif.CMD, 4, (2 << 8) | Bleif.CMD_READ)
    assert (bleif.read(Bleif.FIFOPTR, 4) >> 16) & 0xFF >= 4


def test_a_write_transfer_hands_the_controller_exactly_tsize_bytes():
    # Six bytes go out as two 32-bit pushes; the tail word is partial and the
    # extra two bytes are not part of the packet.
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.controller = BleController()
    bleif.write(Bleif.CMD, 4, 0x00000601)          # write, TSIZE = 6
    assert not bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_CMDCMP
    bleif.write(Bleif.FIFOPUSH, 4, 0x02F1BB01)
    bleif.write(Bleif.FIFOPUSH, 4, 0x10000654)
    assert bleif.controller.sent == [FIRST_PACKET], bleif.controller.sent
    assert bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_CMDCMP, "transfer never completed"
    bleif.write(Bleif.INTCLR, 4, Bleif.INT_CMDCMP)
    assert not bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_CMDCMP


def test_a_read_transfer_pops_what_the_controller_queued():
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.controller = BleController()
    bleif.controller.to_host(bytes.fromhex("040e040100"))
    bleif.write(Bleif.CMD, 4, (5 << 8) | Bleif.CMD_READ)
    first = bleif.read(Bleif.FIFOPOP, 4)
    second = bleif.read(Bleif.FIFOPOP, 4)
    assert first == 0x01040E04, hex(first)
    assert second & 0xFF == 0x00, hex(second)
    assert bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_CMDCMP


# -- and the firmware driving all of it ---------------------------------------

def test_the_firmware_sends_its_first_packet():
    _, bleif, controller = boot("recording")
    assert controller.sent, (
        "the firmware got as far as CMD but pushed nothing -- check FIFOPTR "
        "bits [15:8], which is what 0x0008931E waits on")
    assert controller.sent[0] == FIRST_PACKET, controller.sent[0].hex(" ")
    assert bleif.packets_sent >= 1


def test_the_first_command_is_a_six_byte_write():
    _, bleif, _ = boot("recording")
    assert bleif.commands >= 1
    assert len(bleif.tx) >= 6


def test_nothing_is_sent_when_no_controller_is_fitted():
    # Bit 3 never comes up, so the send path times out instead of pushing --
    # which is what a boot did before any of this existed.
    _, bleif, _ = boot(None)
    assert bleif.packets_sent == 0
    assert len(bleif.tx) == 0


def test_an_answer_is_read_back_and_stops_the_retry_storm():
    m, bleif, controller = boot("answering")
    assert bleif.packets_received >= 1, "the receive path never completed"
    # The cost of the whole thing is the poll: unanswered it is ~1M BSTATUS
    # reads in this window, answered it is a few dozen.
    reads = sum(bleif.reads.values())
    assert reads < 10_000, f"{reads} BLEIF reads -- the transport is still spinning"
    _, unanswered, _ = boot("recording")
    assert sum(unanswered.reads.values()) > 100_000, \
        "the unanswered case is supposed to be the slow one"


# -- the controller the watch actually has ------------------------------------

def test_the_download_completes_and_the_link_becomes_hci():
    _, bleif, controller = boot("nationz")
    assert controller.booted, (
        "the controller never got its go-ahead -- after 01 EE F1 it has to ack "
        "and then announce itself unsolicited (0x00089F56 waits for that)")
    assert bleif.packets_received > 20
    assert controller.hci_commands, "nothing was sent once the link was HCI"
    # The first thing across the finished link is a vendor command.
    assert controller.hci_commands[0] == 0xFD04, \
        hex(controller.hci_commands[0])


def test_all_four_sections_arrive_at_the_length_they_declared():
    _, _, controller = boot("nationz")
    assert sorted(controller.sections) == [0xBB, 0xCC, 0xDD, 0xEE], \
        [hex(s) for s in sorted(controller.sections)]
    for section, declared in controller.declared.items():
        got = len(controller.sections[section])
        assert got == declared, \
            f"section 0x{section:02X}: declared {declared} bytes, got {got}"
    # 0xEE declares nothing: its "begin" is the go-ahead, not a section.
    assert controller.declared[0xEE] == 0


def test_the_nvds_names_the_part():
    _, _, controller = boot("nationz")
    tags = controller.nvds_tags()
    assert tags, "no NVDS section was downloaded"
    assert tags[2].rstrip(b"\x00") == b"NZ8801V1A", tags[2]
    assert len(tags[1]) == 6, "tag 1 should be a BD address"


def test_the_go_ahead_gets_one_message_not_two():
    """It is an announcement, not an ack, and sending both desynchronises.

    The controller restarts into what it was just given, so it cannot answer
    the 0xEE command -- the only thing it sends is the announcement the
    firmware waits for at 0x00089F56, which it reads as one message (two bytes
    then three). Queueing an ack *and* an announcement leaves five bytes
    behind, every later read is skewed by them, and the firmware responds by
    restarting the whole download from 0xBB.
    """
    controller = NationzController()
    controller.from_host(bytes.fromhex("01eef1020000"))
    assert controller.available() == 5, (
        f"{controller.available()} bytes queued for the go-ahead, wanted 5")
    assert controller.booted


def test_a_read_is_padded_to_the_size_that_was_asked_for():
    """A read clocks TSIZE bytes off the bus however few the controller has.

    The firmware reads an HCI event in a fixed nine-byte window (0x0008A058).
    Handing back only the seven bytes of a Command Complete leaves its drain
    loop waiting for two more for ever.
    """
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.controller = BleController()
    bleif.controller.to_host(bytes.fromhex("040e04010 4fd00".replace(" ", "")))
    bleif.write(Bleif.CMD, 4, (9 << 8) | Bleif.CMD_READ)
    assert (bleif.read(Bleif.FIFOPTR, 4) >> 16) & 0xFF >= 9, "short read not padded"
    words = [bleif.read(Bleif.FIFOPOP, 4) for _ in range(3)]
    assert bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_CMDCMP
    assert words[0] == 0x01040E04, hex(words[0])


def test_intstat_bit_8_mirrors_the_ready_state():
    """0x000895A4 switches from BSTATUS bit 3 to INTSTAT bit 8 mid-run.

    Which one it reads depends on a handle byte the firmware sets at
    0x0008A076, at the very end of bring-up. Model only BSTATUS and the
    firmware gets all the way up and then concludes its controller has gone.
    """
    bleif = Bleif(BLEIF_BASE, 0x1000)
    bleif.controller = BleController()
    bleif.write(Bleif.PWRCMD, 4, 0x09)             # asleep
    assert not bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_CONTROLLER_READY
    bleif.write(Bleif.PWRCMD, 4, 0x0D)             # awake
    assert bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_CONTROLLER_READY
    # It is a level, so clearing it does not stick.
    bleif.write(Bleif.INTCLR, 4, 0xFFFFFFFF)
    assert bleif.read(Bleif.INTSTAT, 4) & Bleif.INT_CONTROLLER_READY


def test_a_working_radio_costs_the_boot_nothing():
    # The whole point: unanswered, the transport spins and a boot pays for it.
    # With the controller doing its part the machine runs as it does with the
    # radio switched off.
    _, bleif, _ = boot("nationz")
    reads = sum(bleif.reads.values())
    assert reads < 20_000, f"{reads} BLEIF reads -- something is still spinning"


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
