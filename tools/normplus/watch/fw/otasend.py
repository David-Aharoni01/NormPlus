"""Sending a firmware update to a watch, emulated or physical (#67, #70).

The Apollo DFU conversation as ``ApolloOtaSession`` (``protocol/.../ota/ApolloOta.kt``)
has it and as the firmware answers it -- ``ew_mod_ota_protocol.c``, the handler at
0x0003B798 -- driven from Python so that the same code can rehearse against the
emulated watch and then send to the physical one. Both transports provide the
four DFU calls (``fw.phone.Phone``, ``physical.PhysicalPhone``), so the only
difference between a rehearsal and the real thing is which one is passed in.

The sequence, and what each step is answered with (docs/protocol.md, "Apollo DFU"):

1. ``UPGRADE_MODE`` (0x0E SET [00]) on 8001 -> the generic ack. A mode switch
   inside the running application; nothing resets.
2. ``BT_PARAM`` [10 02] -> no reply; the app waits 500 ms and carries on.
3. ``INIT`` [01][length LE32] -> ``01 01``.
4. ``SET`` [02][type][address 4][length 4][crc 4][0A] -> ``02 01``.
5. The data in 200-byte pieces, each in ``write_size`` writes (0x80 for the
   resource type, 0x14 for every other), each answered
   ``03 01 <01 | 02 at a page boundary | 04 for the last> <bytes so far LE32>``.
6. ``CRC`` [04] -> ``04 01``.
7. ``REBOOT`` [05] -> ``05 01``. A resource update stays up and shows "Upgrade
   Success"; a main-MCU update resets, and what runs next is the first-stage
   bootloader (#67, docs/firmware.md section 5).

**The rails, because this is the call that can brick a watch.** A main-MCU update
(type 1) is refused unless it is asked for explicitly, exactly as the Kotlin
refuses it: the code that receives an update is the code it replaces, so an image
that does not boot leaves no way back without SWD (#14). On top of that the image
itself is checked before a byte is sent -- a Telink image is refused outright, and
so is one whose length or CRC does not match its own contents, because the
bootloader checks that CRC and a half-sealed image would be a brick with no
recovery. Nothing here can be skipped with a flag.
"""

from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass, field
from typing import Callable, Optional

from .phone import (OTA_BT_PARAM, OTA_TYPE_MCU, SET, UPGRADE_MODE,
                    ack, apollo_crc, frame, ota_init, ota_pieces, ota_set_header,
                    ota_write_size)

#: The types the firmware's SET handler accepts (0x0003B8A8).
ACCEPTED_TYPES = (1, 2, 3, 4)
#: Where a main-MCU image stages, from its own header (#67).
STAGING_ADDRESS = 0x0FC00000
#: The 0x6F command that switches the application into update mode.
CMD_CRC, CMD_REBOOT = b"\x04", b"\x05"


class OtaError(Exception):
    """The watch refused a step, or the image is not one worth sending."""


@dataclass
class Progress:
    """How far a send has got, for a caller that wants to print it."""

    pieces: int = 0
    sent: int = 0
    bytes_sent: int = 0
    started: float = field(default_factory=time.monotonic)

    @property
    def seconds(self) -> float:
        return time.monotonic() - self.started

    def summary(self) -> str:
        rate = self.bytes_sent / self.seconds if self.seconds else 0
        left = ((self.pieces - self.sent) / self.sent * self.seconds) if self.sent else 0
        return (f"{self.sent}/{self.pieces} pieces, {self.bytes_sent:,} bytes, "
                f"{rate / 1024:.1f} KB/s, about {left / 60:.1f} min left")


