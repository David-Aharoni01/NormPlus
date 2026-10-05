"""Command line for the Norm 2 watch emulator.

    normwatch info
    normwatch boot --seconds 14 --no-ble
    normwatch modules
    normcmd 08 70                                    (the same as: normwatch cmd ...)
    normcmd BATTERY_POWER CHECK --mac                the physical watch

Installed by `uv tool install --editable .` at the repository root, or run in place
with `uv run normwatch ...`.
"""

from __future__ import annotations

import argparse
import contextlib
import json
import sys
from pathlib import Path

from .. import developer_options, paths
from .fw import flashstate, snapshot
from .fw.bootrom import BootRom
from .fw import image as image_mod
from .fw.console import FirmwareConsole, find_formatter
from .fw.devices import (attach_ble_controller, attach_mspi_devices,
                         attach_motion_sensor, attach_pmu, attach_touch_panel)
from .fw.machine import Apollo3Machine, StopReason
from .fw.resources import MissingResources
from .fw.rtos import format_tasks
from .fw.symbols import SymbolMap
from .fw.trace import Tracer

DEFAULT_IMAGE = paths.FIRMWARE
#: Flash states under here are read-only to `boot --flash-state` (test_golden hashes them).
FIXTURES = (paths.TESTS / "fixtures").resolve()
DEFAULT_RESOURCES = paths.RESOURCES
#: The watch's own factory resources, mounted by `boot` when they are there (#71).
DEFAULT_FACTORY_NAND = paths.FACTORY_NAND
#: The physical watch: what --mac means without an address, and the emulated watch's own
#: address. The same as fw.phone.WATCH, which is not imported here -- it brings bumble.
WATCH = "4C:59:80:12:44:F1"
#: Instructions per emu_start. Only a safety cap: the block hook stops emulation the
#: moment an interrupt is deliverable, and the deadline quantum before the next one.
SLICE = 25_000


def _load(path: str | Path) -> image_mod.FirmwareImage:
    if not Path(path).exists() and Path(path).resolve().is_relative_to(paths.NORM.resolve()):
        print(f"error: {paths.NORM_MISSING}", file=sys.stderr)
        raise SystemExit(2)
    try:
        return image_mod.load(path)
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        raise SystemExit(2)


def cmd_info(args) -> int:
    img = _load(args.image)
    print(img.describe())
    if not img.crc_ok:
        print("\nWARNING: the image CRC does not match — this image is not self-consistent.")
    print()
    print("  vector table:")
    names = {0: "initial SP", 1: "Reset", 2: "NMI", 3: "HardFault", 11: "SVCall",
             14: "PendSV", 15: "SysTick"}
    for index, name in names.items():
        print(f"    {name:<12} 0x{img.vector(index):08X}")
    return 0


def cmd_modules(args) -> int:
    img = _load(args.image)
    symbols = SymbolMap(img.payload, img.link_address)
    modules = symbols.modules()
    print(f"{len(modules)} source modules recovered from assert() strings, "
          f"{len(symbols.refs)} references:")
    for module in modules:
        print(f"  {module}")
    return 0


def cmd_command(args) -> int:
    """Ask a watch one 0x6F question, the way the app asks it.

    By default the watch is the emulated one: the firmware boots, and the phone
    is a bumble host on the radio's own loop (``phone.Phone.on_air``), so no
    port is opened and this runs beside a live AVD. With --mac it is the
    physical watch, over this PC's own Bluetooth adapter
    (``physical.PhysicalPhone``, bleak). Either way the phone pairs, and --
    unless told not to -- does what BindWatchUseCase does: checkInit, and the
    bind only if the watch says it is not initialised. Then the frame, the
    [03] trigger, and every notification that comes back, decoded by the same
    code for both, so a difference between them is the watches'.

    Exit status: 0 a reply came, 1 nothing came back, 2 the setup failed.
    """
    import asyncio
    import time

    from .fw.phone import (BIND_END, BIND_START, CHECK, DATETIME, Deframer, EmulatedWatch,
                           ack, action_byte, command_byte, describe, frame)
    from .physical import PhysicalPhone, mac_address

    try:
        code = command_byte(args.cmd_code)
        action = action_byte(args.action)
        payload = (bytes.fromhex(args.payload) if args.payload is not None
                   else bytes([0x00]) if action == CHECK else b"")
        mac = mac_address(args.mac) if args.mac else None
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    packet = frame(code, action, payload)
    if mac is not None and (args.flash_state or args.save):
        print("error: --flash-state and --save are the emulated watch's; the physical "
              "one (--mac) keeps its own flash", file=sys.stderr)
        return 2
    target = mac or args.address

    state = Path(args.flash_state) if args.flash_state else None
    if args.save and state is None:
        print("error: --save needs --flash-state PATH", file=sys.stderr)
        return 2
    if state is not None and not state.exists() and not args.save:
        print(f"error: no flash state at {state} (add --save to create it)", file=sys.stderr)
        return 2

    t0 = time.monotonic()

    def say(text: str) -> None:
        print(f"{time.monotonic() - t0:6.1f}s  {text}", flush=True)

    watch = None
    if mac is not None:
        say(f"watch: the physical one at {mac}, over this PC's Bluetooth adapter")
    else:
        try:
            watch = EmulatedWatch(args.image, args.resources, address=args.address,
                                  flash_state=state if state is not None and state.exists()
                                  else None)
        except flashstate.FlashStateMismatch as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 2
        if state is not None and state.exists():
            say(f"watch: started from {state}")
        watch.start()
    result = {"replies": [], "failed": None}

    async def flow(phone) -> None:
        if not await phone.find(target):
            result["failed"] = (f"{target} was never heard advertising"
                                + (" -- is a phone connected to it?" if mac else ""))
            return
        await phone.connect(target)
        await phone.pair()
        await phone.discover()
        await phone.listen()
        link = {True: "encrypted", False: "NOT encrypted"}.get(phone.encrypted,
                                                               "encryption not reported")
        say(f"phone: connected, paired, {link}")
        if not args.no_bind:
            init = await phone.check_init()
            if init == 1:
                say("watch: initialised (checkInit 1), not binding")
            elif init == 0:
                say("watch: in first-run setup (checkInit 0); binding once the setup screen is up")
                if watch is not None and not await watch.past_boot_animation(timeout=90):
                    result["failed"] = "the setup screen never came up"
                    return
                bound = await phone.bind()
                expected = [ack(BIND_START), ack(DATETIME), ack(BIND_END)]
                if list(bound.values()) != expected:
                    got = ", ".join((r or b"").hex(" ") or "nothing" for r in bound.values())
                    result["failed"] = f"the bind was not acknowledged: {got}"
                    return
                # Acknowledged is not done: the firmware writes its init flag
                # (0xFA000) when the "Pairing Success" dialog closes, about two
                # seconds after bindEnd. Until then checkInit still reads 0, and
                # a --save taken now would keep an unbound watch.
                for _ in range(30):
                    await asyncio.sleep(0.5)
                    if await phone.check_init() == 1:
                        break
                else:
                    result["failed"] = ("the bind was acknowledged but the watch never "
                                        "reported itself initialised")
                    return
                say("watch: bound (bind acknowledged, checkInit now 1)")
            else:
                result["failed"] = "checkInit got no answer"
                return
        phone.clear()
        say(f"-> {args.char}  {packet.hex(' ')}    {describe(packet)}")
        await phone.send(packet, char=args.char, trigger=not args.no_trigger)
        result["replies"] = await phone.replies(timeout=args.timeout)
        await phone.disconnect()

    async def on_the_physical_watch() -> None:
        async with PhysicalPhone.open(log=say) as phone:
            await flow(phone)

    keep = saved = False
    try:
        if watch is None:
            asyncio.run(asyncio.wait_for(on_the_physical_watch(), args.timeout + 90))
        else:
            watch.drive(flow, timeout=args.timeout + 150)
    except ImportError as exc:
        result["failed"] = (f"{exc.name or exc} is not installed: run `uv sync` at the "
                            "repository root")
    except Exception as exc:  # noqa: BLE001 - reported, and the watch still stops
        result["failed"] = f"{type(exc).__name__}: {exc}"
    finally:
        if watch is not None:
            # Not after a failed setup: a half-done bind is not a state worth keeping.
            keep = args.save and not result["failed"]
            saved = watch.stop(save_to=state if keep else None)

    status = 0
    if result["failed"]:
        say(f"failed: {result['failed']}")
        status = 2
    else:
        deframers, whole = {}, 0
        for char, value in result["replies"]:
            say(f"<- {char}  {value.hex(' ')}")
            for done in deframers.setdefault(char, Deframer()).feed(value):
                whole += 1
                say(f"         = {describe(done)}")
        if not whole:
            say(f"no reply within {args.timeout:g}s"
                + (" (a SET written to 8003 is never acknowledged)"
                   if args.char == "8003" and action == 0x71 else ""))
            status = 1
    if args.save:
        say(f"watch: flash saved to {state}" if keep and saved
            else "watch: flash NOT saved")
    return status

