"""Command line for the Norm 2 watch emulator.

    py -3.11 -m normwatch info  NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin
    py -3.11 -m normwatch boot  NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin
    py -3.11 -m normwatch modules NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from .fw import flashstate
from .fw.bootrom import BootRom
from .fw import image as image_mod
from .fw.console import FirmwareConsole, find_formatter
from .fw.devices import (attach_ble_controller, attach_mspi_devices,
                         attach_motion_sensor, attach_pmu, attach_touch_panel)
from .fw.machine import Apollo3Machine
from .fw.patches import force_gestures
from .fw.rtos import format_tasks
from .fw.symbols import SymbolMap
from .fw.trace import Tracer

DEFAULT_IMAGE = Path("NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin")
DEFAULT_RESOURCES = Path("NORM/assets/Picture_P03B_NORM2_0.4.bin")


def _load(path: str | Path) -> image_mod.FirmwareImage:
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


#: Keys 1-5 in the live window drive the GPIO pins the firmware enables interrupts
#: on (2, 3, 10, 16, 38), minus 28 which is the touch panel. Pin 3 is the button:
#: it is the only one of the six configured for the rising edge, and pressing it
#: is what wakes key_irq.c, gpio_irq.c, the vibrator task and the power-off dialog.
#: Pin 16 looks like the accelerometer -- pressing it doubles system_step_task.c's
#: coverage and adds I2C traffic. Pins 2, 10 and 38 are still unidentified.
LIVE_KEYS = {"1": 2, "2": 3, "3": 10, "4": 16, "5": 38}


def _run_live(machine, devices, args, *, log=print):
    """Run the firmware with its screen in a window, the mouse driving touch."""
    import threading

    from .fw.viewer import WatchWindow

    result = {}

    def worker() -> None:
        try:
            result["stats"] = machine.run(
                max_instructions=args.max_instructions, slice_size=args.slice
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


def boot_animation_note(machine) -> str:
    try:
        total = machine.uc.mem_read(BOOT_ANIMATION_STATE + 0x04, 1)[0]
        frame = int.from_bytes(
            machine.uc.mem_read(BOOT_ANIMATION_STATE + 0x10, 4), "little")
    except Exception:
        return ""
    if not total or frame > total:
        return ""
    if frame >= total:
        return f"  boot animation: finished ({total} frames)"
    per_frame = 2_950_000
    needed = machine._instructions + (total - frame) * per_frame
    return (f"  boot animation: still playing, frame {frame} of {total} — the UI is "
            f"behind it.\n"
            f"                  Try --seconds {needed / 48_000_000:.0f} "
            f"(about {needed:,} instructions).")


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
    tracing = args.trace and not args.no_trace
    tracer = Tracer(
        symbols, stall_window=args.stall, watch_for_stall=not args.live, log=log
    ) if tracing else None
    # A phone on the other end of the radio keeps real time, so the watch
    # must too; --netsim implies it.
    realtime = args.realtime or args.netsim is not None
    machine = Apollo3Machine(img, log=log, trace=tracer, chiprev=args.chiprev,
                             idle_skip=args.idle_skip,
                             fast_hook=not args.no_fast_hook,
                             deadline_quantum=not args.fixed_quantum,
                             ble=not args.no_ble, realtime=realtime)
    resources = None if args.no_resources else Path(args.resources)
    devices = attach_mspi_devices(machine, resource_blob=resources, log=log)
    # Before anything runs and before any patch: the restored flash is the
    # watch as it was left, and a patch goes on top of it, never into it.
    flash_state = Path(args.flash_state) if args.flash_state else None
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
    if (args.radio or args.netsim is not None) and not args.no_ble:
        # Imported here: bumble costs a third of a second to load and only
        # these two switches need it.
        from .fw.blelink import AndroidLink, BumbleController, Radio

        radio = Radio()
        controller = attach_ble_controller(
            machine, BumbleController(args.address, radio=radio, log=log), log=log)
        controller.trace = args.hci_trace
        if args.netsim is not None:
            android = AndroidLink(radio, args.netsim, log=log)
            if not quiet:
                print(f"  Android emulator: launch-emulator.ps1 -Watch "
                      f"(-BridgePort {args.netsim} if not the default)")
    elif args.ble_controller and not args.no_ble:
        controller = attach_ble_controller(machine, log=log)
    if args.force_gestures:
        force_gestures(machine, log=print if quiet else log)

    console = None
    site = find_formatter(img.payload, img.link_address)
    if site is not None:
        console = FirmwareConsole(machine, site, log=log)
    elif not quiet:
        print("  note: the firmware's formatter was not found — asserts will be silent")

    for spec in args.press or []:
        parts = spec.split(":")
        pin = int(parts[0], 0)
        at = int(parts[1], 0) if len(parts) > 1 and parts[1] else args.max_instructions // 4
        hold = int(parts[2], 0) if len(parts) > 2 and parts[2] else None
        machine.press_button(pin, at, hold)

    try:
        if args.live:
            stats = _run_live(machine, devices, args, log=log)
        else:
            stats = machine.run(max_instructions=args.max_instructions, slice_size=args.slice)
    finally:
        # Also on Ctrl-C and a closed window: that is how --netsim sessions
        # end. Stopping between an erase and its program loses that page,
        # as pulling the battery at that instant would on the watch.
        if flash_state is not None:
            saved = flashstate.save(flash_state, machine, devices["nand"])
            log(f"  [flash] saved {len(saved['flash_pages'])} flash pages and "
                f"{len(saved['nand_pages'])} NAND pages to {flash_state}")

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
    p_boot.add_argument("--max-instructions", type=int, default=30_000_000,
                        help="instruction budget (default: 30M, which is 0.6s of "
                             "watch time — see --seconds)")
    p_boot.add_argument("--seconds", type=float, default=None, metavar="N",
                        help="run for N seconds of watch time instead "
                             "(48M instructions each). The watch plays a 134-frame "
                             "boot animation lasting about 8s, so anything shorter "
                             "than that never gets to the UI.")
    p_boot.add_argument("--slice", type=int, default=25_000,
                        help="instructions between timer/interrupt checks (default: 25000)")
    p_boot.add_argument("--stall", type=int, default=3_000_000,
                        help="instructions without new code before declaring a stall")
    p_boot.add_argument("--chiprev", type=lambda s: int(s, 0), default=None,
                        help="override MCUCTRL.CHIPREV (e.g. 0x12)")
    p_boot.add_argument("--resources", default=str(DEFAULT_RESOURCES),
                        help=f"resource blob to mount in the SPI NAND "
                             f"(default: {DEFAULT_RESOURCES})")
    p_boot.add_argument("--no-resources", action="store_true",
                        help="leave the SPI NAND erased")
    p_boot.add_argument("--press", action="append", metavar="PIN[:AT[:HOLD]]",
                        help="inject a button/sensor edge on a GPIO pin, optionally at a "
                             "given instruction count and held for HOLD instructions "
                             "(~48M = 1s of watch time; default hold 3s). Repeatable.")
    p_boot.add_argument("--screenshot", metavar="PATH",
                        help="write the watch's screen to a PNG when the run ends")
    p_boot.add_argument("--live", action="store_true",
                        help="show the watch's screen in a window while it runs, and "
                             "drive the touch panel from the mouse (click and drag)")
    p_boot.add_argument("--scale", type=int, default=1,
                        help="magnify the live window by this factor (default: 1)")
    p_boot.add_argument("--battery", type=float, default=80.0, metavar="PERCENT",
                        help="battery charge the gauge reports (default: 80). "
                             "Below the firmware's threshold the watch raises its "
                             "own low-battery screens.")
    p_boot.add_argument("--charging", action="store_true",
                        help="report the watch as sitting on the charger")
    p_boot.add_argument("--charger-status", type=lambda s: int(s, 0), default=None,
                        metavar="BYTE",
                        help="override the charger's status register (0x09 reg 3) "
                             "instead of deriving it from --charging. Bit 5 is what "
                             "the driver's steady query tests, bit 7 raises its "
                             "one-shot code, bit 4 reads as a fault.")
    p_boot.add_argument("--force-gestures", action="store_true",
                        help="suppress the firmware's own gesture cancel so swipes reach "
                             "the UI. A deliberate deviation from the shipped image — see "
                             "normwatch/fw/patches.py")
    p_boot.add_argument("--trace", action="store_true",
                        help="collect coverage and watch for stalls. Costs about "
                             "27%% of the run rate, so it is off by default.")
    p_boot.add_argument("--no-trace", action="store_true",
                        help=argparse.SUPPRESS)      # now the default; kept working
    p_boot.add_argument("--no-fast-hook", action="store_true",
                        help="run the per-block timing hook in Python. The C one "
                             "is the default and is about 2.5x faster; it builds "
                             "itself on first use and falls back to Python if "
                             "there is no compiler. --trace and watchpoints need "
                             "per-block Python and switch back on their own.")
    p_boot.add_argument("--idle-skip", action="store_true",
                        help="fast-forward through the FreeRTOS idle task's "
                             "busy-wait instead of emulating it. About 70%% of a "
                             "boot is spent there and this build never sleeps, so "
                             "this is most of the run time. The emulated clock is "
                             "unaffected — every skipped cycle is still counted, "
                             "and exception counts match exactly with it on and "
                             "off — but it is off by default because it reasons "
                             "about when the core has nothing to do rather than "
                             "executing it.")
    p_boot.add_argument("--fixed-quantum", action="store_true",
                        help="advance the clocks on a fixed 256-cycle grid "
                             "instead of running to the next deadline. The "
                             "deadline is the default and is about 1.7x faster; "
                             "this is here to reproduce a measurement taken "
                             "before it, or to check one against it.")
    p_boot.add_argument("--no-ble", action="store_true",
                        help="stop the BLE controller from answering its "
                             "power-up, the way this emulator behaved before the "
                             "PWRCTRL.DEVPWRSTATUS mapping was fixed. Nothing is "
                             "yet behind the BLEIF FIFO, so with the radio on the "
                             "firmware's HCI transport spins in an "
                             "interrupt-masked retry for ~100M instructions and a "
                             "boot costs about 2.1x. That is faithful and it is "
                             "the default; this is for when the run is not about "
                             "the radio. It also restores idle time, so anything "
                             "measuring --idle-skip wants it.")
    p_boot.add_argument("--ble-controller", action="store_true",
                        help="put the NZ8801 stand-in on the other side of the "
                             "BLEIF FIFO: it takes the firmware download, answers "
                             "the vendor init and every HCI command with a bare "
                             "Command Complete, and captures the packets. Enough "
                             "for the firmware's stack to finish starting; it is "
                             "not a radio. --radio is.")
    p_boot.add_argument("--radio", action="store_true",
                        help="put a real (virtual) radio behind the BLEIF: a bumble "
                             "controller on a local link, so the firmware's own BLE "
                             "stack comes all the way up and advertises as the "
                             "watch. Alone on the air unless --netsim is given.")
    p_boot.add_argument("--netsim", type=int, nargs="?", const=8877, default=None,
                        metavar="PORT",
                        help="also serve the Android emulator's netsim endpoint on "
                             "PORT (default 8877), with the phone's virtual controller "
                             "on the same air as the watch's -- launch the AVD with "
                             "launch-emulator.ps1 -Watch and :app can pair with the "
                             "emulated watch. Implies --radio and --realtime.")
    p_boot.add_argument("--address", default="4C:59:80:12:44:F1", metavar="MAC",
                        help="the BD address the radio reports (default: the physical "
                             "watch's, so the emulated one looks the same to the app)")
    p_boot.add_argument("--hci-trace", action="store_true",
                        help="with --radio/--netsim, log every HCI packet across the "
                             "seam once the link is up (commands, events, ACL data "
                             "both ways). The first thing to turn on when the phone "
                             "and the watch disagree about what was said.")
    p_boot.add_argument("--realtime", action="store_true",
                        help="never let watch time run ahead of wall time. Needed "
                             "whenever something outside keeps real time -- a phone on "
                             "the radio -- and on by default with --netsim.")
    p_boot.add_argument("--flash-state", metavar="PATH", default=None,
                        help="keep the watch's flash in PATH between runs: restored "
                             "at start if the file exists, saved at exit (Ctrl-C and "
                             "a closed window included). A watch bound once then "
                             "starts past first-run setup and keeps its bond. "
                             "Refuses a file saved against another image.")
    p_boot.add_argument("--json", action="store_true", help="machine-readable output")
    p_boot.set_defaults(func=cmd_boot)

    args = parser.parse_args(argv)
    if getattr(args, "seconds", None):
        args.max_instructions = int(args.seconds * Apollo3Machine.CYCLES_PER_SECOND)
    if getattr(args, "live", False) and "--max-instructions" not in (argv or sys.argv[1:]):
        # A live session runs until the window is closed, not for a fixed budget.
        args.max_instructions = 1 << 62
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
