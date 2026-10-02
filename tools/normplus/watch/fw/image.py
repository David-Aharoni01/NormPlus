"""Parsing and loading of the Ambiq firmware image.

Format is documented in ``docs/firmware.md`` §2 and implemented independently in
``normfw`` (``normplus/firmware/image_tool.py``); this module is the emulator's loader view of it.

::

    +0x00  destination address (staging), stripped by the phone before transfer
    +0x04  link address              <- where the payload executes
    +0x08  staging address again
    +0x0C  payload byte count        = filesize - 48
    +0x10  image CRC                 CRC-32 poly 0x1EDC6F41, MSB-first, init 0
    +0x14  override GPIO
    +0x18  override polarity
    +0x1C  initial stack pointer
    +0x20  reset vector
    +0x30  ---- payload: the Cortex-M vector table ----
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path

#: CRC-32 used by the first-stage bootloader: Castagnoli's polynomial, but
#: MSB-first with a zero seed and no final xor (docs/firmware.md §7).
_IMAGE_CRC_POLY = 0x1EDC6F41

#: The payload begins here in the file; the 4-byte destination address plus the
#: 44-byte am_bootloader_image_t header precede it.
PAYLOAD_OFFSET = 0x30


def image_crc32(data: bytes) -> int:
    crc = 0
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ _IMAGE_CRC_POLY) & 0xFFFFFFFF if crc & 0x80000000 else (crc << 1) & 0xFFFFFFFF
    return crc


def transport_crc16(data: bytes) -> int:
    """The phone-side CRC-16 from ``OtaUtil.smali`` (a non-standard CCITT variant)."""
    crc = 0xFFFF
    for byte in data:
        crc = ((crc << 8) | (crc >> 8)) & 0xFFFF
        crc ^= byte & 0xFF
        crc ^= (crc & 0xFF) >> 4
        crc ^= (crc << 12) & 0xFFFF
        crc ^= ((crc & 0xFF) << 5) & 0xFFFF
    return crc


@dataclass
class FirmwareImage:
    path: Path
    raw: bytes

    destination_address: int
    link_address: int
    staging_address: int
    payload_length: int
    declared_crc: int
    override_gpio: int
    override_polarity: int
    initial_sp: int
    reset_vector: int

    @property
    def payload(self) -> bytes:
        """The bytes that execute at :attr:`link_address` (starts with the vector table)."""
        return self.raw[PAYLOAD_OFFSET : PAYLOAD_OFFSET + self.payload_length]

    @property
    def computed_crc(self) -> int:
        return image_crc32(self.payload)

    @property
    def crc_ok(self) -> bool:
        return self.computed_crc == self.declared_crc

    @property
    def length_ok(self) -> bool:
        return len(self.raw) - 48 == self.payload_length

    @property
    def end_address(self) -> int:
        return self.link_address + self.payload_length

    def vector(self, index: int) -> int:
        """Entry *index* of the Cortex-M vector table (0=SP, 1=Reset, 15=SysTick, 16+=IRQ0+)."""
        return struct.unpack_from("<I", self.payload, index * 4)[0]

    def describe(self) -> str:
        lines = [
            f"{self.path.name}  ({len(self.raw)} bytes)",
            f"  destination   0x{self.destination_address:08X}  (OTA staging)",
            f"  link address  0x{self.link_address:08X}",
            f"  payload       {self.payload_length} bytes  -> 0x{self.link_address:08X}"
            f"-0x{self.end_address - 1:08X}",
            f"  image CRC     declared 0x{self.declared_crc:08X}  computed 0x{self.computed_crc:08X}"
            f"  {'OK' if self.crc_ok else 'MISMATCH'}",
            f"  initial SP    0x{self.initial_sp:08X}",
            f"  reset vector  0x{self.reset_vector:08X}",
            f"  transport CRC-16 (what the phone sends) 0x{transport_crc16(self.raw[4:]):04X}",
        ]
        return "\n".join(lines)


def load(path: str | Path) -> FirmwareImage:
    path = Path(path)
    raw = path.read_bytes()
    if len(raw) < PAYLOAD_OFFSET + 64:
        raise ValueError(f"{path}: too small to be an Apollo image ({len(raw)} bytes)")

    dest, link, staging, length, crc, gpio, polarity, sp, reset = struct.unpack_from("<9I", raw, 0)

    # Refuse the Telink Norm 1 image outright — it is served by the public OTA
    # endpoint and is for a different SoC entirely (docs/firmware.md §8).
    if raw[8:12] == b"KNLT":
        raise ValueError(f"{path}: this is a Telink (Norm 1) image, not an Apollo3 one")

    img = FirmwareImage(
        path=path,
        raw=raw,
        destination_address=dest,
        link_address=link,
        staging_address=staging,
        payload_length=length,
        declared_crc=crc,
        override_gpio=gpio,
        override_polarity=polarity,
        initial_sp=sp,
        reset_vector=reset,
    )

    if not img.length_ok:
        raise ValueError(
            f"{path}: declared payload length {length} does not match file size "
            f"{len(raw)} (expected {len(raw) - 48})"
        )
    if not (0x00000000 <= link < 0x00100000):
        raise ValueError(f"{path}: link address 0x{link:08X} is outside Apollo3 internal flash")
    return img
