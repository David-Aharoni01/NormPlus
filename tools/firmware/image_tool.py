#!/usr/bin/env python3
"""Inspect, verify and re-seal Norm 2 (Apollo3) firmware images.

After patching bytes in a firmware image you must fix up two independent checksums
or the image will be rejected:

  1. The bootloader's image CRC, stored at file+0x10. Recovered by parameter search
     and verified against the factory image (see docs/firmware.md section 7):
         CRC-32, poly 0x1EDC6F41, MSB-first (NOT reflected), init 0, no final xor,
         computed over file[48:], whose length must equal the field at file+0x0C.
  2. The transport CRC-16 that the *phone* computes and sends in the OTA SET header
     (CRC-16/CCITT, poly 0x1021, init 0xFFFF, over file[4:]). Our ApolloOtaProtocol
     already computes this at flash time, so it needs no baking in -- it is printed
     here only so you can cross-check against the app's logs.

Usage:
    python tools/firmware/image_tool.py verify NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin
    python tools/firmware/image_tool.py seal patched.bin -o patched_sealed.bin
    python tools/firmware/image_tool.py seal patched.bin --in-place

"seal" recomputes the length field at +0x0C and the CRC at +0x10 from the actual
payload, leaving every other header field untouched.
"""

from __future__ import annotations

import argparse
import hashlib
import struct
import sys

HEADER_LEN = 48        # 4-byte OTA address + 44-byte bootloader image header
OFF_LINK = 0x04
OFF_LEN = 0x0C
OFF_CRC = 0x10
OFF_SP = 0x1C
OFF_RESET = 0x20

BOOTLOADER_POLY = 0x1EDC6F41   # MSB-first, init 0, no final xor


def _crc32_table(poly: int) -> list[int]:
    tab = []
    for i in range(256):
        c = i << 24
        for _ in range(8):
            c = ((c << 1) ^ poly) & 0xFFFFFFFF if c & 0x80000000 else (c << 1) & 0xFFFFFFFF
        tab.append(c)
    return tab


_TAB = _crc32_table(BOOTLOADER_POLY)


def bootloader_crc32(data: bytes, init: int = 0) -> int:
    """The CRC the first-stage bootloader checks. See module docstring."""
    c = init
    for b in data:
        c = ((c << 8) & 0xFFFFFFFF) ^ _TAB[((c >> 24) ^ b) & 0xFF]
    return c


def transport_crc16(data: bytes) -> int:
    """CRC-16/CCITT init 0xFFFF -- what the phone sends in the OTA SET header.

    Mirrors OtaUtil.crc16() / getApolloCrcCheck() in the original app, which
    transmits it as [lo, hi, 0x00, 0x00].
    """
    crc = 0xFFFF
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
    return crc


def parse(data: bytes) -> dict:
    if len(data) < HEADER_LEN + 4:
        raise ValueError(f"file is only {len(data)} bytes -- not a firmware image")
    if data[8:12] == b"KNLT":
        raise ValueError("this is a TELINK image ('KNLT' magic), not an Apollo3 image "
                         "-- see docs/firmware.md section 8")
    u = lambda off: struct.unpack_from("<I", data, off)[0]  # noqa: E731
    payload = data[HEADER_LEN:]
    return {
        "size": len(data),
        "dest": u(0x00),
        "link": u(OFF_LINK),
        "declared_len": u(OFF_LEN),
        "declared_crc": u(OFF_CRC),
        "sp": u(OFF_SP),
        "reset": u(OFF_RESET),
        "payload_len": len(payload),
        "actual_crc": bootloader_crc32(payload),
        "transport_crc16": transport_crc16(data[4:]),
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def cmd_verify(args) -> int:
    data = open(args.image, "rb").read()
    try:
        info = parse(data)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    len_ok = info["declared_len"] == info["payload_len"]
    crc_ok = info["declared_crc"] == info["actual_crc"]
    sp_ok = 0x10000000 <= info["sp"] < 0x10100000
    reset_ok = info["link"] <= (info["reset"] & ~1) < info["link"] + info["payload_len"]

    print(f"file            {args.image}")
    print(f"  size          {info['size']} bytes")
    print(f"  sha256        {info['sha256']}")
    print(f"  OTA dest      0x{info['dest']:08X}")
    print(f"  link address  0x{info['link']:08X}")
    print(f"  payload       {info['payload_len']} bytes")
    print(f"  length field  {info['declared_len']}  "
          f"[{'ok' if len_ok else 'MISMATCH'}]")
    print(f"  image CRC     0x{info['declared_crc']:08X} declared / "
          f"0x{info['actual_crc']:08X} computed  [{'ok' if crc_ok else 'MISMATCH'}]")
    print(f"  initial SP    0x{info['sp']:08X}  [{'ok' if sp_ok else 'suspect'}]")
    print(f"  reset vector  0x{info['reset']:08X}  [{'ok' if reset_ok else 'suspect'}]")
    print(f"  transport CRC-16 (sent by the phone): 0x{info['transport_crc16']:04X}")

    if len_ok and crc_ok and sp_ok and reset_ok:
        print("\nimage is self-consistent.")
        return 0
    print("\nimage is NOT self-consistent -- run `seal` after patching.")
    return 1


def cmd_seal(args) -> int:
    data = bytearray(open(args.image, "rb").read())
    try:
        before = parse(bytes(data))
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    payload = bytes(data[HEADER_LEN:])
    new_len, new_crc = len(payload), bootloader_crc32(payload)
    struct.pack_into("<I", data, OFF_LEN, new_len)
    struct.pack_into("<I", data, OFF_CRC, new_crc)

    out = args.image if args.in_place else args.output
    if not out:
        print("error: pass -o OUTPUT or --in-place", file=sys.stderr)
        return 2
    with open(out, "wb") as fh:
        fh.write(data)

    print(f"length  {before['declared_len']} -> {new_len}")
    print(f"CRC     0x{before['declared_crc']:08X} -> 0x{new_crc:08X}")
    print(f"transport CRC-16 is now 0x{transport_crc16(bytes(data[4:])):04X}")
    print(f"wrote {out}")
    if before["link"] != struct.unpack_from("<I", data, OFF_LINK)[0]:
        print("note: link address changed -- that is almost certainly a mistake")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    v = sub.add_parser("verify", help="parse the header and check both checksums")
    v.add_argument("image")
    v.set_defaults(func=cmd_verify)

    s = sub.add_parser("seal", help="recompute the length and CRC after patching")
    s.add_argument("image")
    s.add_argument("-o", "--output")
    s.add_argument("--in-place", action="store_true")
    s.set_defaults(func=cmd_seal)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
