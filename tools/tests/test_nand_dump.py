"""The NAND dump client: what it reads back, and what it does when reads go wrong (#69).

`nanddump` drives the 0xEE read #68's patch adds, which answers 128 bytes at a
time and loses roughly one read in five to the watch's own drawing. Most of this
runs against a fake watch, because the cases worth pinning are the awkward ones --
a lost read, a page of real zeros, a link that dies mid-page, a dump resumed --
and a fake can be made to do them on demand, every time. The last test dumps from
the emulated watch itself and compares with the NAND it was given.

Run with:  uv run normtest nand_dump [-- --full]
"""
import asyncio
import json
import random
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from normplus.firmware import nand_patch
from normplus.watch.fw import nanddump
from normplus.watch.fw.nanddump import CHUNK, PAGE, Dump, DumpError, Dumper
from normplus.watch.fw.phone import CHECK, Deframer, frame

REPO = HERE.parents[1]
FULL = "--full" in sys.argv


class FakeWatch:
    """A watch answering 0xEE as the patched firmware does, as badly as asked.

    *loss* is the chance a read is lost to the UI, which the firmware answers
    with its zero-filled buffer (#68); *die_after* makes it stop answering, the
    way a dropped link looks from here.
    """

    def __init__(self, content: dict, *, loss: float = 0.0, die_after: int | None = None,
                 seed: int = 1, down: bool = False) -> None:
        self.content = content          # address -> bytes, sparse; elsewhere erased
        self.loss = loss
        self.die_after = die_after
        self.down = down                # the storage stack is not up: 0xFE to everything
        self.random = random.Random(seed)
        self.reads = 0
        self.page_reads = 0             # how often the NAND itself was read
        self.loaded: int | None = None  # which page the OTA buffer holds
        self.buffer = b"\x00" * nanddump.PAGE
        self.asked: list[tuple[int, int]] = []
        self.tags: list[int] = []       # the tag each read came in with
        self._replies: list = []

    def byte(self, address: int) -> int:
        for start, data in self.content.items():
            if start <= address < start + len(data):
                return data[address - start]
        return 0xFF                     # erased NAND

    def clear(self) -> None:
        self._replies.clear()

    async def send(self, data: bytes, *, char: str = "8001", trigger: bool = True) -> None:
        assert data[0] == 0x6F and data[1] == 0xEE and data[2] == CHECK, data.hex(" ")
        payload = data[5:-1]
        address = int.from_bytes(payload[:4], "big")
        tag = address >> 28
        assert tag in (nanddump.NAND_TAG, nanddump.BUFFER_TAG),             "an untagged address would fault the watch"
        address &= 0x0FFFFFFF
        length = payload[4]
        self.reads += 1
        self.asked.append((address, length))
        self.tags.append(tag)
        if self.die_after is not None and self.reads > self.die_after:
            return                      # nothing comes back, ever again
        page, offset = address - address % nanddump.PAGE, address % nanddump.PAGE
        if self.down:
            answer = bytes([nanddump.DRIVER_DOWN])      # one byte: the stack is not up
        elif offset + length > nanddump.PAGE:
            answer = bytes([nanddump.CROSSES_PAGE])     # one page is all it holds
        elif self.random.random() < self.loss:
            answer = b"\x06"            # one byte: the driver's own error code
        else:
            if tag == nanddump.NAND_TAG:
                # As the driver does: a whole PAGE READ, however little was asked for.
                self.page_reads += 1
                self.loaded = page
                self.buffer = bytes(self.byte(page + i) for i in range(nanddump.PAGE))
            answer = self.buffer[offset:offset + length]
        reply = frame(0xEE, 0x80, answer)
        # Answered in 20-byte notifications, as the real link does, so the
        # deframing is exercised rather than assumed.
        self._replies = [("8002", reply[i:i + 20]) for i in range(0, len(reply), 20)]

    async def replies(self, *, timeout: float, settle: float = 0.5) -> list:
        out, self._replies = self._replies, []
        return out

    async def frames(self, *, timeout: float, count: int = 1) -> list:
        """As ``Conversation.frames``: whole frames, put back together."""
        deframer, out = Deframer(), []
        for _, value in await self.replies(timeout=timeout):
            out += deframer.feed(value)
        return out


def quiet(*_a, **_k):
    pass


def work() -> Path:
    return Path(tempfile.mkdtemp(prefix="nand-dump-"))