#: Keys 1-5 in the live window drive the GPIO pins the firmware enables interrupts
#: on (2, 3, 10, 16, 38), minus 28 which is the touch panel. Pin 3 is the button:
#: it is the only one of the six configured for the rising edge, and pressing it
#: is what wakes key_irq.c, gpio_irq.c, the vibrator task and the power-off dialog.
#: Pin 16 looks like the accelerometer -- pressing it doubles system_step_task.c's
#: coverage and adds I2C traffic. Pins 2, 10 and 38 are still unidentified.
LIVE_KEYS = {"1": 2, "2": 3, "3": 10, "4": 16, "5": 38}


def _run_live(machine, devices, args, *, log=print, missing_images=None):
    """Run the firmware with its screen in a window, the mouse driving touch."""
    import threading

    from .fw.viewer import WatchWindow

    result = {}

    def worker() -> None:
        try:
            result["stats"] = machine.run(
                max_instructions=args.max_instructions, slice_size=SLICE,
                resume=args.load_state is not None,
            )
        except BaseException as exc:  # surfaced after the window closes
            result["error"] = exc

    thread = threading.Thread(target=worker, name="watch-cpu", daemon=True)
    thread.start()
    window = WatchWindow(
        machine,
        devices["display"],
        touch=devices.get("touch"),
        scale=args.scale,
        title=f"Norm 2 — {Path(args.image).name}",
        buttons=LIVE_KEYS,
        log=log,
        extra_status=missing_images.status if missing_images is not None else None,
    )
    try:
        window.run(thread)
    finally:
        machine.stop_requested = True
        thread.join(timeout=30)
    if "error" in result:
        raise result["error"]
    stats = result.get("stats")
    if stats is None:
        # The window closed and the worker has not finished unwinding. Report what
        # the machine reached rather than restarting it, which would wipe the state
        # the report is about to print.
        from .fw.machine import RunStats, StopReason

        stats = RunStats()
        stats.instructions = machine._instructions
        stats.stop = StopReason("stopped", "window closed", 0)
    return stats


#: The boot animation's state block. ``[+0x04]`` is its frame count and
#: ``[+0x10]`` is the frame it has reached; the dialog closes when they meet
#: (0x00075A82). At about 60 ms a frame that is roughly eight seconds of watch
#: time, and the whole UI is behind it — which is why a short run shows nothing
#: but a black screen and reads as a broken emulator.
BOOT_ANIMATION_STATE = 0x10000114


def boot_animation(machine) -> tuple[int, int] | None:
    """(frame reached, frames) of the boot animation; None before the firmware sets it up."""
    try:
        total = machine.uc.mem_read(BOOT_ANIMATION_STATE + 0x04, 1)[0]
        frame = int.from_bytes(
            machine.uc.mem_read(BOOT_ANIMATION_STATE + 0x10, 4), "little")
    except Exception:
        return None
    if not total or frame > total:
        return None
    return frame, total


#: `boot` with no budget (#77) looks at the animation this often, gives the UI this long
#: to draw once it is over (a UI cycle is ~61 ms), and gives up after UNTIL_UI_AT_MOST.
UNTIL_UI_POLL = 12_000_000          # 0.25 s of watch time
UNTIL_UI_SETTLE = 48_000_000        # 1 s
UNTIL_UI_AT_MOST = 30               # seconds of watch time


class UntilTheUi:
    """Stop the run once the boot animation is over and the UI has drawn: what `boot`
    does when it is given no budget. A fixed default could not be right -- the UI comes
    up at ~14s of watch time with --no-ble and ~18s with the radio -- and the one it
    replaced, 30M instructions, was 0.6s, inside the animation (#77)."""

    def __init__(self, machine, start: int) -> None:
        self.machine, self.up = machine, False
        machine.schedule(start + UNTIL_UI_POLL, self._look)

    def _look(self) -> None:
        state = boot_animation(self.machine)
        if state is not None and state[0] >= state[1]:
            self.machine.schedule(self.machine._instructions + UNTIL_UI_SETTLE, self._stop)
        else:
            self.machine.schedule(self.machine._instructions + UNTIL_UI_POLL, self._look)

    def _stop(self) -> None:
        self.up = True
        self.machine.stop_requested = True

    def withdraw(self) -> None:
        """Take a look still pending off the queue: --save-state refuses a machine with
        stimulus pending, and the cap can end the run before the UI comes up."""
        self.machine._events[:] = [e for e in self.machine._events
                                   if getattr(e[1], "__self__", None) is not self]


def boot_animation_note(machine) -> str:
    state = boot_animation(machine)
    if state is None:
        return ""
    frame, total = state
    if frame >= total:
        return f"  boot animation: finished ({total} frames)"
    per_frame = 2_950_000
    needed = machine._instructions + (total - frame) * per_frame
    return (f"  boot animation: still playing, frame {frame} of {total} — the UI is "
            f"behind it.\n"
            f"                  Try --seconds {needed / 48_000_000:.0f} "
            f"(about {needed:,} instructions).")


