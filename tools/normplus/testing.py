"""``normtest``: run the Python test suite in ``tools/tests``.

Each test file is a standalone script (not pytest) that exits non-zero when one of its
tests fails, and they run one at a time: several drive the emulated watch in real time,
and running them side by side makes them slow enough to fail.

    normtest                  every test file (about 5 minutes)
    normtest ota cmd          only files whose name contains "ota" or "cmd"
    normtest ota -- --full    pass arguments on to the test files
    normtest --list
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time

from . import paths


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    passthrough = []
    if "--" in argv:
        at = argv.index("--")
        argv, passthrough = argv[:at], argv[at + 1:]
    ap = argparse.ArgumentParser(prog="normtest", description=__doc__.split("\n\n")[0].replace("``", ""),
                                 epilog=__doc__.split("\n\n", 2)[2].replace("``", ""),
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("names", nargs="*", help="run only test files whose name contains one of these")
    ap.add_argument("--list", action="store_true", help="list the test files and stop")
    ap.add_argument("-v", "--verbose", action="store_true", help="show every test's output")
    args = ap.parse_args(argv)

    tests = sorted(paths.TESTS.glob("test_*.py"))
    if args.names:
        tests = [t for t in tests if any(n in t.stem for n in args.names)]
    if args.list or not tests:
        for t in tests:
            print(t.stem)
        if not tests:
            print("no test file matches", " ".join(args.names), file=sys.stderr)
        return 0 if tests else 2

    failed = []
    started = time.monotonic()
    for t in tests:
        if sys.stdout.isatty():
            print(f"  ...  {t.stem}", end="\r", flush=True)
        t0 = time.monotonic()
        run = subprocess.run([sys.executable, str(t), *passthrough], cwd=paths.REPO,
                             capture_output=True, text=True, encoding="utf-8", errors="replace")
        took = time.monotonic() - t0
        ok = run.returncode == 0
        print(f"  {'ok  ' if ok else 'FAIL'} {took:6.1f}s  {t.stem}")
        output = (run.stdout + run.stderr).strip()
        if args.verbose and output:
            print("\n".join("        " + line for line in output.splitlines()))
        elif not ok:
            failed.append(t.stem)
            lines = [l for l in output.splitlines() if not l.startswith("WARNING:")]
            shown = [l for l in lines if l.startswith("FAIL")] or lines[-15:]
            print("\n".join("        " + line for line in shown))
    total = time.monotonic() - started
    print(f"\n{len(tests) - len(failed)}/{len(tests)} passed in {total:.0f}s"
          + (f"; failed: {', '.join(failed)}" if failed else ""))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
