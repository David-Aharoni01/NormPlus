"""Loader for the native basic-block hook.

Crossing into Python costs ~285 ns per basic block, and this firmware averages a
block every 5.4 instructions — 7.4M crossings per 40M, about 30% of the wall
clock. ``native/watchemu_hook.c`` does the same work without entering Python;
this module loads it, hands it the two Unicorn entry points it needs, and
registers it in place of the Python hook.

Everything else stays in Python. The only emulator logic that exists twice is
the mask check, and it is kept small enough to get right twice — Python decides
*which* exception is pending and at what priority, and the C side only compares
that against FAULTMASK/PRIMASK/BASEPRI to decide whether to stop.

If the library is not built, :func:`load` returns ``None`` and the emulator uses
the Python hook exactly as before. Build it with::

    py -3.11 tools/watchemu/native/build.py
"""

from __future__ import annotations

import ctypes
import sys
from pathlib import Path
from typing import Optional

from unicorn.arm_const import (UC_ARM_REG_BASEPRI, UC_ARM_REG_FAULTMASK,
                               UC_ARM_REG_PRIMASK)

#: Must match watchemu_hook.c.
NO_PENDING = 256
REASON_NONE = 0
REASON_EXCEPTION = 1
REASON_IDLE = 2
REASON_QUANTUM = 3

_LIBRARY = Path(__file__).resolve().parents[2] / "native" / (
    "watchemu_hook.dll" if sys.platform == "win32" else "watchemu_hook.so")

#: UC_HOOK_BLOCK. Hard-coded rather than imported so the struct layout and the
#: hook type are pinned together in one place.
_UC_HOOK_BLOCK = 1 << 3


class State(ctypes.Structure):
    """Mirror of ``watchemu_state``. Checked against the C sizeof at load."""

    _fields_ = [
        ("instructions", ctypes.c_uint64),
        ("blocks", ctypes.c_uint64),
        ("quantum_cycles", ctypes.c_uint32),
        ("time_quantum", ctypes.c_uint32),
        ("pending_priority", ctypes.c_int32),
        ("pending_is_nmi", ctypes.c_int32),
        ("stop_reason", ctypes.c_uint32),
        ("idle_head", ctypes.c_uint32),
        ("idle_max_iteration", ctypes.c_uint32),
        ("idle_hit", ctypes.c_uint32),
        ("idle_last_head", ctypes.c_uint64),
        ("idle_last_valid", ctypes.c_uint32),
        ("stopped_address", ctypes.c_uint64),
        ("just_stopped", ctypes.c_uint32),
        ("reg_faultmask", ctypes.c_int32),
        ("reg_primask", ctypes.c_int32),
        ("reg_basepri", ctypes.c_int32),
    ]


class NativeHook:
    """A loaded native hook, registered against one Unicorn engine."""

    def __init__(self, library: ctypes.CDLL, uc) -> None:
        self._library = library
        self._uc = uc
        self.state = State()
        self.state.pending_priority = NO_PENDING
        self.state.time_quantum = 256
        self.state.reg_faultmask = UC_ARM_REG_FAULTMASK
        self.state.reg_primask = UC_ARM_REG_PRIMASK
        self.state.reg_basepri = UC_ARM_REG_BASEPRI
        self._handle = ctypes.c_size_t(0)

    # ── registration ─────────────────────────────────────────────────────────

    def attach(self) -> bool:
        """Register the C callback with Unicorn. False if that is not possible."""
        try:
            from unicorn.unicorn_py3 import unicorn as ucmod

            lib = ctypes.CDLL(ucmod.uclib._name)
            hook_add = lib.uc_hook_add
            hook_add.restype = ctypes.c_int
            hook_add.argtypes = (ctypes.c_void_p, ctypes.c_void_p, ctypes.c_int,
                                 ctypes.c_void_p, ctypes.c_void_p,
                                 ctypes.c_uint64, ctypes.c_uint64)
            callback = ctypes.cast(self._library.watchemu_block, ctypes.c_void_p)
            err = hook_add(self._uc._uch, ctypes.byref(self._handle),
                           _UC_HOOK_BLOCK, callback,
                           ctypes.byref(self.state), 1, 0)
            return err == 0
        except Exception:
            return False

    # ── the bits Python has to keep current ──────────────────────────────────

    def set_pending(self, priority: Optional[int], is_nmi: bool = False) -> None:
        """Tell the hook the priority of the best pending exception.

        ``None`` means nothing is pending, and the hook then costs a single
        compare per block. Anything else and it reads the mask registers to
        decide whether to hand control back.
        """
        self.state.pending_priority = NO_PENDING if priority is None else int(priority)
        self.state.pending_is_nmi = 1 if is_nmi else 0

    def set_idle_loop(self, head: Optional[int], max_iteration: int) -> None:
        self.state.idle_head = 0 if head is None else int(head)
        self.state.idle_max_iteration = int(max_iteration)
        self.state.idle_last_valid = 0

    def forget_idle_position(self) -> None:
        self.state.idle_last_valid = 0

    def set_time_quantum(self, cycles: int) -> None:
        self.state.time_quantum = int(cycles)

    def take_stop_reason(self) -> int:
        reason, self.state.stop_reason = self.state.stop_reason, REASON_NONE
        return reason

    def take_idle_hit(self) -> bool:
        hit = bool(self.state.idle_hit)
        self.state.idle_hit = 0
        return hit


