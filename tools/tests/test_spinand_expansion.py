"""The SPI NAND is driven over a quad-configured bus, so its commands arrive
bit-expanded. These tests pin the codec to the firmware's own packer and
un-packer rather than to my reading of them.

Run with:  uv run normtest test_spinand_expansion
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus.watch.fw.devices import (CMD_GET_FEATURE, CMD_PAGE_READ, CMD_READ_CACHE_X4,
                                  FEATURE_STATUS, SpiNand, expand_reply,
                                  is_expanded_frame, unexpand_command)


def firmware_pack(command: bytes) -> bytes:
    """The packer at 0x00053788, transcribed instruction for instruction."""
    out = bytearray(b"\xee" * (len(command) * 4))
    for i, b in enumerate(command):
        out[i * 4 + 0] |= (0x10 & (b >> 3)) | ((b >> 6) & 1)
        out[i * 4 + 1] |= (0x10 & (b >> 1)) | ((b >> 4) & 1)
        out[i * 4 + 2] |= ((b >> 2) & 1) | ((b & 8) << 1)
        out[i * 4 + 3] |= (b & 1) | ((b & 2) << 3)
    return bytes(out)


def firmware_unpack(reply: bytes) -> int:
    """The un-packer at 0x000534EC, transcribed instruction for instruction."""
    b0, b1, b2, b3 = reply[:4]
    v = ((b0 & 0x20) << 2) | ((b0 & 0x02) << 5)
    v |= (b1 & 0x20) | ((b1 & 0x02) << 3)
    v |= ((b2 >> 2) & 0x08) | ((b2 & 0x02) << 1)
    v |= ((b3 >> 4) & 0x02) | ((b3 >> 1) & 0x01)
    return v


def test_unexpand_matches_the_firmwares_packer():
    for command in (b"\x13\x00\x8f\x00", b"\x0f\xc0", b"\x6b\x05\xbf\xff", bytes(range(16))):
        frame = firmware_pack(command)
        assert is_expanded_frame(frame), command.hex()
        assert unexpand_command(frame) == command, command.hex()


def test_reply_expansion_survives_the_firmwares_unpacker():
    for value in range(256):
        assert firmware_unpack(expand_reply(bytes([value]))) == value, value


def test_raw_commands_are_not_mistaken_for_expanded_ones():
    for command in (b"\x9f", b"\x0f\xc0", b"\x13\x00\x8f\x00", b"\xff", b"\x1f\xa0\x00"):
        assert not is_expanded_frame(command), command.hex()


def test_page_read_and_get_feature_over_the_expanded_bus():
    nand = SpiNand(log=lambda *a, **k: None)
    nand.load(bytes(range(256)) * 8, 0)          # page 0 of die 0

    nand.exchange(firmware_pack(bytes([CMD_PAGE_READ, 0, 0, 0])), 0)
    assert nand.pages_read == 1

    # GET FEATURE answers on one lane, so four bytes come back per real byte.
    nand.features[FEATURE_STATUS] = 0x25
    nand.exchange(firmware_pack(bytes([CMD_GET_FEATURE, FEATURE_STATUS])), 0)
    reply = nand.exchange(b"", 4)
    assert len(reply) == 4
    assert firmware_unpack(reply) == 0x25

    # The 0x6B data phase is genuinely four-lane, so it must come back raw.
    nand.exchange(firmware_pack(bytes([CMD_READ_CACHE_X4, 0, 0, 0xFF])), 0)
    assert nand.exchange(b"", 8) == bytes(range(8))


def test_die_select_moves_the_row_window():
    nand = SpiNand(log=lambda *a, **k: None)
    nand.load(b"\xa5" * 2048, 0x0C780000)
    row = (0x0C780000 // nand.page_size) - nand.pages_per_die
    nand.exchange(bytes([0xC2, 0x01]), 0)              # select die 1
    nand.exchange(bytes([CMD_PAGE_READ]) + row.to_bytes(3, "big"), 0)
    nand.exchange(bytes([CMD_READ_CACHE_X4, 0, 0, 0xFF]), 0)
    assert nand.exchange(b"", 4) == b"\xa5\xa5\xa5\xa5"


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
