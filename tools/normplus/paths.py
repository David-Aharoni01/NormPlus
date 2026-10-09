"""Where things are in the NormPlus checkout.

The tools are installed editable (``uv sync``, or ``uv tool install --editable .``), so
this file sits inside the repository and the root is found from it. ``NORMPLUS_ROOT``
overrides that -- for a non-editable install, which has no checkout to find.
"""

from __future__ import annotations

import os
from pathlib import Path


def _find_root() -> Path:
    env = os.environ.get("NORMPLUS_ROOT")
    if env:
        return Path(env).resolve()
    for start in (Path(__file__).resolve(), Path.cwd().resolve()):
        for parent in (start, *start.parents):
            if (parent / "pyproject.toml").is_file() and (parent / "tools" / "normplus").is_dir():
                return parent
    return Path(__file__).resolve().parents[2]


#: The repository root.
REPO = _find_root()
#: The private submodule: the official app unpacked, and the APK (README.md).
NORM = REPO / "NORM"
#: The watch firmware the emulator runs, and the watch's resource image.
FIRMWARE = NORM / "assets" / "Apollo3_P03B_NORM2_F0.2B01.bin"
#: The watch's **factory resources**, read off the physical watch (#65, #70): the
#: artwork most of its screens are drawn from, which the companion app does not
#: carry. Vendor material, so it is in the private submodule and may be absent.
FACTORY_NAND = NORM / "_nand"
RESOURCES = NORM / "assets" / "Picture_P03B_NORM2_0.4.bin"
#: The command names normwatch/normcmd display come from :protocol, their single source.
COMMAND_CODES = REPO / "protocol/src/main/kotlin/com/normplus/protocol/CommandCode.kt"
#: The Python test suite.
TESTS = REPO / "tools" / "tests"

#: Why a file under NORM/ is missing, for error messages.
NORM_MISSING = ("NORM/ is not checked out: it is a private submodule -- run "
                "`git submodule update --init` (signed in to GitHub as its owner), or unpack "
                "your own copy of the app into NORM/ with apktool")
