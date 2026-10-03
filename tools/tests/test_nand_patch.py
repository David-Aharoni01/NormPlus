"""The NAND read-out patch: it reads the NAND, and changes nothing else (#68).

``normplus.firmware.nand_patch`` turns one instruction of the shipped image -- the
``bl memcpy`` at 0x000377CE, inside the 0xEE handler -- into a call to 40 appended
bytes that reach the firmware's own ``ew_dev_spinand.c`` read for a tagged address
and tail-call the same ``memcpy`` for everything else. That module's docstring says
why; this pins that it works and that nothing else moved:

* the hook is the instruction it claims to be, the appended routine assembles to
  the instructions it is written as, and the result re-seals and verifies;
* the patched watch boots, pairs and binds;
* ``0xFnnnnnnn`` reads the emulated NAND byte for byte -- across a page boundary,
  and at an offset the NAND has nothing at (0xFF, erased), which the stock
  firmware could not reach at all;
* a CPU address still answers exactly what the unpatched firmware answers, and an
  untagged NAND address still faults with no reply, as #66 recorded on both the
  emulated and the physical watch;
* a failed read is distinguishable: the handler zero-fills its buffer first
  (``memset`` of 0x80 at 0x000377B4), and an erased NAND page reads 0xFF, so all
  zeros means the read did not happen.

With --full it also runs both OTA rehearsals against the patched image -- the same
assertions as ``normtest ota``, via their ``--image`` / ``--bound`` arguments -- so
a resource (type 4) update and type-1 staging are checked on the patched firmware
rather than claimed.

Run with:  uv run normtest nand_patch [-- --full]
"""
import asyncio
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from normplus.firmware import image_tool, nand_patch
from normplus.watch.fw.phone import (BIND_END, BIND_START, CHECK, DATETIME, Deframer,
                                     EmulatedWatch, FIRST_SCREEN_FRAME, ack, frame)

REPO = HERE.parents[1]
STOCK = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"
BLOB = RESOURCES.read_bytes()
#: The resource partition's own address, and the bytes the emulated NAND holds there.
PARTITION, CONTENT = int.from_bytes(BLOB[:4], "little"), BLOB[4:]
#: A factory-resource offset the emulated NAND has nothing at (test_resources.py).
MISSING = 0x026DA430
#: 64 bytes before a page boundary with data either side of it -- so a read that
#: spans two NAND pages is a read the patch has to refuse rather than answer with
#: the page's tail and whatever follows its buffer.
ACROSS = 391168 - 64
#: The NAND page size, and a page of the resource partition with data in it: the
#: sixteen chunks of this page are what prove one PAGE READ serves all of them.
PAGE = 0x800
PAGE_UNDER_TEST = 0x8000
MEMORY_READ = 0xEE
FULL = "--full" in sys.argv

#: Built once per run, outside the repository: a patched image is vendor-derived.
_WORK = Path(tempfile.mkdtemp(prefix="nand-patch-"))
PATCHED = _WORK / "patched.bin"
BOUND = _WORK / "patched-bound.zip"


def tagged(address: int, length: int) -> bytes:
    """The 0xEE payload for a NAND read: the address big-endian, tagged, then the length."""
    return (address | nand_patch.NAND_TAG << 28).to_bytes(4, "big") + bytes([length])


def sliced(address: int, length: int) -> bytes:
    """The same, with the other tag: a slice of the page the watch already holds."""
    return (address | nand_patch.BUFFER_TAG << 28).to_bytes(4, "big") + bytes([length])


def appended(stock: bytes, *, guard: bool = False) -> int:
    """How many bytes ``apply`` should add: the routine, its alignment, the guard.

    Worked out rather than written down, so a change to the routine does not need
    a number here changed with it -- what the test is for is that **only** those
    bytes are appended, not how many there happen to be.
    """
    end = nand_patch.LINK_ADDRESS + image_tool.parse(stock)["payload_len"]
    at = (end + 3) & ~3                     # where apply puts the routine
    routine = nand_patch.build_routine(at)[0]
    total = (at - end) + len(routine)
    if guard:                               # ...and the guard goes right after it
        total += len(nand_patch.build_create_guard(at + len(routine))[0])
    return total


