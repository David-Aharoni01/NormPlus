"""Reading the watch's SPI NAND out, through the patch's 0xEE (#68, #69).

The watch's factory resources -- about 40 MB of images the emulator has nothing
for, so it draws "No data" over them (#64, #65) -- exist only in the watch's own
NAND. #66 found no way to read them with the shipped firmware; #68's patch adds
one: command 0xEE with its address tagged ``0xFnnnnnnn`` returns NAND bytes
instead of CPU bytes. This drives that command for as long as it takes.

What the patch's own limits force this to look like (all of it measured in #68,
``docs/firmware.md`` section 11):

* **128 bytes a request.** The handler's buffer is 0x80 bytes of stack and the
  reply builder refuses a frame of 255 bytes or more. Deliberately not raised:
  the image this reads from is the one flashed to the only watch there is.
* **Every chunk is checked, and retried.** The watch reads its own NAND
  continuously to draw itself and the driver's lock has a short timeout, so
  roughly one read in five is lost while the face is up. A lost read is never
  partial -- the handler zero-fills its buffer before the driver runs, so it
  arrives as 128 zero bytes and nothing else does.
* **Which leaves one ambiguity, and it is handled rather than hidden.** Data that
  really is zero looks exactly like a lost read. There is no status byte to tell
  them apart, so a zero chunk is re-read ``zero_tries`` times (12 by default:
  with a ~20% loss rate that is a 4-in-a-billion chance of recording a lost read
  as zeros) and, if it stays zero, counted in :attr:`Stats.zero_chunks` so the
  manifest says how much of the dump rests on that. Erased NAND reads 0xFF, so
  blank pages are never in doubt.
* **Tagged addresses only.** An untagged NAND address goes to the stock
  ``memcpy``, which faults and takes the watch out -- not a dropped reply, a dead
  watch. :func:`chunk_payload` refuses to build one.

Blank pages are skipped by sampling their first and last chunk: erased flash is
0xFF everywhere, and the factory programmer wrote whole pages, so two 0xFF
samples mean an erased page and 14 reads not made. ``--no-skip`` reads every
chunk instead.

The dump is written as the companion app's own resource-blob format -- four bytes
of little-endian address, then the bytes -- which is what ``SpiNand.load`` mounts,
so ``normwatch boot --nand DIR`` runs the watch on what came back. Pages that
were blank are 0xFF in the file, which is what the NAND would have given anyway.
A ``manifest.json`` beside it records what is done, so a dump that stops for any
reason continues where it left off.
"""

from __future__ import annotations

import asyncio
import json
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

from .phone import CHECK, CHECK_RESPONSE, frame

#: The most the patched handler will return in one reply (#68).
CHUNK = 0x80
#: The NAND's page size, which is what blank-skipping works in.
PAGE = 2048
#: The top nibble that tells the patched 0xEE handler "this is a NAND offset".
NAND_TAG = 0xF
#: The command byte the patch hooks.
MEMORY_READ = 0xEE
#: The NAND is 256 MB, and the tag leaves exactly 28 bits for the offset.
NAND_SIZE = 1 << 28
#: The storage driver's lock, NULL until something brings the stack up. On the
#: physical watch that happens when the watch is *used* and goes away again when it
#: idles (#70), and a read taken while it is down answers zeros -- which is exactly
#: what data that is zero looks like. So a dump checks it before every page: see
#: :func:`driver_probe`.
DRIVER_LOCK = 0x1000186C

FORMAT = 1
MANIFEST = "manifest.json"


class DumpError(Exception):
    """The dump cannot go on -- the link is gone, or the watch stopped answering."""


def chunk_payload(address: int, length: int) -> bytes:
    """The 0xEE payload for a NAND read: the tagged address, big-endian, then the length.

    Refuses an address that would not be tagged, or a length the handler cannot
    answer: an untagged address faults the watch rather than failing politely.
    """
    if not 0 <= address < NAND_SIZE:
        raise ValueError(f"0x{address:08X} is outside the NAND's 256 MB")
    if not 1 <= length <= CHUNK:
        raise ValueError(f"{length} bytes is not between 1 and {CHUNK}")
    return (address | NAND_TAG << 28).to_bytes(4, "big") + bytes([length])


def driver_probe(dumper, conv):
    """``await probe()`` -> is the storage stack up? A plain CPU read of the lock.

    Cheap (one read a page, ~6%) and the difference between a dump that can be
    trusted and one that might have recorded a stretch of zeros because the watch
    had powered its NAND down.
    """
    async def probe() -> bool:
        payload = DRIVER_LOCK.to_bytes(4, "big") + bytes([4])
        for _ in range(dumper.tries):
            got = await dumper._once(conv, payload)
            if got is not None and len(got) >= 4:
                return any(got)
        return False
    return probe


