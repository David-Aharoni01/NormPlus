"""``normphone``: the Android emulator that runs ``:app``, and its Bluetooth.

    normphone setup                 one-time: SDK packages, the accelerator, the Pixel 8 AVD
    normphone start                 the AVD, Bluetooth through the USB dongle (starts the bridge)
    normphone start --watch         the AVD, Bluetooth to the emulated watch (normwatch boot --netsim)
    normphone start --no-bridge     the AVD with no Bluetooth
    normphone install               build :app and install it on the running AVD
    normphone clear-bond            forget the watch's pairing on the AVD (after switching watches)
    normphone bridge                the dongle bridge on its own

The AVD is ``Pixel_8_API35``; the SDK is found from ``--sdk``, ``ANDROID_HOME``,
``ANDROID_SDK_ROOT`` or ``local.properties``. See docs/android-emulator.md.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

from .. import developer_options, paths

AVD = "Pixel_8_API35"
SYSTEM_IMAGE = "system-images;android-35;google_apis;x86_64"
#: The port the AVD's packet streamer connects to: the dongle bridge, or the watch
#: emulator (``normwatch boot --netsim``).
PORT = 8877
#: The CSR8510 dongle, bound to WinUSB with Zadig.
USB = "usb:0A12:0001"
#: The physical watch's address, which the emulated watch uses too.
WATCH = "4C:59:80:12:44:F1"
#: Where Android keeps its Bluetooth bonds (the AVD runs as root with `adb root`).
BT_CONFIG = "/data/misc/bluedroid/bt_config.conf"
CONFIG_SNAPSHOT = Path(__file__).with_name("Pixel_8_API35.config.ini")
WINDOWS = sys.platform == "win32"


# -- finding things ------------------------------------------------------------------------

def find_sdk(explicit: str | None = None) -> Path:
    """The Android SDK: --sdk, ANDROID_HOME, ANDROID_SDK_ROOT, local.properties, the default."""
    candidates = [explicit, os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    props = paths.REPO / "local.properties"
    if props.is_file():
        m = re.search(r"^sdk\.dir=(.*)$", props.read_text(encoding="utf-8"), re.M)
        if m:
            candidates.append(m.group(1).strip().replace("\\\\", "\\").replace("\\:", ":"))
    home = Path.home()
    candidates += [str(home / "AppData/Local/Android/Sdk"), str(home / "Android/Sdk")]
    for c in candidates:
        if c and Path(c).is_dir():
            return Path(c)
    raise SystemExit("normphone: no Android SDK found -- pass --sdk or set ANDROID_HOME")


def tool(sdk: Path, *parts: str) -> Path:
    path = sdk.joinpath(*parts)
    if WINDOWS and not path.suffix:
        for ext in (".exe", ".bat"):
            if path.with_suffix(ext).exists():
                return path.with_suffix(ext)
    return path


def sdk_env(sdk: Path) -> dict:
    env = dict(os.environ, ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk))
    env["PATH"] = os.pathsep.join([str(sdk / "emulator"), str(sdk / "platform-tools"),
                                   str(sdk / "cmdline-tools" / "latest" / "bin"), env.get("PATH", "")])
    return env


def listening(port: int) -> bool:
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def adb(sdk: Path, *args: str, check: bool = True, input: str | None = None) -> str:
    run = subprocess.run([str(tool(sdk, "platform-tools", "adb")), *args], capture_output=True,
                         text=True, input=input, env=sdk_env(sdk))
    if check and run.returncode != 0:
        raise SystemExit(f"normphone: adb {' '.join(args)} failed: {(run.stderr or run.stdout).strip()}")
    return run.stdout


# -- setup -----------------------------------------------------------------------------------

def cmd_setup(args) -> int:
    """Everything outside the repository the AVD needs. Safe to run again."""
    sdk = find_sdk(args.sdk)
    env = sdk_env(sdk)
    sdkmanager = tool(sdk, "cmdline-tools", "latest", "bin", "sdkmanager")
    avdmanager = tool(sdk, "cmdline-tools", "latest", "bin", "avdmanager")
    emulator = tool(sdk, "emulator", "emulator")
    if not sdkmanager.exists():
        raise SystemExit(f"normphone: no sdkmanager at {sdkmanager} -- install the SDK "
                         "command-line tools first (and set JAVA_HOME to a JDK 17)")

    print("[1/4] SDK packages: emulator, system image, platform-tools, the AEHD driver")
    subprocess.run([str(sdkmanager), "--licenses"], input="y\n" * 20, text=True, env=env,
                   stdout=subprocess.DEVNULL)
    subprocess.run([str(sdkmanager), "emulator", SYSTEM_IMAGE, "platform-tools",
                    "extras;google;Android_Emulator_Hypervisor_Driver"], env=env, check=True)

    if args.skip_aehd:
        print("[2/4] Skipping the accelerator (--skip-aehd)")
    elif subprocess.run([str(emulator), "-accel-check"], capture_output=True, env=env).returncode == 0:
        print("[2/4] Hardware acceleration is already available")
    else:
        print("[2/4] Installing the AEHD accelerator -- approve the administrator prompt")
        bat = sdk / "extras/google/Android_Emulator_Hypervisor_Driver/silent_install.bat"
        subprocess.run(["powershell", "-NoProfile", "-Command",
                        f"Start-Process cmd.exe -ArgumentList '/c','\"{bat}\"' -Verb RunAs -Wait"],
                       check=False)
        subprocess.run([str(emulator), "-accel-check"], env=env)

    avd_dir = Path.home() / ".android" / "avd" / f"{AVD}.avd"
    if avd_dir.exists():
        print(f"[3/4] The AVD {AVD} already exists -- leaving it as it is")
    else:
        print(f"[3/4] Creating the AVD {AVD} (Pixel 8, {SYSTEM_IMAGE})")
        subprocess.run([str(avdmanager), "create", "avd", "-n", AVD, "-k", SYSTEM_IMAGE,
                        "-d", "pixel_8", "--force"], input="no\n", text=True, env=env, check=True)
        shutil.copyfile(CONFIG_SNAPSHOT, avd_dir / "config.ini")
        print("      applied the committed config.ini")

    print("[4/4] Done. One manual step remains for Bluetooth to the PHYSICAL watch:\n"
          "      plug in the USB dongle and run Zadig (https://zadig.akeo.ie/): Options > List\n"
          "      All Devices > 'CSR8510 A10' (USB ID 0A12 0001) > WinUSB > Replace Driver.\n"
          "      Then: normphone start              (the physical watch, through the dongle)\n"
          "            normphone start --watch      (the emulated watch)")
    return 0


# -- start -----------------------------------------------------------------------------------

def cmd_start(args) -> int:
    """The AVD, in the foreground; Ctrl-C or closing it stops it (and a bridge it started)."""
    sdk = find_sdk(args.sdk)
    env = sdk_env(sdk)
    emulator = tool(sdk, "emulator", "emulator")
    if not emulator.exists():
        raise SystemExit(f"normphone: no emulator at {emulator} -- run `normphone setup`")
    if subprocess.run([str(emulator), "-accel-check"], capture_output=True, env=env).returncode != 0:
        print("normphone: hardware acceleration is not available; run `normphone setup`",
              file=sys.stderr)

    command = [str(emulator), "-avd", args.avd, "-gpu", args.gpu]
    if args.cold_boot:
        command += ["-no-snapshot", "-wipe-data"]
    bridge = None
    if args.no_bridge:
        print("Starting with no Bluetooth (--no-bridge)")
    else:
        if args.watch:
            if not listening(args.port):
                raise SystemExit(f"normphone: nothing is listening on port {args.port}. Start the "
                                 f"emulated watch first:\n  normwatch boot --netsim --live")
            print(f"Bluetooth to the emulated watch on port {args.port}")
        elif listening(args.port):
            print(f"Reusing the bridge already listening on port {args.port}")
        else:
            print(f"Starting the dongle bridge on port {args.port} ({args.usb})")
            bridge = subprocess.Popen([sys.executable, "-m", "normplus.phone.bridge",
                                       "--port", str(args.port), "--usb", args.usb])
            for _ in range(20):
                if listening(args.port) or bridge.poll() is not None:
                    break
                time.sleep(0.5)
            if not listening(args.port):
                print("normphone: the bridge did not come up -- is the dongle plugged in and bound "
                      "to WinUSB with Zadig? The AVD will start without Bluetooth.", file=sys.stderr)
        command += ["-packet-streamer-endpoint", f"localhost:{args.port}",
                    "-writable-system", "-no-snapshot-load"]
    print("Launching:", " ".join(command[1:]))
    try:
        return subprocess.run(command, env=env).returncode
    except KeyboardInterrupt:
        return 130
    finally:
        if bridge is not None and bridge.poll() is None:
            bridge.terminate()


# -- install / clear-bond / bridge -------------------------------------------------------------

def cmd_install(args) -> int:
    """`gradlew installDebug`: the debug build -- the release build is unsigned."""
    gradlew = paths.REPO / ("gradlew.bat" if WINDOWS else "gradlew")
    return subprocess.run([str(gradlew), "installDebug"], cwd=paths.REPO,
                          env=sdk_env(find_sdk(args.sdk))).returncode


def cmd_clear_bond(args) -> int:
    """Remove the AVD's bond with the watch, so it pairs afresh.

    Needed whenever the watch on the other end does not hold that bond: a new emulated
    watch, a different state file, or a switch between the emulated and physical watch.
    Android keeps the bond as a ``[aa:bb:...]`` section of bt_config.conf; with Bluetooth
    off, that section is deleted, then Bluetooth comes back. The next connection makes
    Android ask "Pair with Norm2#00000?" -- tap Pair within 30 seconds.
    """
    sdk = find_sdk(args.sdk)
    mac = args.mac.lower()
    if "device" not in adb(sdk, "get-state", check=False):
        raise SystemExit("normphone: no running AVD -- `normphone start` first")
    adb(sdk, "root", check=False)
    adb(sdk, "wait-for-device")
    adb(sdk, "shell", "svc bluetooth disable")
    time.sleep(3)
    heads = adb(sdk, "shell", f"grep -n '^\\[' {BT_CONFIG}", check=False).split()
    sections = [(int(h.split(":", 1)[0]), h.split(":", 1)[1]) for h in heads if ":" in h]
    removed = False
    for i, (line, name) in enumerate(sections):
        if name.strip("[]").lower() == mac:
            end = f"{sections[i + 1][0] - 1}" if i + 1 < len(sections) else "$"
            adb(sdk, "shell", f"sed -i '{line},{end}d' {BT_CONFIG}")
            removed = True
    adb(sdk, "shell", "svc bluetooth enable")
    print(f"Removed the AVD's bond with {args.mac}." if removed else
          f"The AVD had no bond with {args.mac}.")
    return 0


def cmd_bridge(args) -> int:
    from . import bridge
    bridge.main(["--port", str(args.port), "--usb", args.usb])
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="normphone", description=__doc__.split("\n\n")[0].replace("``", ""),
                                 epilog=__doc__.split("\n\n", 1)[1].replace("``", ""),
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    developer_options(ap).add_argument(
        "--sdk", help="the Android SDK (default: ANDROID_HOME, local.properties)")
    sub = ap.add_subparsers(dest="command", required=True)

    p = sub.add_parser("setup", help="one-time setup of the SDK packages, accelerator and AVD")
    developer_options(p).add_argument("--skip-aehd", action="store_true",
                                      help="skip the (elevated) accelerator install")
    p.set_defaults(func=cmd_setup)

    p = sub.add_parser("start", help="start the AVD (and the dongle bridge)")
    mode = p.add_mutually_exclusive_group()
    mode.add_argument("--watch", action="store_true",
                      help="Bluetooth to the emulated watch: start `normwatch boot --netsim` first")
    mode.add_argument("--no-bridge", action="store_true", help="no Bluetooth at all")
    p.add_argument("--cold-boot", action="store_true",
                   help="no snapshot, wipe data (after config changes, or when the guest's "
                        "Bluetooth will not turn on)")
    p.add_argument("--gpu", default="auto", choices=["auto", "host", "swiftshader_indirect"],
                   help="how the AVD renders (default auto); swiftshader_indirect is software, "
                        "for when the GPU path glitches")
    dev = developer_options(p)
    dev.add_argument("--avd", default=AVD, help=f"the AVD to start (default {AVD})")
    dev.add_argument("--port", type=int, default=PORT,
                     help=f"the port the AVD's Bluetooth connects to: the dongle bridge, or "
                          f"the watch emulator with --watch (default {PORT})")
    dev.add_argument("--usb", default=USB, help=f"the dongle (default {USB})")
    p.set_defaults(func=cmd_start)

    p = sub.add_parser("install", help="build :app (debug) and install it on the running AVD")
    p.set_defaults(func=cmd_install)

    p = sub.add_parser("clear-bond", help="forget the watch's pairing on the AVD")
    developer_options(p).add_argument("--mac", default=WATCH,
                                      help=f"the watch's address (default {WATCH})")
    p.set_defaults(func=cmd_clear_bond)

    p = sub.add_parser("bridge", help="run the dongle bridge on its own")
    dev = developer_options(p)
    dev.add_argument("--port", type=int, default=PORT, help=f"the port to serve (default {PORT})")
    dev.add_argument("--usb", default=USB, help=f"the dongle (default {USB})")
    p.set_defaults(func=cmd_bridge)

    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