def check_image(raw: bytes, update_type: int, *, allow_mcu: bool = False) -> tuple[bytes, bytes]:
    """Split an update file into (address, content) after every check worth making.

    Raises :class:`OtaError` rather than sending anything questionable.
    """
    if update_type not in ACCEPTED_TYPES:
        raise OtaError(f"update type {update_type} is not one the watch accepts "
                       f"({', '.join(map(str, ACCEPTED_TYPES))})")
    if len(raw) < 0x34:
        raise OtaError(f"{len(raw)} bytes is too small to be an update")
    if raw[8:12] == b"KNLT":
        raise OtaError("this is a Telink (Norm 1) image; it must never reach a Norm 2 "
                       "(docs/firmware.md section 8)")
    address, content = raw[:4], raw[4:]
    if update_type == OTA_TYPE_MCU:
        if not allow_mcu:
            raise OtaError("a main-MCU update (type 1) replaces the code that receives "
                           "updates: there is no way back if it does not boot (#14). "
                           "Ask for it explicitly if that is really the intention")
        # The image's own header, and the two fields the bootloader reads. An
        # unsealed image is a brick: `normfw seal` after any patch.
        from . import image as image_mod

        link = int.from_bytes(content[0:4], "little")
        staging = int.from_bytes(content[4:8], "little")
        length = int.from_bytes(content[8:12], "little")
        declared = int.from_bytes(content[12:16], "little")
        payload = raw[0x30:]
        if int.from_bytes(address, "little") != STAGING_ADDRESS:
            raise OtaError(f"the image stages at 0x{int.from_bytes(address, 'little'):08X}, "
                           f"not 0x{STAGING_ADDRESS:08X}")
        if staging != STAGING_ADDRESS or link != 0x00020000:
            raise OtaError(f"the image's header says link 0x{link:08X}, staging "
                           f"0x{staging:08X} -- not an Apollo application image")
        if length != len(payload):
            raise OtaError(f"the image's length field says {length} bytes, the file has "
                           f"{len(payload)}: seal it (`normfw seal`) before sending it")
        computed = image_mod.image_crc32(payload)
        if computed != declared:
            raise OtaError(f"the image's CRC is 0x{declared:08X} but its contents give "
                           f"0x{computed:08X}: seal it (`normfw seal`) before sending it. "
                           "The bootloader checks this, and a wrong one is unrecoverable")
    return address, content


async def send_update(conv, raw: bytes, update_type: int, *, allow_mcu: bool = False,
                      log: Callable = print, progress_every: int = 200,
                      reply_timeout: float = 5.0,
                      reboot_timeout: float = 15.0) -> Progress:
    """Send one update over *conv*, a phone that has already connected and bound.

    Returns when REBOOT has been answered. Raises :class:`OtaError` the moment
    the watch refuses a step, naming what it refused.
    """
    address, content = check_image(raw, update_type, allow_mcu=allow_mcu)
    pieces = ota_pieces(content)
    write_size = ota_write_size(update_type)
    state = Progress(pieces=len(pieces))
    log(f"  [ota] type {update_type}, {len(content):,} bytes to "
        f"0x{int.from_bytes(address, 'little'):08X}, {len(pieces):,} pieces of "
        f"{write_size}-byte writes, transport CRC {apollo_crc(content)[:2][::-1].hex()}")

    reply = await conv.exchange(frame(UPGRADE_MODE, SET, b"\x00"), timeout=reply_timeout)
    if reply != ack(UPGRADE_MODE):
        raise OtaError(f"UPGRADE_MODE was not acknowledged: {(reply or b'').hex(' ') or 'nothing'}")
    await conv.open_dfu()
    await conv.dfu_command(OTA_BT_PARAM, timeout=0.5)       # no reply; the app waits
    await asyncio.sleep(0.5)

    async def command(data: bytes, what: str, timeout: float) -> bytes:
        got = await conv.dfu_command(data, timeout=timeout)
        if got is None:
            raise OtaError(f"the watch did not answer {what}")
        if len(got) < 2 or got[0] != data[0] or got[1] != 0x01:
            raise OtaError(f"the watch refused {what}: {got.hex(' ')}")
        return got

    await command(ota_init(content), "INIT", reply_timeout)
    log("  [ota] INIT accepted; the SET erases the target now")
    await command(ota_set_header(update_type, address, content), f"SET (type {update_type})",
                  max(reply_timeout, 30.0))
    log("  [ota] SET accepted -- the update is now in progress and must be finished")

    for i, piece in enumerate(pieces):
        got = await conv.dfu_piece(piece, timeout=reply_timeout, write_size=write_size)
        state.sent, state.bytes_sent = i + 1, state.bytes_sent + len(piece)
        if got is None:
            raise OtaError(f"no answer to piece {i + 1} of {len(pieces)} "
                           f"({state.bytes_sent:,} bytes in)")
        if got[:2] != b"\x03\x01" or int.from_bytes(got[3:7], "little") != state.bytes_sent:
            raise OtaError(f"piece {i + 1} of {len(pieces)} was refused: {got.hex(' ')} "
                           f"(expected {state.bytes_sent:,} bytes so far)")
        if (i + 1) % progress_every == 0:
            log(f"  [ota] {state.summary()}")

    await command(CMD_CRC, "CRC", max(reply_timeout, 30.0))
    log("  [ota] CRC accepted: the watch has the whole image")
    await command(CMD_REBOOT, "REBOOT", reboot_timeout)
    log(f"  [ota] REBOOT accepted after {state.seconds / 60:.1f} min"
        + (" -- the watch resets now, and the bootloader runs next"
           if update_type == OTA_TYPE_MCU else " -- the watch stays up"))
    return state