def run(coro):
    return asyncio.run(coro)


# -- the request itself --------------------------------------------------------

def test_the_payload_is_tagged_and_refuses_what_would_fault_the_watch():
    assert nanddump.chunk_payload(0x026DA430, 0x80) == bytes.fromhex("f26da430") + b"\x80"
    assert nanddump.chunk_payload(0, 1) == bytes.fromhex("f0000000") + b"\x01"
    for address, length in ((nanddump.NAND_SIZE, 0x80), (-1, 0x80), (0, 0), (0, 0x81)):
        try:
            nanddump.chunk_payload(address, length)
        except ValueError:
            continue
        raise AssertionError(f"built a payload for 0x{address:X}+{length}")


# -- reads that go wrong -------------------------------------------------------

def test_a_failed_read_is_retried_and_never_mistaken_for_data():
    # The patch answers a failed read with one byte -- the driver's error code --
    # so a retry is never a guess about what the bytes mean (#70).
    data = bytes(range(256)) * 8
    watch = FakeWatch({0x1000: data}, loss=0.5, seed=7)
    dumper = Dumper(tries=8, log=quiet)
    got = run(dumper.read_chunk(watch, 0x1000))
    assert got == data[:CHUNK]
    assert dumper.stats.lost == watch.reads - 1, (dumper.stats.lost, watch.reads)
    assert dumper.stats.errors == {0x06: watch.reads - 1}, dumper.stats.errors


def test_a_page_of_real_zeros_costs_one_read_each():
    # The whole point of the reply-length signal: zeros that arrive at full length
    # ARE zeros. The first version asked again up to twelve times to find that out,
    # and a resource image is full of zeros (#69, #70).
    watch = FakeWatch({0x2000: bytes(PAGE)})
    dumper = Dumper(log=quiet)
    got = run(dumper.read_chunk(watch, 0x2000))
    assert got == bytes(CHUNK)
    assert watch.reads == 1, watch.reads
    assert dumper.stats.lost == 0 and not dumper.stats.errors


def test_the_watch_saying_its_stack_is_down_is_its_own_answer():
    # 0xFE is the patch's own code, not one of the driver's: the dump waits for the
    # stack instead of recording anything, and cannot confuse it with zero data.
    watch = FakeWatch({0x3000: bytes(PAGE)}, down=True)
    dumper = Dumper(tries=2, log=quiet)
    try:
        run(dumper.read_chunk(watch, 0x3000))
    except nanddump.DriverDown:
        assert dumper.stats.driver_down == 2, dumper.stats.driver_down
        assert not dumper.stats.errors, "0xFE was counted as a driver error"
        return
    raise AssertionError("a watch with its storage down produced an answer")


def test_a_dump_waits_for_the_stack_and_gives_up_in_the_end():
    start = 0x04000000
    directory = work()
    dumper = Dumper(tries=1, wait_for_driver=0, log=quiet)
    try:
        run(dumper.run(FakeWatch({}, down=True), [(start, start + PAGE)],
                       Dump(directory, log=quiet)))
    except DumpError as exc:
        assert "stayed down" in str(exc), exc
        assert dumper.stats.waits == 1
        return
    raise AssertionError("the dump did not give up")


def test_a_frame_that_is_not_the_answer_is_not_mistaken_for_one():
    """The physical watch sends frames of its own; the emulated one does not (#70).

    The first version of the client took whatever frame arrived first, so a
    1-byte reply became a 1-byte chunk and a page 127 bytes short -- caught only
    because the dump file's own bookkeeping refused it. Now only a 0xEE
    CHECK_RESPONSE of exactly the length asked for counts.
    """
    data = bytes(range(256)) * 8

    class Chatty(FakeWatch):
        async def send(self, frame_bytes, *, char="8001", trigger=True):
            await super().send(frame_bytes, char=char, trigger=trigger)
            # The generic acknowledgement, ahead of the real answer.
            stray = frame(0x01, 0x81, bytes([0xEE, 0x00]))
            self._replies = ([("8002", stray)] + self._replies)

    watch = Chatty({0x7000: data})
    dumper = Dumper(log=quiet)
    got = run(dumper.read_chunk(watch, 0x7000))
    assert got == data[:CHUNK], (got or b"")[:16].hex(" ")
    assert dumper.stats.strays == 0, "a stray was counted even though the answer came"

    # ...and a watch that sends only strays is a watch that did not answer.
    class Useless(FakeWatch):
        async def send(self, frame_bytes, *, char="8001", trigger=True):
            self._replies = [("8002", frame(0x01, 0x81, bytes([0xEE, 0x00])))]
            self.reads += 1

    only = Dumper(tries=2, log=quiet)
    assert run(only.read_chunk(Useless({0x7000: data}), 0x7000)) is None
    assert only.stats.strays >= 2 and only.stats.silent == 2