def patched() -> bytes:
    if not PATCHED.exists():
        image, _ = nand_patch.apply(STOCK.read_bytes())
        PATCHED.write_bytes(image)
    return PATCHED.read_bytes()


def reader(phone, attempts: list | None = None):
    """``await read(payload)`` -> the 0xEE reply's payload, or None if none came.

    ``replies`` hands back notification chunks, which a 128-byte answer arrives in
    several of; the frame has to be put back together before it means anything.
    """
    async def read(payload: bytes):
        phone.clear()
        await phone.send(frame(MEMORY_READ, CHECK, payload))
        deframer, frames = Deframer(), []
        for _, value in await phone.replies(timeout=5):
            frames += deframer.feed(value)
        return frames[0][5:-1] if frames else None

    async def retrying(payload: bytes, tries: int = 4):
        """...and again if the watch says the read failed, as a dump client must.

        The watch's own UI reads the NAND continuously to draw itself and the
        driver's lock is taken with a short timeout (0x00031B40 from 0x0003E348),
        so a read loses that race fairly often -- about one in five while the face
        is up. The patch answers that with a **one-byte** reply carrying the reason
        (#70), so a retry is never a guess about what the bytes mean, and an answer
        of the length asked for is data even when every byte of it is zero.
        """
        wanted = payload[4]
        for attempt in range(1, tries + 1):
            got = await read(payload)
            # CROSSES_PAGE is an answer, not a loss: retrying a slice that does not
            # fit in a page would refuse it again, so it ends here as it does in
            # the dump client.
            if got is None or len(got) == wanted or                     got == bytes([nand_patch.CROSSES_PAGE_CODE]):
                if attempts is not None:
                    attempts.append(attempt)
                return got
        if attempts is not None:
            attempts.append(0)       # 0: it reported a failure every time
        return got
    return retrying


# -- the patch itself ----------------------------------------------------------

def test_the_hook_is_the_instruction_the_patch_claims():
    stock = STOCK.read_bytes()
    at = nand_patch.PAYLOAD_OFFSET + (nand_patch.HOOK_ADDRESS - nand_patch.LINK_ADDRESS)
    assert stock[at:at + 4] == nand_patch.HOOK_EXPECTED
    # The encoder reproduces the firmware's own instruction, which is why the
    # branch it writes can be trusted.
    assert nand_patch._bl(nand_patch.HOOK_ADDRESS, nand_patch.MEMCPY) \
        == nand_patch.HOOK_EXPECTED


def test_only_the_read_hook_changes_by_default():
    # The two hooks that keep the storage stack up crashed the watch (#70), so the
    # image built by default carries the read hook and nothing else.
    stock, image = STOCK.read_bytes(), patched()
    assert len(image) - len(stock) == appended(stock), len(image) - len(stock)
    at = nand_patch.PAYLOAD_OFFSET + (nand_patch.HOOK_ADDRESS - nand_patch.LINK_ADDRESS)
    header = set(range(image_tool.OFF_LEN, image_tool.OFF_LEN + 4)) | \
        set(range(image_tool.OFF_CRC, image_tool.OFF_CRC + 4))
    differ = [i for i in range(len(stock))
              if stock[i] != image[i] and i not in header and not at <= i < at + 4]
    assert not differ, [hex(i) for i in differ[:8]]
    for address, expected in ((nand_patch.CREATE_ADDRESS, nand_patch.CREATE_EXPECTED),
                              (nand_patch.TEARDOWN_ADDRESS, nand_patch.TEARDOWN_EXPECTED)):
        o = nand_patch.PAYLOAD_OFFSET + (address - nand_patch.LINK_ADDRESS)
        assert image[o:o + 4] == expected, f"0x{address:08X} was patched"