@dataclass
class Stats:
    """What the dump cost -- the numbers #69 exists to produce."""

    #: Chunks asked for, including every retry.
    reads: int = 0
    #: Reads that came back zero-filled (a lost race for the NAND).
    lost: int = 0
    #: Reads that got no reply at all.
    silent: int = 0
    #: Frames that came back but were not the answer asked for (the watch's own).
    strays: int = 0
    #: Times the storage stack was found down, and how long was spent waiting.
    waits: int = 0
    wait_seconds: float = 0.0
    #: Chunks that stayed zero for every attempt: data that is zero, almost surely.
    zero_chunks: int = 0
    #: Pages skipped as erased, and pages read in full.
    blank_pages: int = 0
    pages: int = 0
    #: Bytes of NAND covered (blank pages included; they are 0xFF in the file).
    covered: int = 0
    seconds: float = 0.0

    @property
    def bytes_per_second(self) -> float:
        return self.covered / self.seconds if self.seconds else 0.0

    @property
    def reads_per_second(self) -> float:
        return self.reads / self.seconds if self.seconds else 0.0

    @property
    def loss_rate(self) -> float:
        return self.lost / self.reads if self.reads else 0.0

    def estimate(self, size: int) -> float:
        """Seconds *size* bytes would take at the rate measured, read in full."""
        rate = self.reads_per_second
        return (size / CHUNK) / rate if rate else float("inf")

    def summary(self) -> str:
        return (f"{self.covered:,} bytes in {self.seconds:.1f}s "
                f"({self.bytes_per_second / 1024:.1f} KB/s, "
                f"{self.reads_per_second:.1f} reads/s); "
                f"{self.reads:,} reads, {self.lost:,} lost to the UI "
                f"({self.loss_rate * 100:.0f}%), {self.silent} unanswered, "
                f"{self.strays} stray frames; "
                f"{self.pages:,} pages read, {self.blank_pages:,} blank; "
                f"{self.zero_chunks:,} chunks are zero"
                + (f"; waited {self.wait_seconds:.0f}s over {self.waits} stalls"
                   if self.waits else ""))

    def as_dict(self) -> dict:
        return {"reads": self.reads, "lost": self.lost, "silent": self.silent,
                "strays": self.strays, "waits": self.waits,
                "wait_seconds": round(self.wait_seconds, 1),
                "zero_chunks": self.zero_chunks, "blank_pages": self.blank_pages,
                "pages": self.pages, "covered": self.covered,
                "seconds": round(self.seconds, 3),
                "bytes_per_second": round(self.bytes_per_second, 1)}


class Dump:
    """The files a dump is written to, and what is already in them.

    One ``0x<start>.bin`` per range, in the resource-blob format the emulator
    mounts, plus a manifest of the pages that are done. Both are rewritten as the
    dump goes, so stopping it costs at most the page in flight.
    """

    def __init__(self, directory, *, log=print) -> None:
        self.directory = Path(directory)
        self.log = log
        self.directory.mkdir(parents=True, exist_ok=True)
        self.manifest = {"format": FORMAT, "chunk": CHUNK, "page": PAGE, "ranges": {}}
        path = self.directory / MANIFEST
        if path.exists():
            loaded = json.loads(path.read_text(encoding="utf-8"))
            if loaded.get("format") != FORMAT:
                raise DumpError(f"{path} is format {loaded.get('format')}, not {FORMAT}")
            self.manifest = loaded

    def _key(self, start: int) -> str:
        return f"0x{start:08X}"

    def file(self, start: int) -> Path:
        return self.directory / f"{self._key(start)}.bin"

    def open_range(self, start: int, end: int) -> dict:
        """The record for a range, and the file it is appended to.

        A page is appended the moment it is read, so a dump that stops loses at
        most the page in flight; the manifest is what the next run trusts, and the
        file is trimmed to it in case a write was cut in half.
        """
        key = self._key(start)
        record = self.manifest["ranges"].setdefault(
            key, {"start": start, "end": end, "done": 0, "blank": [], "zero": []})
        if record["end"] != end:
            raise DumpError(f"{key} was dumped to 0x{record['end']:08X}, not 0x{end:08X}")
        path = self.file(start)
        if not path.exists():
            path.write_bytes(start.to_bytes(4, "little"))
        elif int.from_bytes(path.read_bytes()[:4], "little") != start:
            raise DumpError(f"{path} does not begin with 0x{start:08X}")
        held = path.stat().st_size - 4
        if held > record["done"]:
            with open(path, "r+b") as fh:
                fh.truncate(4 + record["done"])
        elif held < record["done"]:
            record["done"] = held
        return record

    def write_page(self, start: int, offset: int, data: bytes, *, blank: bool,
                   zero_chunks: int = 0) -> None:
        """Append one page to a range and record it. *offset* is from the range's start."""
        path = self.file(start)
        held = path.stat().st_size - 4
        if offset != held:
            raise DumpError(f"page at +0x{offset:X} does not follow 0x{held:X} bytes")
        with open(path, "ab") as fh:
            fh.write(data)
        record = self.manifest["ranges"][self._key(start)]
        record["done"] = held + len(data)
        if blank:
            record["blank"].append(offset)
        if zero_chunks:
            record["zero"].append([offset, zero_chunks])

    def flush(self, stats: Optional[Stats] = None) -> None:
        """Write the manifest. The range files are already up to date."""
        if stats is not None:
            self.manifest["stats"] = stats.as_dict()
        self.manifest["written"] = time.strftime("%Y-%m-%d %H:%M:%S")
        (self.directory / MANIFEST).write_text(
            json.dumps(self.manifest, indent=1), encoding="utf-8")

    def blobs(self) -> list[Path]:
        """The range files, for the emulator to mount."""
        return sorted(p for p in self.directory.glob("0x*.bin"))


