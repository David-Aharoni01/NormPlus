"""Norm 2 smartwatch BLE protocol probe.

Interactive CLI tool for testing the watch communication protocol directly from Python,
bypassing the Android app entirely. Use this to isolate protocol bugs by observing
exactly what the watch accepts and responds to.

Setup:
    pip install bleak

Usage:
    python -m norm2_probe scan                           # scan for devices (10s default)
    python -m norm2_probe scan --duration 15             # custom scan duration
    python -m norm2_probe battery <MAC>                  # query battery level
    python -m norm2_probe version <MAC>                  # query device version
    python -m norm2_probe brightness <MAC>               # query brightness
    python -m norm2_probe brightness <MAC> --set 75      # set brightness
    python -m norm2_probe sync-count <MAC>               # query record counts
    python -m norm2_probe info <MAC>                     # dump all services
    python -m norm2_probe watch <MAC>                    # listen for notifications
    python -m norm2_probe raw <MAC> 0x08 0x70 --payload 00  # send raw command
    python -m norm2_probe test-check <MAC>               # run command matrix test
"""

import asyncio
import sys

from . import cli


_HARD_TIMEOUT = 90  # seconds -- script always exits, even if something hangs


async def _run_with_timeout(args):
    try:
        await asyncio.wait_for(cli.execute_command(args), timeout=_HARD_TIMEOUT)
    except asyncio.TimeoutError:
        print(f"\n[!] Hard timeout ({_HARD_TIMEOUT}s) reached -- forcing exit.")
        sys.exit(1)


def main():
    parser = cli.setup_argparse()
    args = parser.parse_args()

    # Bridge mode is long-lived (it serves the Kotlin CLI until QUIT/EOF), so it
    # must bypass the hard timeout below and own its exit code.
    if getattr(args, "command", None) == "bridge":
        from .bridge import run_bridge
        try:
            sys.exit(asyncio.run(run_bridge(args.mac)))
        except KeyboardInterrupt:
            sys.exit(0)

    try:
        asyncio.run(_run_with_timeout(args))
    except KeyboardInterrupt:
        print("\nInterrupted.")
        sys.exit(0)
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
