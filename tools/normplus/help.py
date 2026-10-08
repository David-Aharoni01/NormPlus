"""``normhelp``: what every NormPlus command is for, and how to run it.

    normhelp                       the overview: every command, the common recipes
    normhelp watch                 a command's own help (normwatch --help)
    normhelp watch boot            a subcommand's help (normwatch boot --help)
"""

from __future__ import annotations

import importlib
import sys
import textwrap

#: name, entry point, what it is for, the lines people actually run.
TOOLS = [
    ("normwatch", "normplus.watch.__main__:main",
     "The watch emulator: runs the watch's own firmware on this PC.",
     ["normwatch boot --live                       the watch's screen in a window; click to touch",
      "normwatch boot --no-ble                     boot to the UI and print a report",
      "normwatch boot --phone --live --flash-state watch.zip   on the air, for the Android emulator",
      "normwatch records --flash-state watch.zip   a day of sport records to sync (its own firmware writes them)",
      "normwatch records --flash-state watch.zip --count 0 --heart-rate 5   five heart-rate readings it measures",
      "normwatch records --flash-state watch.zip --count 0 --sleep 2        two nights' sleep sessions it records",
      "normwatch info                              the firmware image's header",
      "normwatch dump -o DIR --range 026DA430-0496C000   read the NAND out (needs a patched image)"]),
    ("normcmd", "normplus.watch.__main__:cmd_main",
     "Asks a watch one command and decodes the reply: the emulated one, or the real one with --mac.",
     ["normcmd BATTERY_POWER CHECK --mac           the real watch",
      "normcmd 08 70                               the emulated watch (boots it first)"]),
    ("normphone", "normplus.phone.cli:main",
     "The Android emulator that runs the app, and its Bluetooth.",
     ["normphone setup                             once: SDK packages, accelerator, the Pixel 8",
      "normphone start                             with Bluetooth to the real watch (USB dongle)",
      "normphone start --watch                     with Bluetooth to the emulated watch",
      "normphone install                           build the app and install it",
      "normphone clear-bond                        forget the watch's pairing (after switching)"]),
    ("normfw", "normplus.firmware.cli:main",
     "Firmware image tools.",
     ["normfw verify NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin   check an image's checksums",
      "normfw seal patched.bin --in-place          re-seal an image after patching it",
      "normfw patch-nand IMAGE -o patched.bin      the NAND read-out patch (#68)",
      "normfw query-ota                            what the vendor's update server offers"]),
    ("normtest", "normplus.testing:main",
     "Runs the Python tests (about 5 minutes for all of them).",
     ["normtest                                    every test file",
      "normtest ota cmd                            only files whose name contains ota or cmd",
      "normtest ota -- --full                      pass options on to the tests"]),
    ("normboard", "normplus.board:main",
     "The task board: GitHub issues on the NormPlus project.",
     ["normboard                                   what is open, In Progress first",
      "normboard show 13                           one issue with its comments",
      "normboard new \"Title\" -a app --start        a new issue in an area, into In Progress",
      "normboard done 13 -m \"what was done\"        close it"]),
    ("normhelp", "normplus.help:main",
     "This overview.",
     ["normhelp watch boot                         a command's own detailed help"]),
]

RECIPES = [
    ("First time on a machine", [
        "git config --global core.longpaths true",
        "git clone --recurse-submodules https://github.com/David-Aharoni01/NormPlus.git",
        "uv tool install --editable .              (in the clone: puts these commands on PATH)",
        "normphone setup                           (only to run the Android app)"]),
    ("After pulling new code", [
        "uv tool install --editable . --reinstall  (only when a new command appeared)"]),
    ("Run the app against the emulated watch -- no hardware", [
        "normwatch records --flash-state watch.zip               (optional, first: a day of records to sync)",
        "normwatch boot --phone --live --flash-state watch.zip   (terminal 1, keep it running)",
        "normphone start --watch                                  (terminal 2)",
        "normphone install                                        (terminal 3)",
        "If Android asks \"Pair with Norm2#00000?\", tap Pair within 30 seconds."]),
    ("Run the app against the real watch", [
        "normphone start            (the USB dongle bound to WinUSB; see docs/android-emulator.md)",
        "normphone install"]),
    ("Check something on the real watch, quickly", [
        "normcmd BATTERY_POWER CHECK --mac",
        "(the watch must not be connected to a phone at the time)"]),
    ("Read the watch's NAND out (the missing resources, #65)", [
        "normfw patch-nand NORM/assets/Apollo3_P03B_NORM2_F0.2B01.bin -o patched.bin",
        "normwatch dump -o dump --range 026DA430-0496C000 --image patched.bin --flash-state bound.zip",
        "normwatch boot --live --nand dump          (mount what came back)",
        "The physical watch needs the patch flashed first, which is #70."]),
]

DOCS = [
    ("README.md", "getting started"),
    ("docs/app.md", "the Android app"),
    ("docs/protocol.md", "the watch protocol and firmware updates"),
    ("docs/firmware.md", "the watch's firmware"),
    ("docs/watch-emulator.md", "the watch emulator (docs/watch-emulator-internals.md in full)"),
    ("docs/android-emulator.md", "the Android emulator"),
]


def _entry(name: str):
    module, func = next(t[1] for t in TOOLS if t[0] == name).split(":")
    return getattr(importlib.import_module(module), func)


def _resolve(word: str) -> str | None:
    word = word.lower()
    for name, *_ in TOOLS:
        if word in (name, name[len("norm"):]):
            return name
    return None


def overview() -> str:
    out = ["NormPlus tools. Every command takes --help; `normhelp <command>` shows it. There,",
           "\"options\" are for you, and \"developer options\" for the AI developer working on them.",
           ""]
    for name, _, what, examples in TOOLS:
        out.append(f"{name:<10} {what}")
        out += [f"    {line}" for line in examples]
        out.append("")
    out.append("Recipes")
    for title, lines in RECIPES:
        out.append(f"  {title}:")
        out += [f"    {line}" for line in lines]
    out.append("")
    out.append("Documentation")
    out += [f"  {path:<26} {what}" for path, what in DOCS]
    out.append("")
    out.append("Not installed? Every command also runs as `uv run <command>` inside the repository.")
    return "\n".join(out)


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    if not argv or argv[0] in ("-h", "--help"):
        print(overview())
        return 0
    name = _resolve(argv[0])
    if name is None:
        known = ", ".join(t[0] for t in TOOLS)
        print(f"normhelp: no command called {argv[0]!r}. The commands: {known}", file=sys.stderr)
        return 2
    if name == "normhelp":
        print(textwrap.dedent(__doc__).strip())
        return 0
    try:
        return _entry(name)([*argv[1:], "--help"]) or 0
    except SystemExit as stop:          # argparse leaves through SystemExit after --help
        return stop.code if isinstance(stop.code, int) else 0


if __name__ == "__main__":
    sys.exit(main())