@dataclass
class Dumper:
    """Reads NAND out through a 0x6F conversation, one 128-byte chunk at a time."""

    #: Attempts for a read that gets no reply at all.
    tries: int = 4
    #: The most attempts a zero-filled read gets; see :meth:`zero_attempts`.
    zero_tries: int = 12
    #: The chance of recording a lost read as zeros that the retries aim for.
    zero_target: float = 1e-9
    #: Sample a page's ends and skip it if both are erased.
    skip_blank: bool = True
    #: Seconds to wait for one reply.
    timeout: float = 5.0
    #: Seconds to wait for the storage stack to come back before giving up.
    wait_for_driver: float = 600.0
    #: Write ``[03]`` to 8002 after each request, as the companion app does. The
    #: emulated watch answers 0xEE without it, which saves a write a read, but the
    #: physical one has only ever been asked with it -- so it stays on by default.
    trigger: bool = True
    log: Callable = print
    stats: Stats = field(default_factory=Stats)

    def zero_attempts(self) -> int:
        """How many zero-filled answers it takes to believe the data is zero.

        Zero data and a read the UI won are the same 128 zero bytes, so the only
        defence is to ask again -- but the cost falls entirely on data that is
        genuinely zero, of which a resource image has a great deal (the watch
        face's background alone is 259,200 zero bytes). So the count follows the
        loss rate actually being seen rather than the worst one ever measured:
        enough attempts that all of them being losses has probability
        :attr:`zero_target`. At the ~20% loss of a watch busy drawing that is 13
        attempts (capped at :attr:`zero_tries`); at the ~0.3% of a watch whose
        screen has gone off, 3. Until there are enough reads to estimate from,
        the cap applies.
        """
        if self.stats.reads < 200:
            return self.zero_tries
        import math
        rate = max(self.stats.loss_rate, 0.001)
        needed = math.ceil(math.log(self.zero_target) / math.log(rate))
        return max(3, min(self.zero_tries, needed))

    async def _once(self, conv, payload: bytes) -> Optional[bytes]:
        """One request, one answer: the reply's payload, or None if none came.

        Through ``frames``, not ``replies``: the answer is taken as soon as its
        frame is whole, because waiting half a second for a second answer that
        never comes is most of a dump.

        Only a 0xEE CHECK_RESPONSE of exactly the length asked for counts. The
        physical watch sends frames of its own (the generic ``6F 01 81 ...``
        acknowledgement among them) which the emulated one never does, and the
        first version of this took whatever arrived first -- so a 1-byte reply once
        became a short chunk, and a page 127 bytes short of a page. Anything else
        is skipped, and if nothing fits, this is a read that did not happen.
        """
        wanted = payload[4]
        conv.clear()
        await conv.send(frame(MEMORY_READ, CHECK, payload), trigger=self.trigger)
        got = await conv.frames(timeout=self.timeout)
        self.stats.reads += 1
        for reply in got:
            if (len(reply) >= 6 and reply[1] == MEMORY_READ and reply[2] == CHECK_RESPONSE
                    and len(reply) - 6 == wanted):
                return reply[5:-1]
        if got:
            self.stats.strays += len(got)
        return None

    async def read_chunk(self, conv, address: int, length: int = CHUNK) -> Optional[bytes]:
        """One chunk, retried; None if the watch stopped answering.

        Zeros are returned only once they have survived :attr:`zero_tries`
        attempts, and counted as data rather than as losses -- a zero-filled reply
        is a lost race for the NAND *or* data that is really zero, and nothing in
        the protocol tells the two apart.
        """
        payload = chunk_payload(address, length)
        allowed = self.zero_attempts()
        silent = zero = 0
        zeros = None
        while True:
            got = await self._once(conv, payload)
            if got is None:
                self.stats.silent += 1
                silent += 1
                if silent >= self.tries:
                    return None
                continue
            if any(got):
                self.stats.lost += zero       # those earlier zeros were lost races
                return got
            zeros, zero = got, zero + 1
            if zero >= allowed:
                self.stats.zero_chunks += 1   # data that is zero, to zero_target
                return zeros

    async def read_page(self, conv, address: int) -> tuple[bytes, bool, int]:
        """One NAND page: its bytes, whether it was skipped as erased, zero chunks."""
        erased = b"\xff" * CHUNK
        before = self.stats.zero_chunks
        if self.skip_blank:
            first = await self.read_chunk(conv, address)
            if first is None:
                raise DumpError(f"no answer for 0x{address:08X}")
            if first == erased:
                last = await self.read_chunk(conv, address + PAGE - CHUNK)
                if last is None:
                    raise DumpError(f"no answer for 0x{address + PAGE - CHUNK:08X}")
                if last == erased:
                    self.stats.blank_pages += 1
                    self.stats.covered += PAGE
                    return b"\xff" * PAGE, True, 0
            chunks = [first]
        else:
            chunks = []
        while len(chunks) * CHUNK < PAGE:
            at = address + len(chunks) * CHUNK
            got = await self.read_chunk(conv, at)
            if got is None:
                raise DumpError(f"no answer for 0x{at:08X}")
            chunks.append(got)
        page = b"".join(chunks)
        if len(page) != PAGE:
            raise DumpError(f"0x{address:08X} came back {len(page)} bytes, not {PAGE}")
        self.stats.pages += 1
        self.stats.covered += PAGE
        return page, False, self.stats.zero_chunks - before

    async def run(self, conv, ranges, dump: Dump, *, pages: Optional[int] = None,
                  progress_every: int = 64, probe=None) -> Stats:
        """Dump *ranges* (pairs of addresses) into *dump*, continuing where it left off.

        *pages* stops after that many pages, which is how the tests exercise a
        resume without needing the link to actually drop.
        """
        started = time.monotonic()
        done_pages = 0
        # One notification an answer instead of seven, where the transport can.
        if hasattr(conv, "request_mtu"):
            try:
                mtu = await conv.request_mtu()
                self.log(f"  [dump] ATT MTU {mtu}: a 128-byte answer in one notification")
            except Exception as exc:  # noqa: BLE001 - the dump works at any MTU
                self.log(f"  [dump] the MTU stayed as it was ({exc}); this will be slower")
        try:
            for start, end in ranges:
                if start % PAGE or end % PAGE:
                    raise DumpError(f"0x{start:08X}-0x{end:08X} is not page aligned")
                record = dump.open_range(start, end)
                at = start + record["done"]
                if at > start:
                    self.log(f"  [dump] 0x{start:08X}: {record['done']:,} bytes already read, "
                             f"going on from 0x{at:08X}")
                while at < end:
                    if pages is not None and done_pages >= pages:
                        return self.stats
                    if probe is not None and not await probe():
                        # Down rather than gone: the physical watch powers its NAND
                        # down when it is not being used (#70), and brings it back
                        # when it is. Waiting beats failing, because the alternative
                        # is reading zeros that look like data.
                        self.stats.waits += 1
                        self.log(f"  [dump] 0x{at:08X}: the storage stack is down -- "
                                 f"waiting up to {self.wait_for_driver:g}s for it "
                                 f"(use the watch to bring it up)")
                        waited = 0.0
                        while waited < self.wait_for_driver:
                            await asyncio.sleep(2)
                            waited += 2
                            if await probe():
                                self.stats.wait_seconds += waited
                                self.log(f"  [dump] up again after {waited:g}s; going on")
                                break
                        else:
                            self.stats.wait_seconds += waited
                            raise DumpError(
                                f"the storage stack stayed down at 0x{at:08X} for "
                                f"{self.wait_for_driver:g}s. Use the watch (or put it on "
                                "its charger) and run the same command again -- it goes "
                                "on from here")
                    data, blank, zeros = await self.read_page(conv, at)
                    dump.write_page(start, at - start, data, blank=blank, zero_chunks=zeros)
                    at += PAGE
                    done_pages += 1
                    if done_pages % progress_every == 0:
                        self.stats.seconds = time.monotonic() - started
                        dump.flush(self.stats)
                        self.log(f"  [dump] 0x{at:08X}  {self.stats.summary()}")
        finally:
            self.stats.seconds = time.monotonic() - started
            dump.flush(self.stats)
        return self.stats
