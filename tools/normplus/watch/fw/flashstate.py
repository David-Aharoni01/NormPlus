"""The watch's flash, kept between runs (``boot --flash-state PATH``).

A real watch keeps what it wrote to flash when it restarts. The emulator began
every run from the shipped image and an erased part, so every run started at
first-run setup and every phone had to pair from scratch. What the firmware
writes is small, and all of it is its own doing. Logging every
``nv_page_erase`` / ``nv_program_main`` over a boot, a bond and a bind:

=====================  ==========================================================
``0x1C000``            a full page on every boot (the boot record, see #39)
``0x18000``            at bindStart: ``04``, the LE32 epoch setDateTime sent, ``01``
``0xDE000``            at boot: the canned quick-reply texts ("Call me later.", ...)
``0xE0000-0xF7FFF``    erased by the bind (already blank on a fresh emulated watch)
``0xF8000``            written by the bind
``0xFA000``            byte 0 is ``ff`` after a boot and ``01`` once the bind is done
``0xFE000``            written the moment the link is encrypted: the bond
=====================  ==========================================================

No SPI NAND page is programmed or erased in any of that. The NAND is kept
anyway: OTA staging and the resource partition live there.

Only pages the firmware wrote are stored -- ``Apollo3Machine.flash_dirty`` and
``SpiNand.dirty`` -- so a state file is a difference from the shipped image and
resource blob, and it refuses to load over different ones: their layout is not
something it knows anything about.

The file is a zip, so ``unzip -l`` shows what is in it::

    manifest.json            format, what it was saved against, page lists
    flash/0x000FA000.bin     one 8 KB internal-flash page each
    nand/00062340.bin        one NAND page each (data + spare)
"""

from __future__ import annotations

import hashlib
import json
import os
import time
import zipfile
from pathlib import Path

from .bootrom import BootRom

FORMAT = 1
MANIFEST = "manifest.json"


class FlashStateMismatch(Exception):
    """The state was saved against a different firmware image or resource blob."""


def _image_digest(machine) -> str:
    return hashlib.sha256(machine.image.payload).hexdigest()


def save(path, machine, nand=None) -> dict:
    """Write every page the firmware has written (or that a load put back).

    Written to a temporary file and moved into place, so a run killed while
    saving leaves the previous state rather than half of a new one.
    """
    path = Path(path)
    page = BootRom.PAGE_SIZE
    flash_pages = sorted(machine.flash_dirty)
    nand_pages = sorted(nand.dirty) if nand is not None else []
    manifest = {
        "format": FORMAT,
        "image_sha256": _image_digest(machine),
        "nand_origin": [list(o) for o in nand.origin] if nand is not None else [],
        "flash_page_size": page,
        "flash_pages": [f"0x{i * page:08X}" for i in flash_pages],
        "nand_pages": nand_pages,
        "watch_seconds": round(machine.cycles / 48_000_000, 3),
        "saved": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    tmp = path.with_name(path.name + ".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(MANIFEST, json.dumps(manifest, indent=2))
        for i in flash_pages:
            z.writestr(f"flash/0x{i * page:08X}.bin", bytes(machine.uc.mem_read(i * page, page)))
        for n in nand_pages:
            data = nand.pages.get(n)
            if data is None:  # erased: no resident page reads as all 0xFF
                data = b"\xff" * (nand.page_size + nand.spare_size)
            z.writestr(f"nand/{n:08d}.bin", bytes(data))
    os.replace(tmp, path)
    return manifest


def load(path, machine, nand=None, *, log=print) -> dict:
    """Put a saved state back over a freshly built machine.

    Call it after the devices are attached (the resource blob is in the NAND)
    and before anything runs. The pages it restores count as written, so the
    next save keeps them even if this run never touches them.
    """
    path = Path(path)
    with zipfile.ZipFile(path) as z:
        manifest = json.loads(z.read(MANIFEST))
        if manifest.get("format") != FORMAT:
            raise FlashStateMismatch(
                f"{path} is flash-state format {manifest.get('format')}, "
                f"this emulator reads {FORMAT}")
        if manifest["image_sha256"] != _image_digest(machine):
            raise FlashStateMismatch(
                f"{path} was saved against a different firmware image "
                f"(sha256 {manifest['image_sha256'][:16]}..., this one is "
                f"{_image_digest(machine)[:16]}...)")
        if manifest["nand_pages"] or manifest["nand_origin"]:
            here = [list(o) for o in nand.origin] if nand is not None else []
            if manifest["nand_origin"] != here:
                raise FlashStateMismatch(
                    f"{path} was saved over a different NAND (resource blob "
                    f"{manifest['nand_origin']}, this run has {here})")

        page = manifest["flash_page_size"]
        if page != BootRom.PAGE_SIZE:
            raise FlashStateMismatch(f"{path} has {page}-byte flash pages, "
                                     f"this machine has {BootRom.PAGE_SIZE}")
        for address in manifest["flash_pages"]:
            data = z.read(f"flash/{address}.bin")
            # A raw write, like an erase: this is restoring the part, not
            # programming it, so the program-can-only-clear-bits rule does
            # not apply. persist=True marks the page for the next save.
            machine.write_flash(int(address, 16), data, erase=True)
        for n in manifest["nand_pages"]:
            data = z.read(f"nand/{n:08d}.bin")
            if data.count(0xFF) == len(data):
                nand.pages.pop(n, None)
            else:
                nand.pages[n] = bytearray(data)
            nand.dirty.add(n)
    log(f"  [flash] restored {len(manifest['flash_pages'])} flash pages and "
        f"{len(manifest['nand_pages'])} NAND pages from {path} "
        f"(saved {manifest['saved']}, {manifest['watch_seconds']}s of watch time)")
    return manifest
