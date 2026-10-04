"""Every command's ``--help``, and the two readers it is written for (#76).

Each page lists its flags under ``options`` -- what a person running the tool needs --
or ``developer options``, for the AI developer working on it (``normplus.developer_options``).
These tests render every page of every command, since a stray ``%`` in a help string only
fails when someone asks for help, and hold the commands people are given to run -- in
``normhelp`` and the README -- to flags that exist and are listed under ``options``.

Run with:  uv run normtest test_help
"""
import contextlib
import io
import re
import shlex
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from normplus import help as normhelp

REPO = Path(__file__).resolve().parents[2]
COMMANDS = [name for name, *_ in normhelp.TOOLS]
#: normfw dispatches by hand, so its own page has no {choices} to read them from.
NORMFW = ["verify", "seal", "patch-nand", "query-ota"]


def page(command: str, *words: str) -> tuple[int, str]:
    """``command words --help``, as the person typing it sees it, and its exit status."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
        status = normhelp.main([command, *words])
    return status, out.getvalue()


def subcommands(command: str) -> list[str]:
    if command == "normfw":
        return NORMFW
    found = re.search(r"\{([\w,-]+)\} \.\.\.", page(command)[1])
    return found.group(1).split(",") if found else []


def sections(text: str) -> dict[str, str]:
    """Every flag on a help page, and the heading it is listed under."""
    where, heading = {}, None
    for line in text.splitlines():
        if line and not line.startswith(" ") and line.endswith(":"):
            heading = line[:-1]
        elif heading and line.startswith("  -"):
            for flag in re.findall(r"(?:^|, )(-{1,2}[\w-]+)", line.strip()):
                where[flag] = heading
    return where


def given_to_people() -> list[tuple[str, str]]:
    """(where, command line) for every NormPlus command line normhelp and the README show."""
    lines = [("normhelp", ex) for *_, examples in normhelp.TOOLS for ex in examples]
    lines += [("normhelp", ln) for _, recipe in normhelp.RECIPES for ln in recipe]
    readme = (REPO / "README.md").read_text(encoding="utf-8").splitlines()
    lines += [("README.md", ln) for ln in readme]
    out = []
    for where, line in lines:
        line = re.split(r"\s{2,}|\s#", line.strip(), maxsplit=1)[0]
        if line.split(" ", 1)[0] in COMMANDS and line.split(" ", 1)[0] != "normhelp":
            out.append((where, line))
    return out


def test_every_page_renders():
    for command in COMMANDS:
        for words in [(), *((sub,) for sub in subcommands(command))]:
            status, text = page(command, *words)
            assert status == 0 and text.strip(), f"{command} {' '.join(words)} --help: {text}"


def test_developer_options_come_after_the_users():
    status, text = page("normwatch", "boot")
    users, developers = text.index("\noptions:"), text.index("\ndeveloper options:")
    assert users < developers, text
    where = sections(text)
    assert where["--live"] == "options" and where["--max-instructions"] == "developer options"


def test_what_people_are_given_to_run_uses_only_their_options():
    lines = given_to_people()
    assert len(lines) > 20, lines       # the parsing above still finds them
    for where, line in lines:
        command, *words = shlex.split(line)
        words = words[:words.index("--")] if "--" in words else words
        sub = [words[0]] if words and words[0] in subcommands(command) else []
        flags = sections(page(command, *sub)[1])
        for word in words:
            if word.startswith("-"):
                assert word in flags, f"{where}: {line}: {word} is not a flag of {command} {''.join(sub)}"
                assert flags[word] == "options", \
                    f"{where}: {line}: {word} is under {flags[word]}, but people are given it"


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