def cmd_ota(args) -> int:
    """Send a firmware update to a watch -- the emulated one, or the physical one.

    The rehearsal and the real thing are the same code (``fw.otasend``), which is
    the point: what goes to the watch has been sent to the emulator first. A
    main-MCU update (type 1) needs --allow-mcu, because it replaces the code that
    receives updates and there is no way back if it does not boot (#14, #70).

    Exit status: 0 the watch took it, 1 it refused a step, 2 the setup failed.
    """
    import asyncio
    import time

    from .fw.otasend import OtaError, send_update
    from .fw.phone import (BIND_END, BIND_START, DATETIME, EmulatedWatch, OTA_TYPE_MCU, ack)
    from .physical import PhysicalPhone, mac_address

    try:
        mac = mac_address(args.mac) if args.mac else None
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    raw = Path(args.update).read_bytes()
    target = mac or args.address
    state = Path(args.flash_state) if args.flash_state else None
    if state is not None and not state.exists():
        print(f"error: no flash state at {state}", file=sys.stderr)
        return 2

    t0 = time.monotonic()

    def say(text: str) -> None:
        print(f"{time.monotonic() - t0:6.1f}s  {text}", flush=True)

    if args.type == OTA_TYPE_MCU and mac is not None:
        say("!! a main-MCU update to the PHYSICAL watch: it reboots into its bootloader "
            "when this finishes, and that step cannot be rehearsed (#70)")
    watch = None
    if mac is not None:
        say(f"watch: the physical one at {mac}")
    else:
        try:
            watch = EmulatedWatch(args.image, args.resources, address=args.address,
                                  flash_state=state)
        except flashstate.FlashStateMismatch as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 2
        watch.start()
    failed = None
    sent = None

    async def flow(phone) -> None:
        nonlocal failed, sent
        if not await phone.find(target):
            failed = f"{target} was never heard advertising"
            return
        await phone.connect(target)
        await phone.pair()
        await phone.discover()
        await phone.listen()
        say("phone: connected, paired")
        init = await phone.check_init()
        if init == 0:
            if watch is not None and not await watch.past_boot_animation(timeout=90):
                failed = "the setup screen never came up"
                return
            bound = await phone.bind()
            if list(bound.values()) != [ack(BIND_START), ack(DATETIME), ack(BIND_END)]:
                failed = "the bind was not acknowledged"
                return
            for _ in range(30):
                await asyncio.sleep(0.5)
                if await phone.check_init() == 1:
                    break
            say("watch: bound")
        # Never over the boot animation: an OTA screen opened there overflows the
        # UI task's stack (#57). The emulated watch is waited for above; a
        # physical one has been up for a while by the time it is reachable.
        if watch is not None:
            await watch.past_boot_animation(timeout=90)
            await asyncio.sleep(2)
        try:
            sent = await send_update(phone, raw, args.type, allow_mcu=args.allow_mcu,
                                     log=say, progress_every=args.progress_every)
        except OtaError as exc:
            failed = str(exc)
            return
        with contextlib.suppress(Exception):
            await phone.disconnect()

    async def on_the_physical_watch() -> None:
        async with PhysicalPhone.open(log=say) as phone:
            await flow(phone)

    try:
        if watch is None:
            asyncio.run(on_the_physical_watch())
        else:
            watch.drive(flow, timeout=args.timeout)
    except ImportError as exc:
        failed = (f"{exc.name or exc} is not installed: run `uv sync` at the "
                  "repository root")
    except Exception as exc:  # noqa: BLE001 - reported, and the watch still stops
        failed = f"{type(exc).__name__}: {exc}"
    finally:
        if watch is not None:
            watch.stop(save_to=state if args.save and state else None)

    if failed:
        say(f"failed: {failed}")
        return 1 if sent is None else 2
    say(f"ota: done -- {sent.summary() if sent else 'nothing sent'}")
    return 0


def nand_blobs(args) -> tuple[list[Path], bool]:
    """Which NAND blobs `boot` should mount, and whether the factory ones are among them.

    The watch's factory resources are mounted **by default** when the private
    submodule has them (#71): without them most screens draw LVGL's "No data" and
    the boot animation runs black, which is not what the watch does. They cost
    about 22 s on a boot that plays the whole animation -- 134 full-screen images
    really decoded instead of failing fast, which is 18,000 NAND page reads
    through the emulated SPI rather than 500 -- so `--no-factory-resources` leaves
    them out, and `--no-resources` leaves the NAND erased altogether.
    """
    if getattr(args, "no_resources", False):
        return [], False
    chosen = list(getattr(args, "nand", None) or [])
    factory = (not getattr(args, "no_factory_resources", False)
               and not chosen
               and DEFAULT_FACTORY_NAND.is_dir()
               and any(DEFAULT_FACTORY_NAND.glob("0x*.bin")))
    if factory:
        chosen.append(str(DEFAULT_FACTORY_NAND))
    return mount_blobs(chosen), factory


def mount_blobs(paths) -> list[Path]:
    """The NAND blobs behind each ``--nand``: a file, or every one in a dump directory."""
    out = []
    for text in paths:
        path = Path(text)
        if path.is_dir():
            found = sorted(path.glob("0x*.bin"))
            if not found:
                raise SystemExit(f"normwatch: no 0x<address>.bin in {path}")
            out += found
        elif path.exists():
            out.append(path)
        else:
            raise SystemExit(f"normwatch: no such NAND blob: {path}")
    return out