_SOURCE = _LIBRARY.with_suffix(".c")


def _needs_build() -> Optional[str]:
    """Why the library has to be built, or None if it is good to go."""
    if not _LIBRARY.exists():
        return "not built yet"
    if _SOURCE.exists() and _SOURCE.stat().st_mtime > _LIBRARY.stat().st_mtime:
        return "older than watchemu_hook.c"
    return None


def _build(log) -> bool:
    """Compile the library. Returns False and explains rather than raising.

    Building it on demand matters more than it sounds: a stale library is worse
    than a missing one, because it loads and runs. Rebuilding whenever the
    source is newer means editing the C cannot leave a run silently using the
    previous version.
    """
    import importlib.util

    script = _LIBRARY.parent / "build.py"
    if not script.exists():
        log(f"  [native] {script.name} is missing — using the Python hook")
        return False
    try:
        spec = importlib.util.spec_from_file_location("_watchemu_native_build", script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module.build() == 0
    except Exception as exc:
        log(f"  [native] could not build it ({exc}) — using the Python hook")
        return False


def load(uc, log=print, build: bool = True) -> Optional[NativeHook]:
    """Load and register the native hook, or return None and say why.

    Builds the library first if it is missing or older than its source, so
    --fast-hook works without anyone having to know a build step exists.
    """
    reason = _needs_build()
    if reason is not None:
        if not build:
            log(f"  [native] watchemu_hook is {reason} — using the Python hook")
            return None
        log(f"  [native] watchemu_hook is {reason} — building it")
        if not _build(log):
            return None
        if _needs_build() is not None:
            log("  [native] the build reported success but produced nothing — "
                "using the Python hook")
            return None

    try:
        library = ctypes.CDLL(str(_LIBRARY))
    except OSError as exc:
        log(f"  [native] {_LIBRARY.name} would not load ({exc}) — using the Python hook")
        return None

    size = library.watchemu_state_size()
    if size != ctypes.sizeof(State):
        log(f"  [native] {_LIBRARY.name} describes a {size}-byte state and this "
            f"build expects {ctypes.sizeof(State)} — using the Python hook")
        return None

    try:
        from unicorn.unicorn_py3 import unicorn as ucmod

        uclib = ctypes.CDLL(ucmod.uclib._name)
        library.watchemu_init(
            ctypes.cast(uclib.uc_reg_read, ctypes.c_void_p),
            ctypes.cast(uclib.uc_emu_stop, ctypes.c_void_p))
    except Exception as exc:
        log(f"  [native] could not hand Unicorn's entry points over ({exc}) — "
            f"using the Python hook")
        return None

    hook = NativeHook(library, uc)
    if not hook.attach():
        log("  [native] uc_hook_add refused the callback — using the Python hook")
        return None
    return hook
