"""A running machine, saved and put back (``boot --save-state`` / ``--load-state``).

Every probe used to pay for a boot: ~400M instructions of boot animation before
the UI exists. A snapshot is the machine at the end of a run -- memory, CPU,
every device -- so the next run starts there in well under a second and carries
on exactly as the first would have: ``run(..., resume=True)`` from a restored
machine lands on the same fingerprint, to the instruction, as one run straight
through (tests/test_snapshot.py checks it against tests/golden.json).

What is in one:

* **Memory**: every mapped region that is not MMIO -- internal flash, the boot
  ROM window, SRAM.
* **The CPU**: Unicorn's context. Two things in the binding are wrong for this.
  Its pickling keeps a pointer into a buffer it has already freed (the process
  dies at exit), and a context is not position-independent: it carries six
  per-engine heap pointers (most likely QEMU's MPU region arrays, at bytes 4080-4136 of the
  4660), and restoring them into another engine is an access violation. So the
  bytes go into a context the target engine allocated itself, and every word
  that differs between two fresh engines -- or between the saving process's
  blank context and this one's -- keeps the target's own value.
* **The native hook's counters** (fasthook.State), field by field.
* **Everything else**: the Python state of every object reachable from the
  machine -- CortexM, the boot ROM, the bus and its peripherals, the devices
  behind them. A reference from one object to another is written as the
  other's path from the machine (``m.bus.by_base[0x50014000]``) and comes back
  as the object at that path in the machine being restored, so the restoring
  side builds its machine the same way (as for flashstate) and a difference is
  an error that names it. Objects made during the run are made again.

What is not: the session's wiring (log functions, the tracer, watchpoints, the
stop flag, real-time pacing), and a bumble radio -- its link state lives in
bumble, outside the machine, so a machine with one is refused. The NZ8801
model (``attach_ble_controller`` with no argument) is plain state and is fine.
Pending stimulus is refused too: it is lambdas, and it belongs to the run that
scheduled it.

A snapshot is a pickle. Load only ones you made.
"""

from __future__ import annotations

import collections
import ctypes
import enum
import hashlib
import io
import pickle
import time
import types
import zlib
from pathlib import Path

from unicorn import UC_ARCH_ARM, UC_MODE_MCLASS, UC_MODE_THUMB, Uc
from unicorn.arm_const import UC_CPU_ARM_CORTEX_M4

from .fasthook import NativeHook, State
from .image import FirmwareImage
from .machine import MMIO_WINDOWS

FORMAT = 1

#: Values that are data, copied as they are.
_PLAIN = (int, float, complex, str, bytes, bytearray, bool, type(None), range, slice,
          enum.Enum, type)
_CONTAINERS = (list, tuple, dict, set, frozenset, collections.deque)

#: Attributes that wire a machine into its session rather than being part of
#: the watch: the machine being restored keeps its own.
SESSION = frozenset({"log", "trace", "_watchpoints", "stop_requested", "realtime",
                     "realtime_slept", "realtime_lag"})

#: Objects that carry over by identity only. The image is checked by hash; the
#: native hook's state is copied field by field.
_IDENTITY_ONLY = (FirmwareImage, NativeHook, State)

#: Classes a snapshot cannot hold, and why.
_REFUSED = {
    "BumbleController": "a bumble radio is attached: its link state lives in bumble, "
                        "outside the machine (the NZ8801 model alone is fine)",
    "Radio": "a bumble radio is attached",
    "AndroidLink": "an Android netsim endpoint is attached",
}

#: Machine switches that decide how it runs; a snapshot only goes back into a
#: machine built with the same ones.
BUILD_SWITCHES = ("ble", "idle_skip", "deadline_quantum")


class SnapshotMismatch(Exception):
    """The machine being restored is not built like the one that was saved."""


class SnapshotRefused(Exception):
    """This machine cannot be saved."""


# -- the object graph -------------------------------------------------------------

def _is_ours(obj) -> bool:
    return type(obj).__module__.startswith("normplus.watch.") and not isinstance(obj, types.FunctionType)


def _carries_state(obj) -> bool:
    return _is_ours(obj) and not isinstance(obj, _IDENTITY_ONLY)


def _state(obj) -> dict:
    if hasattr(obj, "__dict__"):
        return {k: v for k, v in vars(obj).items() if k not in SESSION}
    slots = [s for c in type(obj).__mro__ for s in getattr(c, "__slots__", ())]
    return {s: getattr(obj, s) for s in slots if hasattr(obj, s) and s not in SESSION}