def cmd_dump(args) -> int:
    """Read a range of the watch's SPI NAND out, through the patch's 0xEE (#68, #69).

    Needs a watch running a patched image: `normfw patch-nand` for the emulated
    one (--image), and for the physical one (--mac) the patch flashed, which is
    #70. The conversation is the same one `cmd` has -- connect, pair, bind if the
    watch is not initialised -- and then `nanddump` asks for 128 bytes at a time
    for as long as it takes, continuing a dump already in the output directory.

    Exit status: 0 the ranges are complete, 1 it stopped part way (run it again),
    2 the setup failed.
    """
    import asyncio
    import time

    from .fw.nanddump import (NAND_SIZE, PAGE, WAKE_COMMAND, WAKE_OUT, Dump, DumpError,
                              Dumper, waker)
    from .fw.phone import SET, frame
    from .fw.phone import (BIND_END, BIND_START, DATETIME, EmulatedWatch, ack)
    from .physical import PhysicalPhone, mac_address

    def parse_range(text: str) -> tuple[int, int]:
        try:
            first, _, last = text.partition("-")
            start, end = int(first, 16), int(last, 16)
        except ValueError:
            raise ValueError(f"{text!r} is not START-END in hex, e.g. 026DA430-0496C000")
        if not 0 <= start < end <= NAND_SIZE:
            raise ValueError(f"{text!r} is not a range inside the NAND's 256 MB")
        # Whole pages: the dump is page-at-a-time so that a blank one is cheap.
        return start & ~(PAGE - 1), (end + PAGE - 1) & ~(PAGE - 1)

    try:
        ranges = [parse_range(text) for text in args.range]
        mac = mac_address(args.mac) if args.mac else None
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    target = mac or args.address
    state = Path(args.flash_state) if args.flash_state else None
    if mac is not None and state is not None:
        print("error: --flash-state is the emulated watch's; the physical one keeps "
              "its own flash", file=sys.stderr)
        return 2
    if state is not None and not state.exists():
        print(f"error: no flash state at {state}", file=sys.stderr)
        return 2

    t0 = time.monotonic()

    def say(text: str) -> None:
        print(f"{time.monotonic() - t0:6.1f}s  {text}", flush=True)

    total = sum(end - start for start, end in ranges)
    say(f"dump: {total:,} bytes in {len(ranges)} range(s) -> {args.output}")
    dump = Dump(args.output, log=say)
    dumper = Dumper(tries=args.tries, skip_blank=not args.no_skip,
                    trigger=not args.no_trigger,
                    retry_pause=args.retry_pause, refusals=args.refusals,
                    keep_awake=0.0 if args.no_wake else args.keep_awake,
                    wait_for_driver=args.wait_for_driver, log=say)

    watch = None
    if mac is not None:
        say(f"watch: the physical one at {mac} -- it must be running a patched image")
    else:
        try:
            watch = EmulatedWatch(args.image, args.resources, address=args.address,
                                  flash_state=state)
        except flashstate.FlashStateMismatch as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 2
        watch.start()
    failed = None

    async def flow(phone) -> None:
        nonlocal failed
        if not await phone.find(target):
            failed = f"{target} was never heard advertising"
            return
        await phone.connect(target)
        await phone.pair()
        await phone.discover()
        await phone.listen()
        say("phone: connected, paired")
        init = await phone.check_init()
        if init == 0:
            if watch is not None and not await watch.past_boot_animation(timeout=90):
                failed = "the setup screen never came up"
                return
            bound = await phone.bind()
            if list(bound.values()) != [ack(BIND_START), ack(DATETIME), ack(BIND_END)]:
                failed = "the bind was not acknowledged"
                return
            for _ in range(30):
                await asyncio.sleep(0.5)
                if await phone.check_init() == 1:
                    break
            say("watch: bound")
        # The storage stack goes down a few seconds after the watch stops drawing,
        # and a message-count push brings it back (#70). One at the start saves the
        # first stall; the dumper sends more whenever a read says the stack is down.
        if not args.no_wake:
            dumper.wake = waker(phone)
            await dumper.wake()
            await asyncio.sleep(6)
        try:
            await dumper.run(phone, ranges, dump, pages=args.pages)
        finally:
            if not args.no_wake:
                # Leave the watch on its face, not on the screen we jumped to.
                with contextlib.suppress(Exception):
                    await phone.send(frame(WAKE_COMMAND, SET, bytes([WAKE_OUT])))
        await phone.disconnect()

    async def on_the_physical_watch() -> None:
        async with PhysicalPhone.open(log=say) as phone:
            await flow(phone)

    def complete() -> bool:
        return sum(r["done"] for r in dump.manifest["ranges"].values()) >= total

    try:
        if watch is None:
            # A dump of tens of megabytes takes hours, and over hours this PC's
            # Bluetooth stack drops the link -- WinRT's "operation was canceled"
            # being the usual one, mid-connect or mid-read. The dump is resumable
            # by design, so a drop is something to reconnect through rather than
            # something to stop for: each session goes on from the manifest.
            for attempt in range(1, args.sessions + 1):
                failed = None                  # what flow() sets is the real reason
                try:
                    asyncio.run(on_the_physical_watch())
                except DumpError as exc:
                    failed = f"{exc}"
                    break                      # the watch said no; retrying will not help
                except (OSError, EOFError, asyncio.TimeoutError, RuntimeError) as exc:
                    failed = f"{type(exc).__name__}: {exc}"
                if complete() or args.pages is not None:
                    break
                if attempt < args.sessions:
                    # Slowly: hammering connect leaves this PC's stack with stale
                    # links, and then the watch -- which serves one connection at a
                    # time -- stops advertising and nothing can reach it at all.
                    say(f"link: {failed or 'the session ended'} -- reconnecting in "
                        f"{args.reconnect_wait:g}s ({attempt} of {args.sessions})")
                    time.sleep(args.reconnect_wait)
        else:
            watch.drive(flow, timeout=args.timeout)
    except DumpError as exc:
        failed = f"{exc}"
    except ImportError as exc:
        failed = (f"{exc.name or exc} is not installed: run `uv sync` at the "
                  "repository root")
    except Exception as exc:  # noqa: BLE001 - reported, and the watch still stops
        failed = f"{type(exc).__name__}: {exc}"
    finally:
        if watch is not None:
            watch.stop()
    if failed and complete():
        failed = None                          # it finished, whatever the last link did

    stats = dumper.stats
    if watch is not None and stats.seconds:
        # How much of the time was the emulator rather than the protocol: the
        # watch's own clock against the wall clock. A physical watch runs at 1.0
        # by definition, so a figure well under that says this rate is the
        # emulator's and the real one would be faster.
        watch_seconds = watch.machine.cycles / Apollo3Machine.CYCLES_PER_SECOND
        say(f"dump: the emulated watch ran {watch_seconds / stats.seconds:.2f}x real time "
            f"({watch_seconds:.0f}s of watch time in {stats.seconds:.0f}s)")
    say(f"dump: {stats.summary()}")
    done = sum(r["done"] for r in dump.manifest["ranges"].values())
    say(f"dump: {done:,} of {total:,} bytes in {args.output}")
    if stats.driver_down or stats.errors:
        say(f"dump: {stats.driver_down:,} reads refused because the storage stack was "
            f"down" + (f", and the driver reported "
                       f"{dict((hex(c), n) for c, n in sorted(stats.errors.items()))}"
                       if stats.errors else ""))
    if stats.reads_per_second:
        for size, what in ((NAND_SIZE, "the whole 256 MB chip"),):
            say(f"dump: {what}, read in full, would take "
                f"{stats.estimate(size) / 3600:.1f} h at this rate "
                f"({stats.reads_per_second:.1f} reads/s); blank pages cost an eighth of that")
    if failed:
        say(f"stopped: {failed} -- run it again to go on from here")
        return 2 if not stats.covered else 1
    return 0 if done >= total else 1


