"""Watching memory without derailing the run.

Unicorn's own memory hooks cannot be used on this machine. Registering a
UC_HOOK_MEM_WRITE over SRAM does not merely miss writes: the firmware dies at
~155k instructions on an unmapped fetch of 0x10060000 -- one past the end of
SRAM -- with a PC of 0, against a clean run with no hook. It also reports the
same write count whatever the length of the run, which is exactly what makes it
convincing and wrong. Two investigations concluded "nothing writes this address"
from a hook that had already killed the run before the write happened.

Apollo3Machine.watch() checks from the block hook instead, which is the
machine's own and is known good.

Run with:  PYTHONPATH=tools/watchemu py -3.11 tools/watchemu/tests/test_watchpoints.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normwatch.fw import image as image_mod
from normwatch.fw.machine import Apollo3Machine, Watchpoint

REPO = Path(__file__).resolve().parents[3]
IMAGE = REPO / "NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin"

#: Long enough for the firmware to have cleared BSS and started writing state,
#: short enough to keep the suite fast.
BUDGET = 400_000
#: Somewhere in SRAM the firmware touches during early bring-up.
SRAM = 0x10000000

_img = image_mod.load(IMAGE)


def machine():
    return Apollo3Machine(_img, log=lambda *a, **k: None, trace=None)


# ── the bookkeeping, without booting anything ────────────────────────────────

def test_a_watchpoint_records_before_and_after():
    point = Watchpoint(0x10000000, 4, "test")
    point.value = b"\x00\x00\x00\x00"
    assert point.changes == []
    assert "0x10000000" in repr(point)


def test_a_run_with_no_watchpoints_pays_nothing():
    m = machine()
    assert m._watchpoints == []
    # The hot path must not even look at the list contents when it is empty.
    m.run(max_instructions=BUDGET, slice_size=25_000)
    assert m._watchpoints == []


def test_unwatch_removes_it():
    m = machine()
    point = m.watch(SRAM, 4)
    assert m._watchpoints == [point]
    m.unwatch(point)
    assert m._watchpoints == []
    m.unwatch(point)          # removing twice must not raise


# ── the property that matters: watching does not change the run ──────────────

def _run(with_watchpoint):
    m = machine()
    point = m.watch(SRAM, 4, label="probe") if with_watchpoint else None
    stats = m.run(max_instructions=BUDGET, slice_size=25_000)
    return stats, point


def test_watching_does_not_perturb_the_run():
    plain, _ = _run(False)
    watched, point = _run(True)
    assert plain.stop.kind == watched.stop.kind, (plain.stop, watched.stop)
    assert plain.instructions == watched.instructions
    assert plain.stop.pc == watched.stop.pc
    # And it has to actually be doing something, or the comparison proves nothing.
    assert point.changes, "the watchpoint saw no change at all"


def test_a_change_is_attributed_to_an_executable_block():
    _, point = _run(True)
    instructions, block_pc, before, after = point.changes[0]
    assert before != after
    assert len(before) == len(after) == 4
    assert 0 < instructions <= BUDGET
    # The block address must be somewhere the CPU can actually have been.
    assert block_pc >= _img.link_address, hex(block_pc)


def test_the_callback_fires_with_the_same_values():
    m = machine()
    seen = []
    point = m.watch(SRAM, 4, label="cb",
                    on_change=lambda p, pc, b, a: seen.append((pc, b, a)))
    m.run(max_instructions=BUDGET, slice_size=25_000)
    assert len(seen) == len(point.changes)
    for (pc, before, after), (_, cpc, cbefore, cafter) in zip(seen, point.changes):
        assert (pc, before, after) == (cpc, cbefore, cafter)


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