def test_with_keep_storage_up_the_three_hooks_are_there_and_nothing_else():
    stock = STOCK.read_bytes()
    image, _ = nand_patch.apply(stock, keep_storage_up=True)
    assert len(image) - len(stock) == appended(stock, guard=True),         len(image) - len(stock)
    sites = [nand_patch.HOOK_ADDRESS, nand_patch.CREATE_ADDRESS, nand_patch.TEARDOWN_ADDRESS]
    hooked = set()
    for address in sites:
        at = nand_patch.PAYLOAD_OFFSET + (address - nand_patch.LINK_ADDRESS)
        hooked.update(range(at, at + 4))
    # Byte for byte the stock image, but for the three 4-byte hooks and the
    # header's own length and CRC fields.
    header = set(range(image_tool.OFF_LEN, image_tool.OFF_LEN + 4)) | \
        set(range(image_tool.OFF_CRC, image_tool.OFF_CRC + 4))
    differ = [i for i in range(len(stock))
              if stock[i] != image[i] and i not in header and i not in hooked]
    assert not differ, [hex(i) for i in differ[:8]]
    # ...and each hook really is what it is meant to be.
    def at_file(address):
        o = nand_patch.PAYLOAD_OFFSET + (address - nand_patch.LINK_ADDRESS)
        return image[o:o + 4]
    assert at_file(nand_patch.TEARDOWN_ADDRESS) == nand_patch.TEARDOWN_REPLACEMENT
    assert at_file(nand_patch.HOOK_ADDRESS) != nand_patch.HOOK_EXPECTED
    assert at_file(nand_patch.CREATE_ADDRESS) != nand_patch.CREATE_EXPECTED


def test_the_teardown_is_a_plain_return_and_the_guard_rejoins_the_original():
    # The two hooks that keep the storage stack up and leak-free (#70): the
    # teardown returns 0 without doing anything, and the create guard goes back to
    # the instruction after the two it replaced.
    image = patched()
    assert nand_patch.TEARDOWN_REPLACEMENT == bytes.fromhex("00204770")   # movs r0,#0; bx lr
    info = image_tool.parse(image)
    routine_at = (nand_patch.LINK_ADDRESS + info["payload_len"] - 64 + 3) & ~3
    guard_at = routine_at + len(nand_patch.build_routine(routine_at)[0])
    guard, sources, offsets = nand_patch.build_create_guard(guard_at)
    nand_patch.check_guard(guard, guard_at, sources, offsets)
    lines = nand_patch.disassemble(guard[:-4], guard_at)
    assert any(line.endswith(f"b.w #0x{nand_patch.CREATE_RESUME:x}") for line in lines), lines
    assert int.from_bytes(guard[-4:], "little") == nand_patch.DRIVER_LOCK


def test_the_routine_assembles_to_what_it_is_written_as():
    # build_routine's own check, run here so a bad hand-assembly fails the suite
    # and not only the builder.
    at = (nand_patch.LINK_ADDRESS + image_tool.parse(STOCK.read_bytes())["payload_len"] + 3) & ~3
    blob, sources, offsets = nand_patch.build_routine(at)
    nand_patch.check_routine(blob, at, sources, offsets)
    pool = len([source for source in sources if source.startswith(".")]) * 4
    lines = nand_patch.disassemble(blob[:len(blob) - pool], at)
    assert lines[0].endswith("lsrs r3, r1, #0x1c"), lines[0]
    # An untagged address is a tail call to the shipped memcpy, reached before
    # anything has been pushed: that is what makes it indistinguishable.
    tail = next(i for i, line in enumerate(lines) if "b.w" in line)
    assert lines[tail].endswith(f"b.w #0x{nand_patch.MEMCPY:x}"), lines[tail]
    assert not any("push" in line for line in lines[:tail]), lines[:tail]
    for name, value in (("lock", nand_patch.INSTANCE_LOCK),
                        ("device", nand_patch.DEVICE_POINTER),
                        ("buffer", nand_patch.PAGE_BUFFER)):
        held = int.from_bytes(blob[offsets[name]:offsets[name] + 4], "little")
        assert held == value, f"{name} is 0x{held:08X}"
    # The two things the physical watch taught us (#70), where a reader will look:
    # the instance is a literal 0, and the lock is checked before anything is called.
    assert any(line.endswith("movs r0, #0") for line in lines), lines
    calls = [i for i, line in enumerate(lines) if "blx" in line]
    assert len(calls) == 1, lines
    guards = [i for i, line in enumerate(lines) if "cbz r4," in line]
    assert len(guards) == 2 and max(guards) < calls[0], lines
    # ...and the slice path reaches the NAND not at all, which is what makes a
    # page cost one read: between `fetch` and `copy` nothing is called.
    between = nand_patch.disassemble(blob[offsets["fetch"]:offsets["copy"]],
                                     at + offsets["fetch"])
    assert not any("bl" in line or "blx" in line for line in between), between