def cmd_boot(args) -> int:
    img = _load(args.image)
    quiet = args.json
    log = (lambda *a, **k: None) if quiet else print

    if not quiet:
        print(img.describe())
        print()

    symbols = SymbolMap(img.payload, img.link_address)
    # Tracing is off by default: it costs about 27% (5.4M vs 6.9M instructions a
    # second) and the runs that matter are now hundreds of millions of
    # instructions long. --trace brings back coverage and the stall detector.
    #
    # In a live session the stall heuristic must not stop the CPU: the window
    # would go on showing the last frame and ignoring every touch, which reads
    # as "the emulator is slow and unresponsive" rather than "it ended".
    tracer = Tracer(
        symbols, stall_window=args.stall, watch_for_stall=not args.live, log=log
    ) if args.trace else None
    # A phone on the other end of the radio keeps real time, so the watch
    # must too; --phone implies it.
    realtime = args.realtime or args.phone is not None
    machine = Apollo3Machine(img, log=log, trace=tracer, chiprev=args.chiprev,
                             idle_skip=args.idle_skip,
                             fast_hook=not args.no_fast_hook,
                             deadline_quantum=not args.fixed_quantum,
                             ble=not args.no_ble, realtime=realtime)
    resources = None if args.no_resources else Path(args.resources)
    devices = attach_mspi_devices(machine, resource_blob=resources, log=log)
    # Dumped NAND (normwatch dump, #69): each blob carries the address it belongs
    # at, exactly as the app's resource image does, so it mounts the same way.
    blobs, factory = nand_blobs(args)
    for blob in blobs:
        data = blob.read_bytes()
        devices["nand"].load(data[4:], int.from_bytes(data[:4], "little"))
    if factory:
        log("  [nand] the watch's factory resources are mounted; "
            "--no-factory-resources is ~22s faster on a full boot")
    # Kept off the machine (a snapshot walks the machine): which images the firmware drew
    # as "No data" because the NAND does not have them (fw/resources.py, #64).
    missing_images = MissingResources(machine, devices["nand"])
    # Before anything runs and before any patch: the restored flash is the
    # watch as it was left, and a patch goes on top of it, never into it.
    flash_state = Path(args.flash_state) if args.flash_state else None
    if flash_state is not None and args.load_state:
        print("error: --load-state brings its own flash; leave out --flash-state",
              file=sys.stderr)
        return 2
    if flash_state is not None:
        if flash_state.exists():
            try:
                flashstate.load(flash_state, machine, devices["nand"], log=log)
            except flashstate.FlashStateMismatch as exc:
                print(f"error: {exc}", file=sys.stderr)
                return 2
        else:
            log(f"  [flash] no state at {flash_state} yet: starting from the shipped "
                f"image, and saving there at exit")
    devices["touch"] = attach_touch_panel(machine, log=log)
    devices["motion"] = attach_motion_sensor(machine, log=log)
    devices["battery"], devices["charger"] = attach_pmu(
        machine, percent=getattr(args, "battery", 80.0),
        charging=getattr(args, "charging", False),
        charger_status=getattr(args, "charger_status", None), log=log)
    controller = None
    android = None
    if (args.radio or args.phone is not None) and not args.no_ble:
        # Imported here: bumble costs a third of a second to load and only
        # these two switches need it.
        from .fw.blelink import AndroidLink, BumbleController, Radio

        radio = Radio()
        controller = attach_ble_controller(
            machine, BumbleController(args.address, radio=radio, log=log), log=log)
        controller.trace = args.hci_trace
        if args.phone is not None:
            android = AndroidLink(radio, args.phone, log=log)
            if not quiet:
                print(f"  Android emulator: normphone start --watch "
                      f"(--port {args.phone} if not the default)")
    elif args.ble_controller and not args.no_ble:
        controller = attach_ble_controller(machine, log=log)
    # After every device is attached -- the snapshot names each by where it
    # hangs off the machine -- and before any patch, which goes on top.
    if args.load_state:
        try:
            snapshot.restore(args.load_state, machine, log=log)
        except (OSError, snapshot.SnapshotMismatch, snapshot.SnapshotRefused) as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 2
        # The budget and --press times count from where the snapshot was taken.
        args.max_instructions += machine._instructions

    console = None
    site = find_formatter(img.payload, img.link_address)
    if site is not None:
        console = FirmwareConsole(machine, site, log=log)
    elif not quiet:
        print("  note: the firmware's formatter was not found — asserts will be silent")

    for spec in args.press or []:
        parts = spec.split(":")
        pin = int(parts[0], 0)
        start = machine._instructions if args.load_state else 0
        at = (start + int(parts[1], 0) if len(parts) > 1 and parts[1]
              else start + (args.max_instructions - start) // 4)
        hold = int(parts[2], 0) if len(parts) > 2 and parts[2] else None
        machine.press_button(pin, at, hold)
    until_ui = (UntilTheUi(machine, machine._instructions if args.load_state else 0)
                if args.until_ui else None)

    try:
        if args.live:
            stats = _run_live(machine, devices, args, log=log, missing_images=missing_images)
        else:
            stats = machine.run(max_instructions=args.max_instructions, slice_size=SLICE,
                                resume=args.load_state is not None)
            if until_ui is not None:
                until_ui.withdraw()
                if until_ui.up:
                    stats.stop = StopReason("budget", "the boot animation is over and the "
                                            "UI is up", stats.stop.pc)
    finally:
        # Also on Ctrl-C and a closed window: that is how --phone sessions
        # end. Stopping between an erase and its program loses that page,
        # as pulling the battery at that instant would on the watch.
        if flash_state is not None and flash_state.resolve().is_relative_to(FIXTURES):
            # A test fixture is read, never rewritten: the golden fingerprints hash it.
            log(f"  [flash] {flash_state} is a test fixture: not saved")
        elif flash_state is not None:
            saved = flashstate.save(flash_state, machine, devices["nand"])
            log(f"  [flash] saved {len(saved['flash_pages'])} flash pages and "
                f"{len(saved['nand_pages'])} NAND pages to {flash_state}")

    state_saved = None
    if args.save_state:
        if stats.stop.kind in ("budget", "stopped"):
            try:
                state_saved = snapshot.save(args.save_state, machine)
                log(f"  [state] saved {args.save_state}: {state_saved['bytes']:,} bytes at "
                    f"{state_saved['instructions']:,} instructions")
            except snapshot.SnapshotRefused as exc:
                print(f"error: state not saved: {exc}", file=sys.stderr)
        else:
            print(f"error: state not saved: the run ended in a {stats.stop.kind}",
                  file=sys.stderr)

    if args.json:
        print(json.dumps({
            "image": str(img.path),
            "stop": {"kind": stats.stop.kind, "detail": stats.stop.detail, "pc": stats.stop.pc},
            "instructions": stats.instructions,
            "blocks": stats.blocks,
            "unique_blocks": len(stats.coverage),
            "seconds": round(stats.wall_seconds, 3),
            "asserts": console.asserts if console else [],
            "console": console.lines if console else [],
            "exceptions": {k: v for k, v in machine.cortexm.exception_counts.items()},
            "nand_pages_read": devices["nand"].pages_read,
            "display_commands": len(devices["display"].commands),
            "display_frames": devices["display"].frames,
            "display_pixel_bytes": devices["display"].pixel_bytes,
            "boot_animation": boot_animation_note(machine).strip(),
            "flash_state": str(flash_state) if flash_state is not None else None,
            "state_loaded": args.load_state,
            "state_saved": args.save_state if state_saved else None,
            "flash_pages_written": [f"0x{i * BootRom.PAGE_SIZE:08X}" for i in sorted(machine.flash_dirty)],
        }, indent=2))
        if args.screenshot:
            devices["display"].save_png(args.screenshot)
        return 0 if stats.stop.kind in ("budget", "stuck") else 1

    print()
    print("=" * 78)
    print("BOOT REPORT")
    print("=" * 78)
    print(f"  stopped: {stats.stop.kind} — {stats.stop.detail}")
    print(f"  at     : {symbols.describe(stats.stop.pc)}")
    if stats.stop.faulting_address is not None:
        print(f"  address: 0x{stats.stop.faulting_address:08X}")
    rate = stats.instructions / stats.wall_seconds / 1000 if stats.wall_seconds else 0
    print(f"  ran    : {stats.instructions:,} instructions in "
          f"{stats.wall_seconds:.2f}s ({rate:,.0f}k instr/s)")
    print()
    if tracer is not None:
        print(tracer.summary())
    print(machine.cortexm.summary())
    for device in devices.values():
        print(device.summary())
    print(missing_images.summary())
    print(format_tasks(machine, symbols))
    print(machine.bootrom.summary())
    print(machine.bus.by_base[0x5000C000].summary())
    if controller is not None:
        if hasattr(controller, "summary"):
            print(controller.summary())
        if android is not None:
            print(f"  Android emulator: {'attached' if android.connected else 'never attached'} "
                  f"on localhost:{android.port}")
        if controller.sent:
            print("  HCI out of the watch:")
            for packet in controller.sent[:10]:
                head = packet[:16].hex(" ")
                tail = f" ... ({len(packet)} bytes)" if len(packet) > 16 else ""
                print(f"    {head}{tail}")
            if len(controller.sent) > 10:
                print(f"    ... and {len(controller.sent) - 10} more")
    print(machine.bus.unknown_report())
    if machine.native_hook is not None:
        print(f"  block hook: native, {machine.native_hook.state.blocks:,} blocks")
    if machine.realtime:
        print(f"  realtime: slept {machine.realtime_slept:.1f}s to stay level with the wall clock")
    if machine.idle_skips:
        print(f"  idle skip: {machine.idle_skips:,} times, "
              f"{machine.idle_instructions_skipped:,} instructions "
              f"({100.0 * machine.idle_instructions_skipped / max(1, stats.instructions):.0f}% "
              f"of the run) not executed")
    print(boot_animation_note(machine))
    if args.screenshot:
        devices["display"].save_png(args.screenshot)
    if console is not None:
        print(console.summary())
    uart = machine.uart_output()
    if uart:
        print("  UART:")
        for line in uart[:20]:
            print(f"    | {line}")
    return 0


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="normwatch", description="Emulator for the Norm 2 smartwatch (Apollo3 Blue)"
    )
    sub = parser.add_subparsers(dest="command", required=True)

    def add_image(p):
        p.add_argument("image", nargs="?", default=str(DEFAULT_IMAGE),
                       help=f"firmware .bin (default: {DEFAULT_IMAGE})")

    p_info = sub.add_parser("info", help="parse and verify the firmware image header")
    add_image(p_info)
    p_info.set_defaults(func=cmd_info)

    p_mod = sub.add_parser("modules", help="list source modules recovered from assert() strings")
    add_image(p_mod)
    p_mod.set_defaults(func=cmd_modules)

    p_boot = sub.add_parser("boot", help="run the firmware and print a boot triage report")
    add_image(p_boot)
    p_boot.add_argument("--seconds", type=float, default=None, metavar="N",
                        help="run for N seconds of watch time (48M instructions each). "
                             "Without it, boot runs until the boot animation is over and "
                             f"the UI is drawn (at most {UNTIL_UI_AT_MOST}s), and a "
                             "--live or --phone session until it is closed. The "
                             "animation lasts about 8s, so a run shorter than that never "
                             "gets to the UI.")
    p_boot.add_argument("--live", action="store_true",
                        help="show the watch's screen in a window while it runs, and "
                             "drive the touch panel from the mouse (click and drag)")
    p_boot.add_argument("--scale", type=int, default=1,
                        help="magnify the live window by this factor (default: 1)")
    p_boot.add_argument("--screenshot", metavar="PATH",
                        help="write the watch's screen to a PNG when the run ends")
    p_boot.add_argument("--phone", type=int, nargs="?", const=8877, default=None,
                        metavar="PORT",
                        help="put the Android emulator's phone on the same air as the "
                             "watch: start the AVD with normphone start --watch, and "
                             ":app in it pairs with the emulated watch. Serves the "
                             "emulator's netsim endpoint on PORT (default 8877). "
                             "Implies --radio and --realtime.")
    p_boot.add_argument("--flash-state", metavar="PATH", default=None,
                        help="keep the watch's flash in PATH between runs: restored "
                             "at start if the file exists, saved at exit (Ctrl-C and "
                             "a closed window included). A watch bound once then "
                             "starts past first-run setup and keeps its bond. "
                             "Refuses a file saved against another image.")
    p_boot.add_argument("--save-state", metavar="PATH", default=None,
                        help="at the end of the run, save the whole machine -- memory, "
                             "CPU, every device -- to PATH. Not with --radio/--phone: "
                             "a bumble radio's state lives outside the machine")
    p_boot.add_argument("--load-state", metavar="PATH", default=None,
                        help="start from a machine saved with --save-state instead of "
                             "booting; build it with the same switches. --seconds, "
                             "--max-instructions and --press times then count from there")
    p_boot.add_argument("--nand", action="append", metavar="PATH",
                        help="a dumped NAND blob to mount, or a `dump -o` directory of "
                             "them (#69). Each goes at the address in its first four "
                             "bytes. Repeatable, and giving any replaces the factory "
                             "resources that would be mounted by default")
    p_boot.add_argument("--no-ble", action="store_true",
                        help="boot without Bluetooth: much faster, for a run that "
                             "is not about the radio. The BLE controller never "
                             "answers its power-up, as before the "
                             "PWRCTRL.DEVPWRSTATUS mapping was fixed. With nothing "
                             "behind the BLEIF FIFO the firmware's HCI transport "
                             "spins in an interrupt-masked retry for ~100M "
                             "instructions and a boot costs about 2.1x; that is "
                             "faithful, so it is the default. It also restores "
                             "idle time, so anything measuring --idle-skip wants it.")
    p_boot.add_argument("--battery", type=float, default=80.0, metavar="PERCENT",
                        help="battery charge the gauge reports (default: 80). "
                             "Below the firmware's threshold the watch raises its "
                             "own low-battery screens.")
    p_boot.add_argument("--charging", action="store_true",
                        help="report the watch as sitting on the charger")
    dev = developer_options(p_boot)
    dev.add_argument("--netsim", dest="phone", type=int, nargs="?", const=8877, default=None,
                     help=argparse.SUPPRESS)          # --phone's old name (#80)
    dev.add_argument("--max-instructions", type=int, default=None, metavar="N",
                     help="the budget in instructions instead of --seconds (48M to a "
                          "second of watch time); it also bounds a --live session")
    dev.add_argument("--stall", type=int, default=3_000_000,
                     help="with --trace: instructions without new code before declaring "
                          "a stall (default 3M)")
    dev.add_argument("--chiprev", type=lambda s: int(s, 0), default=None,
                     help="override MCUCTRL.CHIPREV (e.g. 0x12)")
    dev.add_argument("--resources", default=str(DEFAULT_RESOURCES),
                     help=f"resource blob to mount in the SPI NAND "
                          f"(default: {DEFAULT_RESOURCES})")
    dev.add_argument("--no-factory-resources", action="store_true",
                     help=f"do not mount the watch's own factory resources from "
                          f"{DEFAULT_FACTORY_NAND} (they are mounted when they are "
                          f"there, and a boot that plays the whole animation is ~22s "
                          f"faster without them: 134 full-screen images are really "
                          f"decoded with them, and drawn as \"No data\" without)")
    dev.add_argument("--no-resources", action="store_true",
                     help="leave the SPI NAND erased: no app resource image and no "
                          "factory resources either")
    dev.add_argument("--press", action="append", metavar="PIN[:AT[:HOLD]]",
                     help="inject a button/sensor edge on a GPIO pin, optionally at a "
                          "given instruction count and held for HOLD instructions "
                          "(~48M = 1s of watch time; default hold 3s). Repeatable.")
    dev.add_argument("--charger-status", type=lambda s: int(s, 0), default=None,
                     metavar="BYTE",
                     help="override the charger's status register (0x09 reg 3) "
                          "instead of deriving it from --charging. Bit 5 is what "
                          "the driver's steady query tests, bit 7 raises its "
                          "one-shot code, bit 4 reads as a fault.")
    dev.add_argument("--trace", action="store_true",
                     help="collect coverage and watch for stalls. Costs about "
                          "27%% of the run rate, so it is off by default.")
    dev.add_argument("--no-fast-hook", action="store_true",
                     help="run the per-block timing hook in Python. The C one "
                          "is the default and is about 2.5x faster; it builds "
                          "itself on first use and falls back to Python if "
                          "there is no compiler. --trace and watchpoints need "
                          "per-block Python and switch back on their own.")
    dev.add_argument("--idle-skip", action="store_true",
                     help="fast-forward through the FreeRTOS idle task's "
                          "busy-wait instead of emulating it. About 70%% of a "
                          "boot is spent there and this build never sleeps, so "
                          "this is most of the run time. The emulated clock is "
                          "unaffected — every skipped cycle is still counted, "
                          "and exception counts match exactly with it on and "
                          "off — but it is off by default because it reasons "
                          "about when the core has nothing to do rather than "
                          "executing it.")
    dev.add_argument("--fixed-quantum", action="store_true",
                     help="advance the clocks on a fixed 256-cycle grid "
                          "instead of running to the next deadline. The "
                          "deadline is the default and is about 1.7x faster; "
                          "this is here to reproduce a measurement taken "
                          "before it, or to check one against it.")
    dev.add_argument("--ble-controller", action="store_true",
                     help="put the NZ8801 stand-in on the other side of the "
                          "BLEIF FIFO: it takes the firmware download, answers "
                          "the vendor init and every HCI command with a bare "
                          "Command Complete, and captures the packets. Enough "
                          "for the firmware's stack to finish starting; it is "
                          "not a radio. --radio is.")
    dev.add_argument("--radio", action="store_true",
                     help="put a real (virtual) radio behind the BLEIF: a bumble "
                          "controller on a local link, so the firmware's own BLE "
                          "stack comes all the way up and advertises as the "
                          "watch. Alone on the air unless --phone is given.")
    dev.add_argument("--address", default=WATCH, metavar="MAC",
                     help="the BD address the radio reports (default: the physical "
                          "watch's, so the emulated one looks the same to the app)")
    dev.add_argument("--hci-trace", action="store_true",
                     help="with --radio/--phone, log every HCI packet across the "
                          "seam once the link is up (commands, events, ACL data "
                          "both ways). The first thing to turn on when the phone "
                          "and the watch disagree about what was said.")
    dev.add_argument("--realtime", action="store_true",
                     help="never let watch time run ahead of wall time. Needed "
                          "whenever something outside keeps real time -- a phone on "
                          "the radio -- and on by default with --phone.")
    dev.add_argument("--json", action="store_true", help="machine-readable output")
    p_boot.set_defaults(func=cmd_boot)

    p_cmd = sub.add_parser(
        "cmd", help="boot the watch (or, with --mac, reach the physical one), connect a "
                    "phone the way the app does, send one 0x6F command and print every reply")
    p_cmd.add_argument("cmd_code", metavar="CMD",
                       help="command byte in hex (08) or a CommandCode name (BATTERY_POWER)")
    p_cmd.add_argument("action", metavar="ACTION",
                       help="action byte in hex (70, 71) or an Action name (CHECK, SET)")
    p_cmd.add_argument("--payload", default=None, metavar="HEX",
                       help="payload in hex, e.g. 06 or '0a 0b'. Default 00 for a CHECK, "
                            "which needs its one byte -- the watch ignores a CHECK without "
                            "it (docs/protocol.md) -- and nothing for anything else")
    p_cmd.add_argument("--mac", metavar="MAC", nargs="?", const=WATCH, default=None,
                       help=f"ask the PHYSICAL watch over this PC's Bluetooth adapter "
                            f"instead of booting the emulated one: {WATCH}, or the one at "
                            f"MAC. Needs bleak; on Windows it bonds the watch once (Just "
                            f"Works). The watch must not be connected to a phone")
    p_cmd.add_argument("--flash-state", metavar="PATH", default=None,
                       help="start from this saved flash (see boot --flash-state). Read "
                            "only: probing never rewrites it unless --save is given")
    p_cmd.add_argument("--save", action="store_true",
                       help="write the flash back to --flash-state at exit, creating it -- "
                            "the bind, this phone's bond, and whatever the command changed")
    dev = developer_options(p_cmd)
    dev.add_argument("--char", choices=["8001", "8003"], default="8001",
                     help="the characteristic to write: 8001 is what the companion app "
                          "and :app use (SETs are acknowledged); on 8003 a SET never "
                          "is. Default 8001")
    dev.add_argument("--no-trigger", action="store_true",
                     help="do not write [03] to 8002 after the frame")
    dev.add_argument("--no-bind", action="store_true",
                     help="skip checkInit and the bind: ask the watch as first-run "
                          "setup leaves it")
    dev.add_argument("--timeout", type=float, default=5.0, metavar="S",
                     help="seconds to wait for replies (default 5)")
    dev.add_argument("--image", default=str(DEFAULT_IMAGE),
                     help=f"firmware .bin (default: {DEFAULT_IMAGE})")
    dev.add_argument("--resources", default=str(DEFAULT_RESOURCES),
                     help="resource blob loaded into the emulated NAND")
    dev.add_argument("--address", default=WATCH, metavar="MAC",
                     help="the emulated watch's BD address (default: the physical "
                          "watch's)")
    p_cmd.set_defaults(func=cmd_command)

    p_dump = sub.add_parser(
        "dump", help="read a range of the watch's SPI NAND out through the patched 0xEE "
                     "(#68); resumable, so stopping it costs one page")
    p_dump.add_argument("-o", "--output", required=True, metavar="DIR",
                        help="directory for the dump: one 0x<start>.bin per range, in the "
                             "resource-blob format `boot --nand` mounts, and a manifest. "
                             "An existing dump is continued")
    p_dump.add_argument("--range", action="append", required=True, metavar="START-END",
                        help="a NAND range in hex, e.g. 026DA430-0496C000 (rounded out to "
                             "whole 2 KB pages). Repeatable. The physical watch's whole chip "
                             "has been read (#83): what it holds is in NORM/_nand and "
                             "docs/firmware.md")
    p_dump.add_argument("--mac", metavar="MAC", nargs="?", const=WATCH, default=None,
                        help=f"dump the PHYSICAL watch instead -- {WATCH}, or the one at "
                             f"MAC -- over this PC's Bluetooth adapter. It must already be "
                             f"running a patched image, which is #70 -- untried")
    p_dump.add_argument("--flash-state", metavar="PATH", default=None,
                        help="start the emulated watch from this saved flash, read only")
    p_dump.add_argument("--image", default=str(DEFAULT_IMAGE),
                        help="firmware .bin for the emulated watch: a PATCHED one "
                             "(normfw patch-nand), or nothing will answer")
    dev = developer_options(p_dump)
    dev.add_argument("--pages", type=int, default=None, metavar="N",
                     help="stop after N pages (a sample, or a measurement)")
    dev.add_argument("--tries", type=int, default=4, metavar="N",
                     help="attempts for a read that gets no reply at all (default 4). "
                          "Silence means the link has gone and each attempt costs a "
                          "timeout, so it is better to end the session and reconnect")
    dev.add_argument("--refusals", type=int, default=12, metavar="N",
                     help="attempts for a read the watch answered to say it could not "
                          "do it (default 12). That is the UI holding the driver's "
                          "lock, it arrives in runs -- four in a row ended a session "
                          "at 4 -- and an attempt costs one read, so it is cheap (#70)")
    dev.add_argument("--retry-pause", type=float, default=0.05, metavar="S",
                     help="seconds before asking again for a read the watch said it "
                          "could not do (default 0.05), so the retry lands in a "
                          "different frame than the redraw that lost it")
    dev.add_argument("--no-skip", action="store_true",
                     help="read every chunk instead of skipping a page whose first and "
                          "last chunk are both erased")
    dev.add_argument("--keep-awake", type=float, default=10.0, metavar="S",
                     help="seconds between nudges that keep the watch awake (default "
                          "10). It drops the link when it thinks it is idle, and "
                          "reading its NAND does not count as activity (#70)")
    dev.add_argument("--no-wake", action="store_true",
                     help="do not push a message count to bring the storage stack up "
                          "when a read says it is down (#70). Without it, a stall waits "
                          "for the watch to be used by hand")
    dev.add_argument("--reconnect-wait", type=float, default=30.0, metavar="S",
                     help="seconds to leave the link alone before reconnecting "
                          "(default 30). Reconnecting faster than this leaves stale "
                          "links on this PC and the watch stops advertising at all")
    dev.add_argument("--sessions", type=int, default=400, metavar="N",
                     help="how many times to reconnect and go on after the link drops "
                          "(default 400). Each session continues from the manifest; this "
                          "PC's Bluetooth stack drops a long dump fairly often")
    dev.add_argument("--wait-for-driver", type=float, default=600.0, metavar="S",
                     help="seconds to wait for the storage stack when it is found down, "
                          "before giving up (default 600). It is up while the watch is "
                          "being used and goes down when it idles (#70)")
    dev.add_argument("--no-trigger", action="store_true",
                     help="do not write [03] to 8002 after each request. The emulated "
                          "watch answers 0xEE without it, saving a write a read; the "
                          "physical one has only been asked with it")
    dev.add_argument("--timeout", type=float, default=36000.0, metavar="S",
                     help="seconds the emulated watch may run (default 10 h)")
    dev.add_argument("--resources", default=str(DEFAULT_RESOURCES),
                     help="resource blob loaded into the emulated NAND")
    dev.add_argument("--address", default=WATCH, metavar="MAC",
                     help="the emulated watch's BD address")
    p_dump.set_defaults(func=cmd_dump)

    p_ota = sub.add_parser(
        "ota", help="send a firmware update to a watch: the emulated one, or the physical "
                    "one with --mac. A main-MCU update needs --allow-mcu")
    p_ota.add_argument("update", metavar="FILE",
                       help="the update .bin, sealed (`normfw verify` it first)")
    p_ota.add_argument("--type", type=int, default=4, choices=[1, 2, 3, 4],
                       help="update type: 4 the resource partition (default), 1 the main "
                            "MCU, 2 the touch panel, 3 the heart-rate sensor")
    p_ota.add_argument("--allow-mcu", action="store_true",
                       help="permit a type-1 (main MCU) update -- only with the owner's "
                            "go-ahead for that flash. It replaces the code that receives "
                            "updates, so an image that does not boot leaves no way back "
                            "without SWD (#14)")
    p_ota.add_argument("--mac", metavar="MAC", nargs="?", const=WATCH, default=None,
                       help=f"send to the PHYSICAL watch -- {WATCH}, or the one at MAC -- "
                            f"over this PC's Bluetooth adapter. It must not be connected "
                            f"to a phone")
    p_ota.add_argument("--flash-state", metavar="PATH", default=None,
                       help="start the emulated watch from this saved flash")
    p_ota.add_argument("--save", action="store_true",
                       help="write the emulated watch's flash back to --flash-state")
    dev = developer_options(p_ota)
    dev.add_argument("--progress-every", type=int, default=200, metavar="N",
                     help="print progress every N pieces (default 200)")
    dev.add_argument("--timeout", type=float, default=3600.0, metavar="S",
                     help="seconds the emulated watch may run (default 1 h)")
    dev.add_argument("--image", default=str(DEFAULT_IMAGE),
                     help="firmware the EMULATED watch runs (not the update)")
    dev.add_argument("--resources", default=str(DEFAULT_RESOURCES),
                     help="resource blob loaded into the emulated NAND")
    dev.add_argument("--address", default=WATCH, metavar="MAC",
                     help="the emulated watch's BD address")
    p_ota.set_defaults(func=cmd_ota)

    args = parser.parse_args(argv)
    if args.command == "boot":
        args.until_ui = False
        if args.max_instructions is not None:
            pass
        elif args.live:
            # A live session runs until the window is closed, not for a fixed budget.
            args.max_instructions = 1 << 62
        elif args.seconds:
            args.max_instructions = int(args.seconds * Apollo3Machine.CYCLES_PER_SECOND)
        elif args.phone is not None:
            # Nor does one with a phone on the other end: it ends with Ctrl-C.
            args.max_instructions = 1 << 62
        else:
            args.until_ui = True
            args.max_instructions = UNTIL_UI_AT_MOST * Apollo3Machine.CYCLES_PER_SECOND
    return args.func(args)


def cmd_main(argv=None) -> int:
    """``normcmd``: the same as ``normwatch cmd``."""
    return main(["cmd", *(sys.argv[1:] if argv is None else argv)])


if __name__ == "__main__":
    raise SystemExit(main())
