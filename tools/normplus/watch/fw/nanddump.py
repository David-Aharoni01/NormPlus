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
* **A failed read says so, in the reply's length.** The patch answers a read it
  could not do with **one byte**: the driver's own error code, or 0xFE for "the
  storage stack is not up" (:data:`DRIVER_DOWN`). A read that worked answers
  exactly the length asked for. So a chunk of zeros that arrives at full length
  *is* zeros and costs one read -- where the first version of this had to tell
  zero data from a lost read by asking again up to twelve times, which was most
  of a dump (#69, #70).
* **Only a tagged address, and the watch cannot be wedged by one.** An untagged
  NAND address goes to the stock ``memcpy``, which faults and takes the watch out;
  :func:`chunk_payload` refuses to build one. A tagged one is safe whatever state
  the driver is in, because the patch checks the lock before it calls anything.
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

from .phone import CHECK, CHECK_RESPONSE, SET, frame

#: The most the patched handler will return in one reply (#68).
CHUNK = 0x80
#: The NAND's page size, which is what blank-skipping works in.
PAGE = 2048
#: The top nibble that tells the patched 0xEE handler "this is a NAND offset":
#: read the page this address is in, then answer the slice asked for.
NAND_TAG = 0xF
#: The other tag: "answer a slice of the page you already hold", which touches no
#: NAND at all. The driver does a full PAGE READ on every call, so asking for the
#: sixteen chunks of a page with :data:`NAND_TAG` reads that page sixteen times;
#: anchoring the page once and slicing it fifteen times reads it once (#70). The
#: page lives in the OTA's assembly buffer, whose only other writers are the OTA
#: handlers, so nothing can move it under a dump -- and a page is re-anchored
#: every 2 KB, so even then the damage could not outlive one page.
BUFFER_TAG = 0xE
#: The command byte the patch hooks.
MEMORY_READ = 0xEE
#: The one-byte code the patch answers with when the storage stack is not up, as
#: opposed to one of the driver's own error codes (#70).
DRIVER_DOWN = 0xFE
#: And when the slice asked for would run past the end of the page it holds. Only
#: a caller asking wrongly can provoke it, so it is fatal rather than retried.
CROSSES_PAGE = 0xFD
#: What brings the storage stack back up, from here rather than by hand:
#: ``CONTROL_DEVICE`` told to show a different screen. What the stack needs is a
#: screen *change* -- the firmware redraws the face from images it already has, so
#: on the physical watch neither a backlight (subcode 0x17, LIGHT_UP_SCREEN) nor a
#: buzz (0x18) nor the charge screen brings it up, and a message-count push only
#: does while the screen is already on. Jumping to the real-time heart-rate screen
#: does, in about two seconds, because that screen's images have to be read
#: (#70). The subcodes are ``BluetoothCommandConstant.smali``'s
#: CONTROL_DEVICE_SET_JUMP_REAL_TIME_HEART_RATE and ..._EXITS_...
WAKE_COMMAND = 0x1A
WAKE_IN, WAKE_OUT = 0x03, 0x0F
#: The NAND is 256 MB, and the tag leaves exactly 28 bits for the offset.
NAND_SIZE = 1 << 28
#: The storage driver's lock, which the patch checks before it reads: NULL means
#: the stack is not up, and the reply is :data:`DRIVER_DOWN` rather than zeros.
#: With the teardown patched out as well, it stays up once something has brought it
#: up -- so this is a stall to wait through, not a reason to stop (#70).
DRIVER_LOCK = 0x1000186C

FORMAT = 1
MANIFEST = "manifest.json"


class DumpError(Exception):
    """The dump cannot go on -- the link is gone, or the watch stopped answering."""


class DriverDown(DumpError):
    """The watch says its storage stack is not up, so it cannot read the NAND.

    Not fatal and not ambiguous: the patch answers 0xFE rather than zeros (#70),
    so the dump can wait for the stack and go on, and can never mistake this for
    a page that happens to be zero.
    """


def chunk_payload(address: int, length: int, tag: int = NAND_TAG) -> bytes:
    """The 0xEE payload for a NAND read: the tagged address, big-endian, then the length.

    Refuses an address that would not be tagged, or a length the handler cannot
    answer: an untagged address faults the watch rather than failing politely.
    Also refuses a slice that crosses a page boundary, which the patch refuses too
    (:data:`CROSSES_PAGE`) -- it holds one page at a time, and the bytes after it
    are not this page's.
    """
    if not 0 <= address < NAND_SIZE:
        raise ValueError(f"0x{address:08X} is outside the NAND's 256 MB")
    if not 1 <= length <= CHUNK:
        raise ValueError(f"{length} bytes is not between 1 and {CHUNK}")
    if tag not in (NAND_TAG, BUFFER_TAG):
        raise ValueError(f"0x{tag:X} is not a tag the patch answers")
    if address % PAGE + length > PAGE:
        raise ValueError(
            f"0x{address:08X} + {length} crosses the end of its page")
    return (address | tag << 28).to_bytes(4, "big") + bytes([length])


def waker(conv):
    """``await wake()``: make the watch change screen, which brings its storage up.

    In and out by turns, because arriving at a screen the watch is already on is
    not a change and redraws nothing. Neither screen is anywhere the watch cannot
    be already, and the dump leaves it on its face.
    """
    state = {"in": False}

    async def wake() -> None:
        state["in"] = not state["in"]
        subcode = WAKE_IN if state["in"] else WAKE_OUT
        await conv.send(frame(WAKE_COMMAND, SET, bytes([subcode])))
    return wake


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
    #: Nudges sent to keep the watch awake.
    nudges: int = 0
    #: Times the storage stack was found down, and how long was spent waiting.
    waits: int = 0
    wait_seconds: float = 0.0
    #: Reads the watch reported as failed because the stack was not up.
    driver_down: int = 0
    #: The driver's own error codes, counted.
    errors: dict = field(default_factory=dict)
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
                f"{self.driver_down:,} refused (stack down)"
                + (f", errors {dict((hex(c), n) for c, n in sorted(self.errors.items()))}"
                   if self.errors else "")
                + (f"; waited {self.wait_seconds:.0f}s over {self.waits} stalls"
                   if self.waits else ""))

    def as_dict(self) -> dict:
        return {"reads": self.reads, "lost": self.lost, "silent": self.silent,
                "strays": self.strays, "waits": self.waits,
                "wait_seconds": round(self.wait_seconds, 1),
                "driver_down": self.driver_down, "nudges": self.nudges,
                "errors": {f"0x{code:02X}": n for code, n in sorted(self.errors.items())},
                "blank_pages": self.blank_pages,
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
            key, {"start": start, "end": end, "done": 0, "blank": []})
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

    def write_page(self, start: int, offset: int, data: bytes, *, blank: bool) -> None:
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
        # Every page, not every sixty-fourth: the next run trusts the manifest and
        # trims the file to it, so anything the manifest has not recorded is thrown
        # away. A session killed mid-dump used to lose up to 64 pages that way --
        # and did, which over a dump measured in hours is the difference between
        # resuming and starting again. One small JSON write a page is nothing
        # beside the ~0.5 s the page itself took.
        self.flush()

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

    #: Attempts for a read that gets no reply at all. Silence means the link is
    #: gone, so this stays small: every attempt costs :attr:`timeout` seconds and
    #: the session has to end before it can be restarted.
    tries: int = 4
    #: Attempts for a read the watch *answered* with one of the driver's own error
    #: codes. A different failure and a different budget: it means the UI held the
    #: driver's lock, an attempt costs one read rather than a timeout, and the losses
    #: arrive in runs -- 13 in 1,938 reads on the watch, four of them consecutive,
    #: which at four attempts is a session ended by one redraw (#70). The patch's own
    #: "the stack is not up" is not one of these: see :meth:`read_chunk`.
    refusals: int = 12
    #: Sample a page's ends and skip it if both are erased.
    skip_blank: bool = True
    #: Seconds to wait for one reply.
    timeout: float = 5.0
    #: Seconds to wait for the storage stack to come back before giving up.
    wait_for_driver: float = 600.0
    #: ``await wake()`` to bring the storage stack up; see :func:`waker`. Without
    #: one, a stall waits for the watch to be used by hand.
    wake: Optional[Callable] = None
    #: Seconds to leave the watch alone after each page.
    page_pause: float = 0.0
    #: Seconds before asking again for a read the watch said it could not do. The
    #: losses are the UI holding the lock for a redraw and arrive in runs, so the
    #: point is to land the retry in a different frame, not to be polite.
    retry_pause: float = 0.05
    #: Seconds between nudges that keep the watch awake. It drops the link when it
    #: decides it is idle, and reading its NAND does not count as activity: every
    #: session ended with one unanswered read and then a dropped link, after
    #: anywhere from 9 to 73 reads, while the one session that ran for two minutes
    #: was one where the watch was being handled. So :attr:`wake` goes out on a
    #: timer, not only when a read says the storage stack is down (#70).
    keep_awake: float = 10.0
    #: Write ``[03]`` to 8002 after each request, as the companion app does. The
    #: emulated watch answers 0xEE without it, which saves a write a read, but the
    #: physical one has only ever been asked with it -- so it stays on by default.
    trigger: bool = True
    log: Callable = print
    stats: Stats = field(default_factory=Stats)

    async def _once(self, conv, payload: bytes) -> Optional[bytes]:
        """One request, one answer: the reply's payload, or None if none came.

        Through ``frames``, not ``replies``: the answer is taken as soon as its
        frame is whole, because waiting half a second for a second answer that
        never comes is most of a dump.

        Only a **0xEE CHECK_RESPONSE** counts, whatever its length: the physical
        watch sends frames of its own (the generic ``6F 01 81 ...`` acknowledgement
        among them) which the emulated one never does, and taking whatever arrived
        first once turned one of those into a short chunk and a page 127 bytes short
        of a page. The length is left to :meth:`read_chunk` to read, because with
        the patch it carries the meaning: as asked for is data, one byte is a
        failure and its reason.
        """
        conv.clear()
        await conv.send(frame(MEMORY_READ, CHECK, payload), trigger=self.trigger)
        got = await conv.frames(timeout=self.timeout)
        self.stats.reads += 1
        for reply in got:
            if len(reply) >= 6 and reply[1] == MEMORY_READ and reply[2] == CHECK_RESPONSE:
                return reply[5:-1]
        if got:
            self.stats.strays += len(got)
        return None

    async def read_chunk(self, conv, address: int, length: int = CHUNK,
                         tag: int = NAND_TAG) -> Optional[bytes]:
        """One chunk, retried; None if the watch stopped answering.

        The patch reports a failed read as a **one-byte reply** holding the
        driver's own error code (0xFE being its own "the storage stack is not up"),
        and a read that worked answers exactly the length asked for (#70). So a
        chunk of zeros that comes back at full length *is* zeros, and costs one
        read -- where telling the two apart by re-reading used to cost six, which
        was most of a dump (#69).
        """
        payload = chunk_payload(address, length, tag)
        silent = failed = 0
        while True:
            got = await self._once(conv, payload)
            if got is None:
                self.stats.silent += 1
                silent += 1
                if silent >= self.tries:
                    return None
                continue
            if len(got) == length:
                return got                    # the answer, zeros included
            # One byte: the read did not happen, and this is why.
            code = got[0] if got else 0
            self.stats.lost += 1
            if code == CROSSES_PAGE:
                raise DumpError(
                    f"0x{address:08X} + {length}: the watch says that slice crosses "
                    "the end of the page it holds -- a bug here, not there")
            if code == DRIVER_DOWN:
                self.stats.driver_down += 1
            else:
                self.stats.errors[code] = self.stats.errors.get(code, 0) + 1
            failed += 1
            # The stack being down is a state, not a run of bad luck: asking again
            # does not fix it and the waker does, so that one keeps the small budget.
            if failed >= (self.tries if code == DRIVER_DOWN else self.refusals):
                if code == DRIVER_DOWN:
                    raise DriverDown(
                        f"0x{address:08X}: the watch says its storage stack is not up")
                self.log(f"  [dump] 0x{address:08X}: read error 0x{code:02X}, "
                         f"{failed} times")
                return None
            # Wait a moment before asking again. These failures are the UI holding
            # the driver's lock to draw, so they come in runs: 13 in 1,938 reads on
            # the watch, but four of them in a row, which is a whole session ended
            # by one redraw. Retrying inside the same draw asks the same question of
            # the same answer; a frame later it is a different one (#70).
            if self.retry_pause:
                await asyncio.sleep(self.retry_pause)

    async def read_page(self, conv, address: int) -> tuple[bytes, bool, int]:
        """One NAND page: its bytes, whether it was skipped as erased, zero chunks.

        The page is **anchored once** -- the first chunk is asked for with
        :data:`NAND_TAG`, which costs the one PAGE READ the driver does on every
        call, and every chunk after it with :data:`BUFFER_TAG`, which is a copy out
        of the page the watch is already holding. Sixteen chunks, one page read.
        """
        erased = b"\xff" * CHUNK
        if self.skip_blank:
            first = await self.read_chunk(conv, address)
            if first is None:
                raise DumpError(f"no answer for 0x{address:08X}")
            if first == erased:
                # The far end of the same page, so this costs no second page read.
                last = await self.read_chunk(conv, address + PAGE - CHUNK,
                                             tag=BUFFER_TAG)
                if last is None:
                    raise DumpError(f"no answer for 0x{address + PAGE - CHUNK:08X}")
                if last == erased:
                    self.stats.blank_pages += 1
                    self.stats.covered += PAGE
                    return b"\xff" * PAGE, True
            chunks = [first]
        else:
            chunks = []
        while len(chunks) * CHUNK < PAGE:
            at = address + len(chunks) * CHUNK
            # Only the first chunk of a page goes to the NAND; the rest are slices
            # of what that read left in the watch's buffer.
            got = await self.read_chunk(
                conv, at, tag=NAND_TAG if not chunks else BUFFER_TAG)
            if got is None:
                raise DumpError(f"no answer for 0x{at:08X}")
            chunks.append(got)
        page = b"".join(chunks)
        if len(page) != PAGE:
            raise DumpError(f"0x{address:08X} came back {len(page)} bytes, not {PAGE}")
        self.stats.pages += 1
        self.stats.covered += PAGE
        return page, False

    async def run(self, conv, ranges, dump: Dump, *, pages: Optional[int] = None,
                  progress_every: int = 64) -> Stats:
        """Dump *ranges* (pairs of addresses) into *dump*, continuing where it left off.

        *pages* stops after that many pages, which is how the tests exercise a
        resume without needing the link to actually drop.
        """
        started = last_nudge = time.monotonic()
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
                    # The watch itself says when its storage stack is down, so this
                    # waits for it rather than failing -- and because the firmware
                    # answers 0xFE instead of zeros, there is no race between
                    # checking and reading and no way to mistake it for data (#70).
                    waited = 0.0
                    while True:
                        try:
                            data, blank = await self.read_page(conv, at)
                            break
                        except DriverDown:
                            if waited == 0:
                                self.stats.waits += 1
                                self.log(f"  [dump] 0x{at:08X}: the storage stack is "
                                         f"down -- waiting up to {self.wait_for_driver:g}s "
                                         "for it (use the watch to bring it up)")
                            if self.wake is not None:
                                await self.wake()
                            if waited >= self.wait_for_driver:
                                self.stats.wait_seconds += waited
                                raise DumpError(
                                    f"the storage stack stayed down at 0x{at:08X} for "
                                    f"{self.wait_for_driver:g}s. Use the watch and run the "
                                    "same command again -- it goes on from here") from None
                            await asyncio.sleep(3)
                            waited += 3
                    if waited:
                        self.stats.wait_seconds += waited
                        self.log(f"  [dump] up again after {waited:g}s; going on")
                    dump.write_page(start, at - start, data, blank=blank)
                    if self.page_pause:
                        await asyncio.sleep(self.page_pause)
                    if (self.wake is not None and self.keep_awake
                            and time.monotonic() - last_nudge >= self.keep_awake):
                        await self.wake()
                        last_nudge = time.monotonic()
                        self.stats.nudges += 1
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