def test_the_patched_image_verifies_and_is_sealed():
    info = image_tool.parse(patched())
    assert info["declared_len"] == info["payload_len"]
    assert info["declared_crc"] == info["actual_crc"]
    assert info["link"] == nand_patch.LINK_ADDRESS
    assert info["dest"] == 0x0FC00000
    assert info["sp"] == 0x1005FA50 and info["reset"] == 0x00020101


def test_it_refuses_an_image_it_was_not_derived_from():
    for image in (patched(), b"\x00" * 4096):
        try:
            nand_patch.apply(image)
        except ValueError:
            continue
        raise AssertionError("patched an image it should have refused")


# -- against the firmware ------------------------------------------------------

_session = None


def session() -> dict:
    """One patched watch, bound, asked several 0xEE questions."""
    global _session
    if _session is not None:
        return _session
    patched()                       # the image the watch is about to run
    watch = EmulatedWatch(PATCHED, RESOURCES,
                          flash_state=BOUND if BOUND.exists() else None)
    watch.start()
    result = {}

    async def flow(phone):
        assert await phone.find()
        await phone.connect()
        await phone.pair()
        await phone.discover()
        await phone.listen()
        init = await phone.check_init()
        if init == 0:
            assert await watch.past_boot_animation(timeout=90), "the setup screen never came up"
            bound = await phone.bind()
            result["bind"] = list(bound.values())
            for _ in range(30):
                await asyncio.sleep(0.5)
                if await phone.check_init() == 1:
                    break
        result["init"] = await phone.check_init()
        result["frames"] = watch.display.frames

        result["attempts"] = []
        read = reader(phone, result["attempts"])
        result["partition"] = await read(tagged(PARTITION, 0x80))
        # A read spanning two NAND pages: 64 bytes short of a page boundary, 128
        # long. One page is all the patch holds, so this is refused rather than
        # answered with the page's tail and whatever SRAM follows it.
        result["across"] = await read(tagged(PARTITION + ACROSS, 0x80))
        # ...and the same page asked for properly, so a refusal can be told from
        # a page the driver could not read in the first place.
        result["across_page"] = await read(
            tagged(PARTITION + ACROSS - ACROSS % PAGE, 0x80))
        # The page buffer, which is the whole point (#70): one tagged read loads a
        # page, and the fifteen slices after it must touch no NAND at all.
        # ``pages_read`` counts the UI's own reads too -- it draws from the NAND
        # constantly -- so what is counted here is PAGE READs *of this page*,
        # which is the only number the claim is about.
        nand = watch.devices["nand"]
        mine = (PARTITION + PAGE_UNDER_TEST) // nand.page_size
        rows, original = [], nand._row_to_page

        def counting(row: int) -> int:
            number = original(row)
            rows.append(number)
            return number

        nand._row_to_page = counting
        try:
            chunks = [await read(tagged(PARTITION + PAGE_UNDER_TEST, 0x80))]
            result["anchor_pages"] = rows.count(mine)
            for offset in range(0x80, PAGE, 0x80):
                chunks.append(
                    await read(sliced(PARTITION + PAGE_UNDER_TEST + offset, 0x80)))
            result["page_pages"] = rows.count(mine)
        finally:
            nand._row_to_page = original
        result["page"] = b"".join(c for c in chunks if c)
        result["page_chunks"] = sum(1 for c in chunks if c and len(c) == 0x80)
        result["missing"] = await read(tagged(MISSING, 0x20))
        result["short"] = await read(tagged(PARTITION, 8))
        result["cpu"] = await read(bytes.fromhex("00020000") + bytes([0x10]))
        # Last, because it leaves the storage stack unusable: put the watch into
        # the state a physical one rests in. On an idle watch the driver has been
        # initialised and never opened -- the lock every access takes is NULL
        # (docs/firmware.md section 11) -- and the emulated stack never goes down
        # on its own, so this is the only way to see that state here (#74).
        slot = int.from_bytes(watch.machine.uc.mem_read(nand_patch.INSTANCE_LOCK, 4),
                              "little")
        result["lock_slot"] = slot
        if slot:
            watch.machine.uc.mem_write(slot, b"\x00" * 4)
        # Through a reader that does not record attempts: this one is meant to
        # fail, and the retry statistics above are about reads that are not.
        result["stack_down"] = await reader(phone)(tagged(PARTITION, 0x80), tries=1)
        await phone.disconnect()

    try:
        watch.drive(flow, timeout=300)
    except Exception as exc:  # noqa: BLE001 - reported by the tests
        result["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        # Saved so the OTA rehearsals below start from a bound patched watch, as
        # they do from the fixture for the stock one (an OTA must not open over
        # the boot animation, #57).
        watch.stop(save_to=BOUND)
    result["machine"] = watch.machine
    _session = result
    return result


def test_the_patched_watch_boots_pairs_and_binds():
    r = session()
    assert "error" not in r, r["error"]
    if "bind" in r:
        assert r["bind"] == [ack(BIND_START), ack(DATETIME), ack(BIND_END)], \
            [(b or b"").hex(" ") for b in r["bind"]]
    assert r["init"] == 1, r["init"]
    # Past the boot animation and drawing the screens after it, not merely alive.
    assert r["frames"] > FIRST_SCREEN_FRAME, r["frames"]
    assert not r["machine"].faults, list(r["machine"].faults)


def test_a_tagged_address_reads_the_nand_byte_for_byte():
    r = session()
    assert r["partition"] == CONTENT[:0x80], (r["partition"] or b"").hex(" ")
    assert r["short"] == CONTENT[:8], (r["short"] or b"").hex(" ")


def test_a_read_spanning_two_nand_pages_is_refused_rather_than_half_invented():
    # The driver would clamp each transfer to the end of its page and come back for
    # the rest (0x0003E39C-0x0003E3BC), so until 2026-10-03 this read was answered
    # in full. It is not any more, and deliberately: the patch now loads one page
    # into the OTA's buffer and answers slices of it, which made a page cost one
    # PAGE READ instead of sixteen (#70) but leaves nothing valid past the end of
    # that page. The bytes there would be the next SRAM variable along, and bytes
    # that look like data but are not are worse than a refusal, so the routine
    # checks the slice fits and answers CROSSES_PAGE if it does not.
    r = session()
    page_at = ACROSS - ACROSS % PAGE
    assert r["across_page"] == CONTENT[page_at:page_at + 0x80],         f"the page itself did not read: {(r['across_page'] or b'').hex(' ')}"
    assert r["across"] == bytes([nand_patch.CROSSES_PAGE_CODE]), (r["across"] or b"").hex(" ")


def test_a_page_is_sixteen_chunks_and_one_page_read():
    # The whole reason the patch was changed on 2026-10-03: every call into the
    # driver issues a full PAGE READ (0x0003E416), so asking for all sixteen chunks
    # of a page with the NAND tag read that page sixteen times -- 0.28s a chunk on
    # hardware, days for the factory resources (#70, #65). One tagged read now
    # loads the page and fifteen BUFFER_TAG slices come out of it.
    r = session()
    assert r["page_chunks"] == PAGE // 0x80, r["page_chunks"]
    assert r["page"] == CONTENT[PAGE_UNDER_TEST:PAGE_UNDER_TEST + PAGE],         f"the page came back {len(r['page'] or b'')} bytes"
    assert r["anchor_pages"] == 1, r["anchor_pages"]
    assert r["page_pages"] == 1, f"{r['page_pages']} page reads for one page"


def test_a_read_with_the_storage_stack_down_answers_0xFE_rather_than_wedging():
    """The emulator's storage stack never goes down; the real watch's usually is,
    and that difference wedged the watch (#74).

    On an idle physical watch the NAND driver has been initialised and never
    opened: instance state 0, the lock NULL, and the device object's first word
    0xFF. The first version of this patch passed that first word as the driver's
    instance argument -- 0 here, 0xFF there -- so on hardware it hit
    ``assert(instance < 1)``, and every assert in this firmware ends in an
    infinite loop. The 0x6F channel was dead until the watch was restarted by
    hand, and the emulator had been green throughout.

    It cannot reach that state by itself, so the session puts it there: the lock
    is NULLed as a teardown leaves it, and the read must come back as the one
    byte that says so instead of taking a lock that is not there.
    """
    r = session()
    assert r.get("lock_slot"), "the emulated stack was not up to begin with"
    assert r["stack_down"] == bytes([nand_patch.DRIVER_DOWN_CODE]),         f"a read with the lock gone answered {(r['stack_down'] or b'').hex(' ')}"


def test_a_read_that_loses_the_nand_to_the_ui_says_so_and_is_retryable():
    # Not a defect of the patch but the fact a dump client is built around: the
    # driver's lock has a short timeout and the UI holds it to draw, so a read
    # loses that race fairly often. What the patch adds is that losing says so --
    # a one-byte reply with the reason instead of a buffer of zeros that cannot be
    # told from zero data (#70). Every read above was answered within four tries.
    r = session()
    assert r["attempts"], "no reads were recorded"
    assert 0 not in r["attempts"], f"a read failed four times over: {r['attempts']}"
    assert max(r["attempts"]) <= 4, r["attempts"]


def test_an_offset_the_nand_has_nothing_at_reads_erased():
    # The stock firmware could not read this address at all (#66); that it answers
    # 0xFF rather than 0x00 is also what tells a blank page from a failed read.
    r = session()
    assert r["missing"] == b"\xff" * 0x20, (r["missing"] or b"").hex(" ")


def test_a_cpu_address_answers_exactly_what_it_did_unpatched():
    # The image's own vector table at 0x00020000 -- the bytes #66 read from both
    # the emulated and the physical watch.
    r = session()
    assert r["cpu"] == bytes.fromhex("50fa0510010102001f010200b1030200"), \
        (r["cpu"] or b"").hex(" ")


_fault = None


def fault_session() -> dict:
    """A watch of its own, asked for an untagged NAND address -- which wedges it.

    Its own session because nothing works on that watch afterwards, so this
    cannot share the one above. It starts from the bound state that one saved.
    """
    global _fault
    if _fault is not None:
        return _fault
    session()                        # for PATCHED and the bound state
    watch = EmulatedWatch(PATCHED, RESOURCES, flash_state=BOUND)
    watch.start()
    result = {}

    async def flow(phone):
        assert await phone.find()
        await phone.connect()
        await phone.pair()
        await phone.discover()
        await phone.listen()
        read = reader(phone)
        result["before"] = await read(tagged(PARTITION, 8))        # the watch is answering
        result["untagged"] = await read(bytes.fromhex("0c780000") + bytes([0x10]))
        try:
            result["after"] = await read(tagged(PARTITION, 8))
        except Exception as exc:  # noqa: BLE001 - the point of the test
            result["after"] = f"{type(exc).__name__}"

    try:
        watch.drive(flow, timeout=200)
    except Exception as exc:  # noqa: BLE001
        result["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        watch.stop()
    result["faults"] = list(watch.machine.faults)
    _fault = result
    return result


def test_an_untagged_nand_address_still_faults_as_it_does_unpatched():
    # Not a regression and not fixed by the patch: 0xEE's memcpy has always
    # faulted on an address the CPU does not map, which is every NAND offset
    # (#66, on the emulated and the physical watch). The tag is what makes a NAND
    # read reachable; an untagged one goes to the same memcpy as before.
    #
    # What is new here is how bad it is: the fault takes the watch out. In the
    # emulator the CPU stops on the unmapped read inside memcpy and every later
    # GATT write times out, so a dump client (#69) must send tagged addresses
    # only -- a mistyped address is not a dropped reply, it is a dead watch.
    r = fault_session()
    assert r["before"] == CONTENT[:8], (r["before"] or b"").hex(" ")
    assert r["untagged"] is None, (r["untagged"] or b"").hex(" ")
    assert any("0x0C780000" in f for f in r["faults"]), r["faults"]
    assert r["after"] == "TimeoutError", r["after"]


# -- the OTA path, on the patched firmware -------------------------------------

def test_both_ota_rehearsals_pass_against_the_patched_image():
    if not FULL:
        return
    r = session()                      # for the bound state the rehearsals start from
    assert "error" not in r, r["error"]
    for name in ("test_ota.py", "test_ota_mcu.py"):
        run = subprocess.run([sys.executable, str(HERE / name),
                              "--image", str(PATCHED), "--bound", str(BOUND)],
                             capture_output=True, text=True, encoding="utf-8",
                             errors="replace", cwd=REPO)
        tail = (run.stdout + run.stderr).strip().splitlines()[-6:]
        assert run.returncode == 0, f"{name} failed:\n" + "\n".join(tail)


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