def _walk(root) -> dict:
    """Every non-data object reachable from *root*, by its first path, in a fixed order.

    Breadth first through attributes, list indices and dict keys (sets by the
    repr of their members), so two machines built the same way name every
    object the same way.
    """
    paths: dict = {}
    seen: set = set()
    queue = collections.deque([("m", root)])
    while queue:
        path, obj = queue.popleft()
        if isinstance(obj, _PLAIN):
            continue
        if isinstance(obj, _CONTAINERS):
            if isinstance(obj, dict):
                items = obj.items()
            elif isinstance(obj, (set, frozenset)):
                items = enumerate(sorted(obj, key=repr))
            else:
                items = enumerate(obj)
            for key, value in items:
                queue.append((f"{path}[{key!r}]", value))
            continue
        if id(obj) in seen:
            continue
        seen.add(id(obj))
        if type(obj).__name__ in _REFUSED and _is_ours(obj):
            raise SnapshotRefused(f"{path}: {_REFUSED[type(obj).__name__]}")
        paths[path] = obj
        if _carries_state(obj):
            for key, value in _state(obj).items():
                queue.append((f"{path}.{key}", value))
    return paths


class _Pickler(pickle.Pickler):
    def __init__(self, file, ids: dict) -> None:
        super().__init__(file, protocol=5)
        self._ids = ids

    def persistent_id(self, obj):
        if isinstance(obj, _PLAIN) or isinstance(obj, _CONTAINERS):
            return None
        return self._ids.get(id(obj))


class _Unpickler(pickle.Unpickler):
    def __init__(self, file, objects: dict) -> None:
        super().__init__(file)
        self._objects = objects

    def persistent_load(self, pid):
        return self._objects[pid]


# -- memory and CPU -----------------------------------------------------------------

def _ram_regions(uc) -> list:
    """Mapped regions that are memory rather than MMIO, as (base, size)."""
    out = []
    for begin, end, _perms in uc.mem_regions():
        if not any(base <= begin < base + size for base, size in MMIO_WINDOWS):
            out.append((begin, end - begin + 1))
    return out


def _context_bytes(uc) -> bytes:
    context = uc.context_save()
    data = ctypes.string_at(context.context, context.size)
    del context
    return data


def _blank_context() -> bytes:
    uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
    uc.ctl_set_cpu_model(UC_CPU_ARM_CORTEX_M4)
    return _context_bytes(uc)


def _restore_cpu(uc, saved: bytes, saved_blank: bytes) -> int:
    """Put *saved* into *uc*, keeping *uc*'s own value for every word that is not
    the CPU's but the engine's. Returns how many words were kept."""
    here, other, current = _blank_context(), _blank_context(), _context_bytes(uc)
    if not len(saved) == len(saved_blank) == len(here) == len(current):
        raise SnapshotMismatch(f"the CPU context is {len(saved)} bytes in the snapshot and "
                               f"{len(current)} here: a different Unicorn build")
    merged = bytearray(saved)
    kept = 0
    for word in range(0, len(here) - len(here) % 8, 8):
        span = slice(word, word + 8)
        if here[span] != other[span] or here[span] != saved_blank[span]:
            merged[span] = current[span]
            kept += 1
    context = uc.context_save()
    ctypes.memmove(context.context, bytes(merged), len(merged))
    uc.context_restore(context)
    del context
    return kept


# -- save / restore -------------------------------------------------------------------

def _image_digest(machine) -> str:
    return hashlib.sha256(machine.image.payload).hexdigest()


def save(path, machine) -> dict:
    """Write *machine* as it stands at the end of its last run."""
    started = time.perf_counter()
    if machine._resume_pc is None:
        raise SnapshotRefused("this machine has not run yet; there is nothing to save")
    if machine._events or machine._posted:
        raise SnapshotRefused(f"{len(machine._events) + len(machine._posted)} stimulus "
                              "event(s) still pending: save before scheduling input or "
                              "after it has fired")
    paths = _walk(machine)
    ids = {id(obj): path for path, obj in paths.items()}

    states = {path: _state(obj) for path, obj in paths.items() if _carries_state(obj)}
    buffer = io.BytesIO()
    _Pickler(buffer, ids).dump(states)

    uc = machine.uc
    snapshot = {
        "format": FORMAT,
        "image_sha256": _image_digest(machine),
        "switches": {name: getattr(machine, name) for name in BUILD_SWITCHES},
        "instructions": machine._instructions,
        "graph": [(p, type(o).__module__, type(o).__qualname__, _carries_state(o))
                  for p, o in paths.items()],
        "objects": buffer.getvalue(),
        "memory": {base: bytes(uc.mem_read(base, size)) for base, size in _ram_regions(uc)},
        "cpu": _context_bytes(uc),
        "blank_cpu": _blank_context(),
        "native": ({name: getattr(machine.native_hook.state, name) for name, _ in State._fields_}
                   if machine.native_hook is not None else None),
    }
    raw = pickle.dumps(snapshot, protocol=5)
    Path(path).write_bytes(zlib.compress(raw, 1))
    return {"objects": len(states), "bytes": Path(path).stat().st_size,
            "instructions": machine._instructions,
            "seconds": round(time.perf_counter() - started, 3)}