def test_a_watch_that_stops_answering_fails_the_page_rather_than_inventing_one():
    watch = FakeWatch({0x3000: b"\x5a" * PAGE}, die_after=2)
    dumper = Dumper(tries=3, log=quiet)
    try:
        run(dumper.read_page(watch, 0x3000))
    except DumpError:
        assert dumper.stats.silent == 3, dumper.stats.silent
        return
    raise AssertionError("a dead watch produced a page")


# -- pages ---------------------------------------------------------------------

def test_an_erased_page_is_skipped_after_two_samples():
    watch = FakeWatch({})                       # all 0xFF
    dumper = Dumper(log=quiet)
    data, blank = run(dumper.read_page(watch, 0x4000))
    assert data == b"\xff" * PAGE and blank
    assert watch.reads == 2, watch.reads        # the first chunk and the last
    assert [a for a, _ in watch.asked] == [0x4000, 0x4000 + PAGE - CHUNK]
    assert dumper.stats.blank_pages == 1 and dumper.stats.pages == 0


def test_a_page_with_data_is_read_in_full_even_if_it_starts_erased():
    content = b"\xff" * CHUNK + b"\x11" * (PAGE - CHUNK)
    watch = FakeWatch({0x5000: content})
    dumper = Dumper(log=quiet)
    data, blank = run(dumper.read_page(watch, 0x5000))
    assert not blank and data == content
    assert watch.reads == PAGE // CHUNK + 1     # the last-chunk sample, then all 16


def test_no_skip_reads_every_chunk():
    watch = FakeWatch({})
    dumper = Dumper(skip_blank=False, log=quiet)
    data, blank = run(dumper.read_page(watch, 0x6000))
    assert data == b"\xff" * PAGE and not blank
    assert watch.reads == PAGE // CHUNK


def test_a_page_costs_one_page_read_not_sixteen():
    """The whole point of the page buffer (#70).

    The driver issues a full PAGE READ however few bytes are asked for, so the
    sixteen chunks of a page used to read that page sixteen times. Only the first
    chunk is asked for with the NAND tag now; the fifteen after it are slices of
    the page that read left behind, and they touch no NAND at all.
    """
    start = 0x0C780000
    content = bytes(random.Random(11).randbytes(PAGE))
    watch = FakeWatch({start: content})
    dumper = Dumper(log=quiet)
    data, blank = run(dumper.read_page(watch, start))
    assert data == content and not blank
    assert watch.reads == PAGE // CHUNK, watch.reads
    assert watch.page_reads == 1, watch.page_reads
    # ...and the tags are what did it: the NAND once, then fifteen slices.
    assert watch.tags == [nanddump.NAND_TAG] + [nanddump.BUFFER_TAG] * 15, watch.tags


def test_a_blank_page_is_sampled_without_reading_the_nand_twice():
    watch = FakeWatch({})
    dumper = Dumper(log=quiet)
    data, blank = run(dumper.read_page(watch, 0x02000000))
    assert blank and data == b"\xff" * PAGE
    # Both samples are in the same page, so the far one is a slice, not a read.
    assert watch.reads == 2 and watch.page_reads == 1, (watch.reads, watch.page_reads)


def test_the_client_and_the_patch_agree_on_every_number_between_them():
    """Two packages, one protocol: a drift here is a dump of wrong bytes.

    The patch decides these -- they are in its instructions -- and the client has
    to use the same ones. Nothing in a dump would look wrong if they parted: a
    0xE read against a patch that does not know that tag would be answered from a
    CPU address instead, and the file would fill up with plausible rubbish.
    """
    assert nanddump.NAND_TAG == nand_patch.NAND_TAG
    assert nanddump.BUFFER_TAG == nand_patch.BUFFER_TAG
    assert nanddump.DRIVER_DOWN == nand_patch.DRIVER_DOWN_CODE
    assert nanddump.CROSSES_PAGE == nand_patch.CROSSES_PAGE_CODE
    assert nanddump.PAGE == nand_patch.PAGE_SIZE
    # ...and a chunk has to fit in the handler's own destination buffer.
    assert CHUNK <= 0x80


