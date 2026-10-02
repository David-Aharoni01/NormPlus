#!/usr/bin/env python3
"""Query the Norm OTA server for available firmware, and optionally download it.

Reverse-engineered from the original app:
  NORM/smali_classes2/cn/appscomm/iting/utils/NetworkManager.smali
    lambda$queryFirmware$0  -- POST https://api.normdenmarkupdate.com/api/firmware_version
                               Content-Type: application/json
                               body {"device_type": "s21"}
                               -> {"version": "...", "file_name": "<url>", ...}
    lambda$queryFirmware$1  -- app keeps version[0:3] and file_name
    lambda$downloadFile$2   -- plain GET on file_name, saved under the app cache dir

"s21" is hardcoded in the app, and it is NOT the Norm 2: the Norm 2 is "P03B_LEMOVT"
(WatchDeviceFactory$CurrentDeviceType.isP03B), and for it the app never sends this query --
SettingsFragment's update item calls handleApolloLocalUpdate() when isP03B(), and
queryFirmware() only for the other models.

!! IMPORTANT -- this is the TELINK channel, not the Apollo one. SettingsFragment
   assigns the result to OTAPathVersion.tePath/teVersion ("te" = Telink); the
   apolloPath/apolloVersion fields are filled by a different route entirely
   (Retrofit POST device/queryFirmwareVersion at https://normdenmark.com/, which
   answered 404 in 2026-10 -- docs/firmware.md section 8).
   As of 2026-08 it serves ONE record regardless of device_type, and that record
   is Norm 1 firmware for a Telink TC32 SoC -- 'KNLT' magic at +0x08, string
   "Norm 1#23574". It must NEVER be pushed at a Norm 2. See docs/firmware.md section 8.

DEVICE_TYPES below is seeded from the model-specific layouts/assets in the APK
(fragment_l42p_*, assets/watchface/{l42p,t51}, fragment_w007ga_*); the server
ignores the parameter, but it costs nothing to keep probing in case that changes.

Stdlib only. Usage:
    normfw query-ota
    normfw query-ota --device-type s21 l42p t51 --json
    normfw query-ota --enumerate
    normfw query-ota --download <dir>
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import ssl
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request

API_URL = "https://api.normdenmarkupdate.com/api/firmware_version"

# Where file_name points, as of the 2024-03-28 build. Azure blob containers are
# sometimes configured to allow anonymous listing, which would enumerate every
# firmware ever published rather than just the current one -- see --list-blobs.
BLOB_CONTAINER = "https://normdenmarkupdate.blob.core.windows.net/storage"

# The app only ever sends "s21" (not the Norm 2, which never asks). The rest are model
# codes that appear elsewhere in the APK and are plausible values for the same parameter;
# "p03b_lemovt" is the Norm 2's own.
DEVICE_TYPES = ["s21", "p03b_lemovt", "l42p", "l42", "t51", "w007ga", "lemovt", "leader"]

DEFAULT_TIMEOUT = 15.0
USER_AGENT = "okhttp/3.12.0"  # the app's HTTP stack; harmless, keeps us unremarkable


def query(device_type: str, timeout: float = DEFAULT_TIMEOUT,
          insecure: bool = False) -> dict:
    """POST one device_type. Returns a result dict (never raises)."""
    body = json.dumps({"device_type": device_type}).encode()
    req = urllib.request.Request(
        API_URL,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json", "User-Agent": USER_AGENT},
    )
    ctx = ssl._create_unverified_context() if insecure else None
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            raw = resp.read().decode("utf-8", "replace")
            status = resp.status
    except urllib.error.HTTPError as e:
        return {"device_type": device_type, "ok": False,
                "status": e.code, "error": f"HTTP {e.code}",
                "raw": e.read().decode("utf-8", "replace")[:500]}
    except Exception as e:  # URLError, socket.timeout, ssl errors
        return {"device_type": device_type, "ok": False,
                "status": None, "error": f"{type(e).__name__}: {e}"}

    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return {"device_type": device_type, "ok": False, "status": status,
                "error": "response was not JSON", "raw": raw[:500]}

    return {"device_type": device_type, "ok": True, "status": status,
            "version": data.get("version"), "file_name": data.get("file_name"),
            "response": data}


def list_blobs(timeout: float = DEFAULT_TIMEOUT, insecure: bool = False) -> dict:
    """Try an anonymous Azure container listing. Returns {'ok', 'blobs'|'error'}.

    Succeeds only if the container's public access level is "Container" rather
    than "Blob". If it fails the API query above is the only enumeration path.
    """
    url = f"{BLOB_CONTAINER}?restype=container&comp=list&maxresults=1000"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    ctx = ssl._create_unverified_context() if insecure else None
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            xml = resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return {"ok": False, "error": f"HTTP {e.code}",
                "raw": e.read().decode("utf-8", "replace")[:300]}
    except Exception as e:
        return {"ok": False, "error": f"{type(e).__name__}: {e}"}

    import re
    names = re.findall(r"<Name>([^<]+)</Name>", xml)
    sizes = re.findall(r"<Content-Length>(\d+)</Content-Length>", xml)
    blobs = [{"name": n, "size": int(s) if i < len(sizes) else None,
              "url": f"{BLOB_CONTAINER}/{urllib.parse.quote(n)}"}
             for i, (n, s) in enumerate(zip(names, sizes + [""] * len(names)))]
    return {"ok": True, "blobs": blobs}


def download(url: str, dest_dir: str, timeout: float = DEFAULT_TIMEOUT,
             insecure: bool = False) -> str:
    """GET url into dest_dir. Returns the written path."""
    os.makedirs(dest_dir, exist_ok=True)
    name = os.path.basename(urllib.parse.urlparse(url).path) or "firmware.bin"
    path = os.path.join(dest_dir, name)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    ctx = ssl._create_unverified_context() if insecure else None
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp, \
            open(path, "wb") as fh:
        while chunk := resp.read(65536):
            fh.write(chunk)
    return path


def describe_bin(path: str) -> list[str]:
    """Parse the 4-byte OTA address + Ambiq bootloader image header, if present.

    Layout (see docs/firmware.md):
      +0x00 dest addr (stripped by the app, sent in the OTA SET header)
      +0x04 link address   +0x0C payload length   +0x10 image CRC
      +0x1C initial SP     +0x20 reset vector      +0x30 vector table
    """
    lines = []
    with open(path, "rb") as fh:
        head = fh.read(0x40)
    size = os.path.getsize(path)
    lines.append(f"    size      {size} bytes")
    with open(path, "rb") as fh:
        lines.append(f"    sha256    {hashlib.sha256(fh.read()).hexdigest()}")
    if len(head) < 0x40:
        return lines
    w = struct.unpack_from("<16I", head)

    # Telink images carry 'KNLT' at +0x08 and their own length at +0x18. These are for
    # a different product on a different CPU -- flag loudly, do not treat as Apollo.
    if head[8:12] == b"KNLT":
        lines.append("    type      TELINK image ('KNLT' magic) -- NOT an Apollo3/Norm 2 image")
        lines.append(f"    len field {w[6]} (file size {size}; "
                     f"{'matches' if w[6] == size else 'MISMATCH'})")
        lines.append("    *** do not send this to a Norm 2 ***")
        return lines

    lines.append(f"    dest      0x{w[0]:08X}")
    # Heuristic: an Apollo application image has a plausible SP in SRAM.
    if 0x10000000 <= w[7] < 0x10100000:
        lines.append(f"    link addr 0x{w[1]:08X}")
        lines.append(f"    payload   {w[3]} bytes (0x{w[3]:X}); file-48 = {size - 48}")
        lines.append(f"    image crc 0x{w[4]:08X}")
        lines.append(f"    init SP   0x{w[7]:08X}")
        lines.append(f"    reset     0x{w[8]:08X}")
    else:
        lines.append("    (no Apollo image header -- resource/picture blob?)")
    return lines


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--device-type", "-d", nargs="+", default=["s21"],
                    help="model code(s) to query (default: s21, what the app sends)")
    ap.add_argument("--enumerate", "-e", action="store_true",
                    help=f"query the full candidate list: {' '.join(DEVICE_TYPES)}")
    ap.add_argument("--list-blobs", action="store_true",
                    help="try an anonymous listing of the Azure firmware container "
                         "(only works if it allows public container listing)")
    ap.add_argument("--download", metavar="DIR",
                    help="download each returned file_name into DIR")
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    ap.add_argument("--insecure", action="store_true",
                    help="skip TLS verification (only if the server's chain is broken)")
    args = ap.parse_args(argv)

    if args.list_blobs:
        lb = list_blobs(timeout=args.timeout, insecure=args.insecure)
        if args.json:
            json.dump(lb, sys.stdout, indent=2)
            print()
        elif lb["ok"]:
            for b in lb["blobs"]:
                print(f"{b['size'] if b['size'] is not None else '?':>10}  {b['name']}")
            print(f"({len(lb['blobs'])} blobs)")
        else:
            print(f"container listing not available -- {lb['error']}")
            print("the API query below is then the only enumeration path.")
        if not args.enumerate and args.device_type == ["s21"]:
            return 0 if lb["ok"] else 1
        print()

    types = DEVICE_TYPES if args.enumerate else args.device_type
    results = []

    for dt in types:
        r = query(dt, timeout=args.timeout, insecure=args.insecure)
        if args.download and r.get("ok") and r.get("file_name"):
            try:
                path = download(r["file_name"], args.download,
                                timeout=args.timeout, insecure=args.insecure)
                r["downloaded"] = path
            except Exception as e:
                r["download_error"] = f"{type(e).__name__}: {e}"
        results.append(r)

        if args.json:
            continue
        if not r["ok"]:
            print(f"{dt:<8} -- {r['error']}")
            if r.get("raw"):
                print(f"           {r['raw']}")
            continue
        print(f"{dt:<8} version={r['version']!r}")
        print(f"           file_name={r['file_name']!r}")
        extra = {k: v for k, v in r["response"].items()
                 if k not in ("version", "file_name")}
        if extra:
            print(f"           other={json.dumps(extra)}")
        if r.get("download_error"):
            print(f"           download failed: {r['download_error']}")
        elif r.get("downloaded"):
            print(f"           saved -> {r['downloaded']}")
            for line in describe_bin(r["downloaded"]):
                print(line)

    if args.json:
        json.dump(results, sys.stdout, indent=2)
        print()

    return 0 if any(r.get("ok") for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