def restore(path, machine, *, log=print) -> dict:
    """Make *machine* -- freshly built, the same way -- the machine that was saved.

    Then ``machine.run(budget, resume=True)`` carries on from where it was.
    """
    started = time.perf_counter()
    snapshot = pickle.loads(zlib.decompress(Path(path).read_bytes()))
    if snapshot.get("format") != FORMAT:
        raise SnapshotMismatch(f"{path} is snapshot format {snapshot.get('format')}, "
                               f"this emulator reads {FORMAT}")
    if snapshot["image_sha256"] != _image_digest(machine):
        raise SnapshotMismatch(f"{path} was saved from a different firmware image")
    switches = {name: getattr(machine, name) for name in BUILD_SWITCHES}
    if switches != snapshot["switches"]:
        differ = ", ".join(f"{k} {snapshot['switches'][k]} there, {switches[k]} here"
                           for k in BUILD_SWITCHES if switches[k] != snapshot["switches"][k])
        raise SnapshotMismatch(f"{path} was saved from a machine built differently: {differ}")

    # Every object the snapshot names has to be here, at the same path, of the
    # same class -- and nothing here may be missing from it. Strict on purpose:
    # re-creating what is missing would hand back a machine with devices nobody
    # attached. (Nothing in this emulator is created mid-run; if that changes,
    # this is where it will say so.)
    fresh = _walk(machine)
    saved = {p: (module, qualname, carries) for p, module, qualname, carries in snapshot["graph"]}
    missing, objects = [], {}
    for p, (module, qualname, carries) in saved.items():
        here = fresh.get(p)
        if here is not None and (type(here).__module__, type(here).__qualname__) == (module, qualname):
            objects[p] = here
        else:
            missing.append((not carries, qualname, p))
    extra = [(not _carries_state(o), type(o).__qualname__, p)
             for p, o in fresh.items() if p not in saved]
    if missing or extra:
        def names(items):
            items = sorted(items)                       # devices before handles
            shown = ", ".join(f"{q} ({p})" for _, q, p in items[:4])
            return shown + (f" and {len(items) - 4} more" if len(items) > 4 else "")
        parts = []
        if missing:
            parts.append("the snapshot has " + names(missing) + ", which this machine does not")
        if extra:
            parts.append("this machine has " + names(extra) + ", which the snapshot does not")
        raise SnapshotMismatch("; ".join(parts) + " -- build it the way the saved one was")

    states = _Unpickler(io.BytesIO(snapshot["objects"]), objects).load()
    for p, state in states.items():
        obj = objects[p]
        if hasattr(obj, "__dict__"):
            obj.__dict__.update(state)
        else:
            for key, value in state.items():
                setattr(obj, key, value)

    regions = dict(_ram_regions(machine.uc))
    for base, data in snapshot["memory"].items():
        if regions.get(base) != len(data):
            raise SnapshotMismatch(f"memory at 0x{base:08X} is mapped differently here")
        machine.uc.mem_write(base, data)
    kept = _restore_cpu(machine.uc, snapshot["cpu"], snapshot["blank_cpu"])

    if (snapshot["native"] is None) != (machine.native_hook is None):
        raise SnapshotMismatch("the snapshot and this machine disagree about the C block hook "
                               "(--no-fast-hook on one side?)")
    if snapshot["native"] is not None:
        for name, value in snapshot["native"].items():
            setattr(machine.native_hook.state, name, value)

    seconds = time.perf_counter() - started
    instructions = snapshot["instructions"]
    log(f"  [state] restored {path}: {len(states)} objects, {instructions:,} instructions "
        f"({instructions / 48_000_000:.2f}s of watch time) in {seconds:.2f}s")
    return {"objects": len(states), "instructions": instructions,
            "cpu_words_kept": kept, "seconds": round(seconds, 3)}
