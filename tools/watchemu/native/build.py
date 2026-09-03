"""Build the native block hook.

    py -3.11 tools/watchemu/native/build.py

Produces watchemu_hook.dll (or .so) next to the source. The emulator runs
perfectly well without it -- normwatch/fw/fasthook.py falls back to the Python
hook when the library is missing -- so this is optional.

MSVC is found by asking vswhere, which ships with any Visual Studio install, so
there is no need to have run vcvarsall first.
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SOURCE = HERE / "watchemu_hook.c"
WINDOWS = sys.platform == "win32"
OUTPUT = HERE / ("watchemu_hook.dll" if WINDOWS else "watchemu_hook.so")

VSWHERE = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / \
    "Microsoft Visual Studio" / "Installer" / "vswhere.exe"


def find_msvc() -> Path | None:
    """The newest x64 cl.exe from any Visual Studio install."""
    roots = []
    if VSWHERE.exists():
        out = subprocess.run(
            [str(VSWHERE), "-latest", "-products", "*", "-property", "installationPath"],
            capture_output=True, text=True)
        if out.returncode == 0 and out.stdout.strip():
            roots.append(Path(out.stdout.strip()))
    for base in (r"C:\Program Files\Microsoft Visual Studio",
                 r"C:\Program Files (x86)\Microsoft Visual Studio"):
        if Path(base).exists():
            roots.extend(Path(base).glob("*/*"))
    for root in roots:
        candidates = sorted((root / "VC" / "Tools" / "MSVC").glob("*/bin/Hostx64/x64/cl.exe"))
        if candidates:
            return candidates[-1]
    return None


def build() -> int:
    if not WINDOWS:
        command = ["cc", "-O2", "-shared", "-fPIC", str(SOURCE), "-o", str(OUTPUT)]
        print("  " + " ".join(command))
        return subprocess.run(command).returncode

    cl = find_msvc()
    if cl is None:
        print("no cl.exe found — install Visual Studio Build Tools, or just skip "
              "this: the emulator falls back to the Python hook.", file=sys.stderr)
        return 1

    # cl needs its own headers and libs on the path; they sit above the compiler.
    msvc_root = cl.parents[3]
    include = msvc_root / "include"
    libdir = msvc_root / "lib" / "x64"
    sdk_include, sdk_lib = _windows_sdk()

    env = dict(os.environ)
    env["INCLUDE"] = os.pathsep.join(
        [str(include)] + [str(p) for p in sdk_include] + [env.get("INCLUDE", "")])
    env["LIB"] = os.pathsep.join(
        [str(libdir)] + [str(p) for p in sdk_lib] + [env.get("LIB", "")])

    command = [str(cl), "/nologo", "/O2", "/LD", str(SOURCE),
               "/Fe:" + str(OUTPUT), "/Fo:" + str(HERE / "watchemu_hook.obj")]
    # Quiet unless it goes wrong: this runs on demand from fasthook.load, and
    # the linker's chatter in the middle of a boot report is just noise.
    result = subprocess.run(command, cwd=HERE, env=env,
                            capture_output=True, text=True)
    if result.returncode != 0:
        print(result.stdout, file=sys.stderr)
        print(result.stderr, file=sys.stderr)
    for junk in ("watchemu_hook.obj", "watchemu_hook.exp", "watchemu_hook.lib"):
        (HERE / junk).unlink(missing_ok=True)
    return result.returncode


def _windows_sdk():
    """Include and lib directories of the newest installed Windows SDK."""
    base = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / \
        "Windows Kits" / "10"
    includes, libs = [], []
    if (base / "Include").exists():
        versions = sorted((base / "Include").iterdir())
        if versions:
            latest = versions[-1].name
            for part in ("ucrt", "um", "shared"):
                includes.append(base / "Include" / latest / part)
                libs.append(base / "Lib" / latest / part / "x64")
    return includes, libs


if __name__ == "__main__":
    code = build()
    if code == 0:
        print(f"built {OUTPUT}")
    raise SystemExit(code)