def test_a_slice_across_a_page_boundary_is_refused_before_it_is_sent():
    watch = FakeWatch({})
    dumper = Dumper(log=quiet)
    try:
        nanddump.chunk_payload(0x1FC0, CHUNK, nanddump.BUFFER_TAG)
    except ValueError as exc:
        assert "crosses" in str(exc), exc
    else:
        raise AssertionError("a slice over the end of a page should be refused")
    assert watch.reads == 0


# -- the dump file -------------------------------------------------------------

def test_the_dump_is_a_blob_the_emulator_can_mount():
    start = 0x0C780000
    content = bytes(random.Random(3).randbytes(4 * PAGE))
    watch = FakeWatch({start: content})
    directory = work()
    dump = Dump(directory, log=quiet)
    dumper = Dumper(log=quiet)
    run(dumper.run(watch, [(start, start + 4 * PAGE)], dump))

    blob = dump.file(start).read_bytes()
    # The companion app's own resource-blob layout: the address, then the bytes.
    assert int.from_bytes(blob[:4], "little") == start
    assert blob[4:] == content
    assert dump.blobs() == [dump.file(start)]
    manifest = json.loads((directory / "manifest.json").read_text())
    record = manifest["ranges"][f"0x{start:08X}"]
    assert record["done"] == 4 * PAGE and record["blank"] == []
    assert manifest["stats"]["covered"] == 4 * PAGE


def test_the_emulator_mounts_a_dump_as_its_nand():
    # The point of the whole exercise (#65): what comes back has to go into the
    # emulated NAND, at the address it came from, through the same loader the
    # app's own resource image uses.
    from normplus.watch.__main__ import mount_blobs
    from normplus.watch.fw.devices import SpiNand

    start = 0x0288B517 & ~(PAGE - 1)          # where the boot animation's frames live
    content = bytes(random.Random(6).randbytes(2 * PAGE))
    directory = work()
    dump = Dump(directory, log=quiet)
    run(Dumper(log=quiet).run(FakeWatch({start: content}),
                              [(start, start + 2 * PAGE)], dump))

    nand = SpiNand(log=quiet)
    for blob in mount_blobs([str(directory)]):
        data = blob.read_bytes()
        nand.load(data[4:], int.from_bytes(data[:4], "little"))
    first = start // PAGE
    assert bytes(nand.pages[first][:PAGE]) == content[:PAGE]
    assert bytes(nand.pages[first + 1][:PAGE]) == content[PAGE:]
    # ...and naming the file itself works the same way.
    other = SpiNand(log=quiet)
    blob = dump.file(start).read_bytes()
    other.load(blob[4:], int.from_bytes(blob[:4], "little"))
    assert bytes(other.pages[first][:PAGE]) == content[:PAGE]


def test_a_dump_that_stops_goes_on_where_it_left_off():
    start = 0x08CBF981 & ~(PAGE - 1)
    content = bytes(random.Random(4).randbytes(8 * PAGE))
    directory = work()
    ranges = [(start, start + 8 * PAGE)]

    first = Dumper(log=quiet)
    run(first.run(FakeWatch({start: content}), ranges, Dump(directory, log=quiet), pages=3))
    held = Dump(directory, log=quiet).file(start).read_bytes()
    assert len(held) - 4 == 3 * PAGE, len(held) - 4

    # A second dump over the same directory asks only for what is missing.
    watch = FakeWatch({start: content})
    second = Dumper(log=quiet)
    run(second.run(watch, ranges, Dump(directory, log=quiet)))
    assert min(a for a, _ in watch.asked) >= start + 3 * PAGE, "it read what it already had"
    assert Dump(directory, log=quiet).file(start).read_bytes()[4:] == content
    assert second.stats.covered == 5 * PAGE


