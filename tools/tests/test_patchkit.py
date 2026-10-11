"""The patchkit: it builds a C patch into the cave and changes nothing else (#116).

``normplus.firmware.patchkit`` compiles a C patch (``patches/``) for the Apollo3,
links it into the free flash after the image, rewrites a short list of 4-byte sites
to branch into it, and re-seals. This pins the machinery against the stock image
using the kit's own ``passthrough`` patch -- a transparent detour at the 0x6F
dispatcher's fall-through (0x00039F88) that owns no command and must leave the
watch exactly as it was:

* the patch compiles, links within the cave, verifies and is sealed;
* the hook site held the bytes the patch was built against and now holds the
  ``b.w`` into the cave that the kit computed;
* the appended routine is the trampoline it is written as -- reproduce the
  overwritten instruction, jump back to the stock fall-through;
* an image the patch was not built against (already patched), and an unsealed
  image, are both refused;
* the patched watch boots, pairs and binds; a matched command (which does not go
  through the hook) still answers; and an unmatched command (which now reaches the
  fall-through *through* the hook) still gets its reply -- so the detour returns
  transparently and nothing faults.

The emulator cannot prove a first-stage bootloader will accept the image, nor that
the storage/leak behaviour on hardware matches (docs/watch-emulator.md, #74); it
proves the patch is well-formed and transparent to the running firmware.

Run with:  uv run normtest patchkit
"""
import asyncio
import struct
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from normplus.firmware import image_tool, patchkit
from normplus.watch.fw.phone import (BIND_END, BIND_START, CHECK, DATETIME, Deframer,
                                     EmulatedWatch, FIRST_SCREEN_FRAME, ack, frame)

REPO = HERE.parents[1]
STOCK = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"
RESOURCES = REPO / "NORM/assets/Picture_P03B_NORM2_0.4.bin"
IMAGE = STOCK.read_bytes()
PASSTHROUGH = patchkit.PATCHES["passthrough"]
HOOK = PASSTHROUGH.hooks[0]

#: A command code the firmware's 87-entry table does not have, so it reaches the
#: dispatcher fall-through -- the one path the passthrough hook sits on.
UNKNOWN = 0x20
#: BATTERY_POWER -- a matched command, answered by its own handler, never the
#: fall-through. Proves ordinary commands are untouched.
BATTERY = 0x08

#: Built once, outside the repository: a patched image is vendor-derived.
_WORK = Path(tempfile.mkdtemp(prefix="patchkit-"))
PATCHED = _WORK / "patched.bin"
BOUND = _WORK / "patched-bound.zip"
_built: dict | None = None
_session_result: dict | None = None


def built() -> dict:
    global _built
    if _built is None:
        _built = patchkit.apply(IMAGE, PASSTHROUGH)
        PATCHED.write_bytes(_built["image"])
    return _built


# -- the build, without the emulator -------------------------------------------

def test_the_passthrough_builds_verifies_and_is_sealed():
    result = built()
    info = image_tool.parse(result["image"])
    assert info["declared_len"] == info["payload_len"], info
    assert info["declared_crc"] == info["actual_crc"], info
    # It is appended after the stock image, 4-aligned, and within the cave.
    assert result["cave_start"] == 0x000D6694, hex(result["cave_start"])
    assert result["cave_free"] > 0, result["cave_free"]
    assert result["cave_start"] + len(result["blob"]) <= patchkit.CAVE_LIMIT


def test_the_hook_site_now_branches_to_the_cave():
    result = built()
    off = image_tool.HEADER_LEN + (HOOK.site - patchkit.LINK_ADDRESS)
    # The stock image held exactly what the hook expected...
    assert IMAGE[off:off + 4] == HOOK.expected, IMAGE[off:off + 4].hex(" ")
    # ...and the patched image holds the b.w the kit computed, which capstone
    # reads back as a branch to the appended routine.
    patched_bytes = result["image"][off:off + 4]
    target = result["symbols"]["patch_entry"] & ~1
    assert patched_bytes == patchkit.encode_branch(HOOK.site, target, call=False)
    (insn,) = patchkit.disassemble(patched_bytes, HOOK.site)
    assert insn.split("  ")[1] == f"b.w #0x{target:x}", insn


def test_the_appended_routine_is_the_passthrough_trampoline():
    result = built()
    lines = patchkit.disassemble(result["blob"][:8], result["cave_start"])
    mnems = [ln.split("  ")[1] for ln in lines]
    assert mnems == ["movw r1, #0x100", "ldr r0, [pc, #0]", "bx r0"], mnems
    # The literal the trampoline jumps through is the stock fall-through address
    # (Thumb bit set), so bx lands at 0x00039F8C.
    literal = int.from_bytes(result["blob"][8:12], "little")
    assert literal == 0x00039F8D, hex(literal)


