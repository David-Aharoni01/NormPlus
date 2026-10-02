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
#: spans two NAND pages is checked against bytes that are not all zero.
ACROSS = 391168 - 64
MEMORY_READ = 0xEE
FULL = "--full" in sys.argv

#: Built once per run, outside the repository: a patched image is vendor-derived.
_WORK = Path(tempfile.mkdtemp(prefix="nand-patch-"))
PATCHED = _WORK / "patched.bin"
BOUND = _WORK / "patched-bound.zip"


def tagged(address: int, length: int) -> bytes:
    """The 0xEE payload for a NAND read: the address big-endian, tagged, then the length."""
    return (address | nand_patch.NAND_TAG << 28).to_bytes(4, "big") + bytes([length])


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
        """...and again if it comes back zero-filled, as a dump client must.

        The watch's own UI reads the NAND continuously to draw itself, and the
        driver's lock is taken with a short timeout (0x00031B40 from 0x0003E348),
        so a read loses that race fairly often -- about one in five while the face
        is up. It is never a partial answer: the handler zero-fills its buffer
        first, so a lost race arrives as 128 zero bytes and nothing else does.
        """
        for attempt in range(1, tries + 1):
            got = await read(payload)
            if got is None or any(got):
                if attempts is not None:
                    attempts.append(attempt)
                return got
        if attempts is not None:
            attempts.append(0)       # 0: it was zero-filled every time
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


def test_only_the_hook_changed_and_the_routine_was_appended():
    stock, image = STOCK.read_bytes(), patched()
    assert len(image) - len(stock) == 40, len(image) - len(stock)
    at = nand_patch.PAYLOAD_OFFSET + (nand_patch.HOOK_ADDRESS - nand_patch.LINK_ADDRESS)
    # Byte for byte the stock image, but for the 4-byte call and the header's own
    # length and CRC fields.
    header = {image_tool.OFF_LEN, image_tool.OFF_LEN + 1, image_tool.OFF_LEN + 2,
              image_tool.OFF_LEN + 3, image_tool.OFF_CRC, image_tool.OFF_CRC + 1,
              image_tool.OFF_CRC + 2, image_tool.OFF_CRC + 3}
    differ = [i for i in range(len(stock))
              if stock[i] != image[i] and i not in header and not at <= i < at + 4]
    assert not differ, [hex(i) for i in differ[:8]]


def test_the_routine_assembles_to_what_it_is_written_as():
    # build_routine's own check, run here so a bad hand-assembly fails the suite
    # and not only the builder.
    at = (nand_patch.LINK_ADDRESS + image_tool.parse(STOCK.read_bytes())["payload_len"] + 3) & ~3
    blob, sources = nand_patch.build_routine(at)
    nand_patch.check_routine(blob, at, sources)
    lines = nand_patch.disassemble(blob[:-4], at)
    assert lines[0].endswith("lsrs r3, r1, #0x1c"), lines[0]
    assert lines[3].endswith(f"b.w #0x{nand_patch.MEMCPY:x}"), lines[3]
    assert int.from_bytes(blob[-4:], "little") == nand_patch.DEVICE_POINTER


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
        # A read spanning two NAND pages: 64 bytes short of a page boundary, 128 long.
        result["across"] = await read(tagged(PARTITION + ACROSS, 0x80))
        result["missing"] = await read(tagged(MISSING, 0x20))
        result["short"] = await read(tagged(PARTITION, 8))
        result["cpu"] = await read(bytes.fromhex("00020000") + bytes([0x10]))
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


def test_a_read_spanning_two_nand_pages_returns_all_of_it():
    # The driver clamps each transfer to the end of the page it is in
    # (0x0003E39C-0x0003E3BC) and comes back for the rest, so the caller does not
    # have to align anything -- worth pinning, because a dump client that had to
    # align would be a different client (#69).
    r = session()
    assert r["across"] == CONTENT[ACROSS:ACROSS + 0x80], (r["across"] or b"").hex(" ")


def test_a_read_that_loses_the_nand_to_the_ui_is_zero_filled_and_retryable():
    # Not a defect of the patch but the fact a dump client is built around: the
    # driver's lock has a short timeout, the UI holds it to draw, and a read that
    # loses comes back as the handler's zeroed buffer -- all zeros, never
    # partial. Every read above was answered, and within four tries.
    r = session()
    assert r["attempts"], "no reads were recorded"
    assert 0 not in r["attempts"], f"a read was zero-filled four times over: {r['attempts']}"
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