def test_a_dump_killed_mid_page_loses_only_that_page():
    start, pages = 0x00100000, 6
    content = bytes(random.Random(5).randbytes(pages * PAGE))
    directory = work()
    ranges = [(start, start + pages * PAGE)]
    # Dies in the fifth page: four are on disk, the fifth is not.
    watch = FakeWatch({start: content}, die_after=4 * (PAGE // CHUNK + 1) + 3)
    try:
        run(Dumper(tries=2, log=quiet).run(watch, ranges, Dump(directory, log=quiet)))
    except DumpError:
        pass
    else:
        raise AssertionError("the dying watch did not fail the dump")
    record = json.loads((directory / "manifest.json").read_text())["ranges"][f"0x{start:08X}"]
    assert record["done"] == 4 * PAGE, record["done"]

    rest = Dumper(log=quiet)
    run(rest.run(FakeWatch({start: content}), ranges, Dump(directory, log=quiet)))
    assert Dump(directory, log=quiet).file(start).read_bytes()[4:] == content


def test_the_blank_pages_are_recorded_and_cost_two_reads_each():
    start, pages = 0x02000000, 6
    # Data in the first and last page, erased in between.
    content = b"\x77" * PAGE + b"\xff" * (4 * PAGE) + b"\x88" * PAGE
    directory = work()
    watch = FakeWatch({start: content})
    dumper = Dumper(log=quiet)
    run(dumper.run(watch, [(start, start + pages * PAGE)], Dump(directory, log=quiet)))
    assert Dump(directory, log=quiet).file(start).read_bytes()[4:] == content
    record = json.loads((directory / "manifest.json").read_text())["ranges"][f"0x{start:08X}"]
    assert record["blank"] == [PAGE * i for i in range(1, 5)], record["blank"]
    assert dumper.stats.blank_pages == 4 and dumper.stats.pages == 2
    # 16 reads for each full page -- the sampled first chunk is part of the page,
    # not an extra -- and 2 for each blank one.
    assert watch.reads == 2 * (PAGE // CHUNK) + 4 * 2, watch.reads
    # One PAGE READ per page, blank ones included: that is the 2026-10-03 change.
    assert watch.page_reads == pages, watch.page_reads


def test_it_refuses_ranges_that_are_not_page_aligned_and_a_changed_range():
    directory = work()
    for start, end in ((0x1001, 0x2000), (0x1000, 0x2001)):
        try:
            run(Dumper(log=quiet).run(FakeWatch({}), [(start, end)], Dump(directory, log=quiet)))
        except DumpError:
            continue
        raise AssertionError(f"accepted 0x{start:X}-0x{end:X}")
    run(Dumper(log=quiet).run(FakeWatch({}), [(0x4000, 0x4000 + PAGE)], Dump(directory, log=quiet)))
    try:
        run(Dumper(log=quiet).run(FakeWatch({}), [(0x4000, 0x4000 + 2 * PAGE)],
                                  Dump(directory, log=quiet)))
    except DumpError:
        return
    raise AssertionError("accepted a range that grew under an existing dump")


# -- and against the patched firmware ------------------------------------------

def test_it_dumps_the_emulated_watch_byte_for_byte():
    """The real thing: the resource partition, read out of a patched watch.

    Needs the patched image and a bound watch, which ``test_nand_patch`` builds;
    only in --full, because it is a whole emulator session.
    """
    if not FULL:
        return
    import test_nand_patch as patch

    r = patch.session()                      # the patched, bound watch's flash state
    assert "error" not in r, r["error"]
    blob = patch.BLOB
    start = int.from_bytes(blob[:4], "little")
    content = blob[4:]
    pages = 3
    directory = work()
    result = {}

    watch = patch.EmulatedWatch(patch.PATCHED, patch.RESOURCES, flash_state=patch.BOUND)
    watch.start()

    async def flow(phone):
        assert await phone.find()
        await phone.connect()
        await phone.pair()
        await phone.discover()
        await phone.listen()
        dumper = Dumper(log=print)
        result["stats"] = await dumper.run(
            phone, [(start, start + pages * PAGE)], Dump(directory, log=print))
        await phone.disconnect()

    try:
        watch.drive(flow, timeout=600)
    finally:
        watch.stop()

    got = Dump(directory, log=quiet).file(start).read_bytes()[4:]
    assert got == content[:pages * PAGE], "the dump is not what the NAND holds"
    stats = result["stats"]
    print(f"  dump: {stats.summary()}")
    print(f"  40 MB would take {stats.estimate(40 << 20) / 3600:.1f} h, "
          f"166 MB {stats.estimate(166 << 20) / 3600:.1f} h at this rate")
    assert stats.covered == pages * PAGE


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