def test_it_refuses_an_image_it_was_not_built_against():
    # Patching the already-patched image: the hook site no longer holds the
    # expected bytes.
    try:
        patchkit.apply(built()["image"], PASSTHROUGH)
    except ValueError as exc:
        assert "patched already" in str(exc) or "expected" in str(exc), exc
    else:
        raise AssertionError("a second patch should have been refused")


def test_it_refuses_an_unsealed_image():
    unsealed = bytearray(IMAGE)
    # Make the length field disagree with the payload so `apply` refuses it.
    unsealed_len = len(unsealed) - image_tool.HEADER_LEN + 1
    struct.pack_into("<I", unsealed, image_tool.OFF_LEN, unsealed_len)
    try:
        patchkit.apply(bytes(unsealed), PASSTHROUGH)
    except ValueError as exc:
        assert "seal it first" in str(exc), exc
    else:
        raise AssertionError("an unsealed image should have been refused")


# -- the patched watch, on the emulator ----------------------------------------

def session() -> dict:
    """Boot the patched watch, bind it, and ask it a matched and an unmatched command."""
    global _session_result
    if _session_result is not None:
        return _session_result
    built()
    watch = EmulatedWatch(PATCHED, RESOURCES,
                          flash_state=BOUND if BOUND.exists() else None)
    watch.start()
    result: dict = {}

    async def ask(phone, code: int):
        phone.clear()
        await phone.send(frame(code, CHECK, b"\x00"))
        deframer, frames = Deframer(), []
        for _, value in await phone.replies(timeout=5):
            frames += deframer.feed(value)
        return frames[0] if frames else None

    async def flow(phone):
        assert await phone.find()
        await phone.connect()
        await phone.pair()
        await phone.discover()
        await phone.listen()
        if await phone.check_init() == 0:
            assert await watch.past_boot_animation(timeout=90), "the setup screen never came up"
            result["bind"] = list((await phone.bind()).values())
            for _ in range(30):
                await asyncio.sleep(0.5)
                if await phone.check_init() == 1:
                    break
        result["init"] = await phone.check_init()
        result["frames"] = watch.display.frames
        result["battery"] = await ask(phone, BATTERY)      # matched: own handler
        result["unknown"] = await ask(phone, UNKNOWN)      # unmatched: via the hook
        await phone.disconnect()

    try:
        watch.drive(flow, timeout=300)
    except Exception as exc:  # noqa: BLE001 - reported by the tests
        result["error"] = f"{type(exc).__name__}: {exc}"
    finally:
        watch.stop(save_to=BOUND)
    result["machine"] = watch.machine
    _session_result = result
    return result


def test_the_patched_watch_boots_pairs_and_binds():
    r = session()
    assert "error" not in r, r["error"]
    if "bind" in r:
        assert r["bind"] == [ack(BIND_START), ack(DATETIME), ack(BIND_END)], \
            [(b or b"").hex(" ") for b in r["bind"]]
    assert r["init"] == 1, r["init"]
    assert r["frames"] > FIRST_SCREEN_FRAME, r["frames"]
    assert not r["machine"].faults, list(r["machine"].faults)


def test_a_matched_command_still_answers():
    r = session()
    reply = r["battery"]
    assert reply is not None, "the watch did not answer BATTERY_POWER"
    # 6F 08 80 <len> <payload> 8F -- a CHECK_RESPONSE for the command asked.
    assert reply[0] == 0x6F and reply[1] == BATTERY and reply[-1] == 0x8F, reply.hex(" ")


def test_an_unmatched_command_reaches_the_hook_and_still_replies():
    r = session()
    reply = r["unknown"]
    # The fall-through answers an unknown command with the generic negative
    # response (6F 01 81 <len> <status> <cmd> ... 8F): command 0x01, the command
    # it could not find echoed in the payload. With the transparent hook in the
    # path that reply must still arrive unchanged -- a broken detour would hang or
    # fault here instead (and faults are asserted clean in the boot test).
    assert reply is not None, "the fall-through gave no reply -- the hook did not return"
    assert reply[0] == 0x6F and reply[-1] == 0x8F, reply.hex(" ")
    assert reply[1] == 0x01, f"not the generic response: {reply.hex(' ')}"
    assert UNKNOWN in reply[3:-1], f"the unknown command was not echoed: {reply.hex(' ')}"


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
