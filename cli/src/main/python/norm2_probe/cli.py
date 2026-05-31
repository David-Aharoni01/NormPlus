"""CLI argument parsing and command dispatching."""

import argparse
import sys
from typing import Optional

from . import commands, protocol
from .probe import Norm2Probe


def setup_argparse() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="norm2_probe",
        description="Norm 2 smartwatch BLE protocol probe — test watch communication directly.",
    )
    subparsers = parser.add_subparsers(dest="command", help="Command to execute")

    # Scan
    scan_parser = subparsers.add_parser("scan", help="Scan for nearby BLE devices")
    scan_parser.add_argument("--duration", type=float, default=10.0, help="Scan duration in seconds (default: 10)")

    # Connect (deprecated; kept for backward compatibility, use specific commands instead)
    connect_parser = subparsers.add_parser("connect", help="Connect to device (legacy, use specific commands)")
    connect_parser.add_argument("mac", help="Device MAC address")

    # Info
    info_parser = subparsers.add_parser("info", help="Dump all services and characteristics")
    info_parser.add_argument("mac", help="Device MAC address")

    # Watch
    watch_parser = subparsers.add_parser("watch", help="Passively listen for notifications")
    watch_parser.add_argument("mac", help="Device MAC address")
    watch_parser.add_argument("--duration", type=float, default=10.0, help="Listen duration in seconds (default: 10)")

    # Battery
    battery_parser = subparsers.add_parser("battery", help="Query battery level")
    battery_parser.add_argument("mac", help="Device MAC address")

    # Version
    version_parser = subparsers.add_parser("version", help="Query device version")
    version_parser.add_argument("mac", help="Device MAC address")
    version_parser.add_argument("--type", type=int, default=6, help="Version type to query (default: 6)")

    # Sync count
    sync_parser = subparsers.add_parser("sync-count", help="Query sport/sleep/HR record counts")
    sync_parser.add_argument("mac", help="Device MAC address")

    # Brightness
    brightness_parser = subparsers.add_parser("brightness", help="Query or set screen brightness")
    brightness_parser.add_argument("mac", help="Device MAC address")
    brightness_parser.add_argument("--set", type=int, dest="level", help="Set brightness level (0-100)")

    # DND
    dnd_parser = subparsers.add_parser("dnd", help="Query do-not-disturb settings")
    dnd_parser.add_argument("mac", help="Device MAC address")

    # Switch
    switch_parser = subparsers.add_parser("switch", help="Query switch settings bitmask")
    switch_parser.add_argument("mac", help="Device MAC address")

    # Raw
    raw_parser = subparsers.add_parser("raw", help="Send raw command")
    raw_parser.add_argument("mac", help="Device MAC address")
    raw_parser.add_argument("cmd", type=lambda x: int(x, 16), help="Command byte (hex, e.g., 08)")
    raw_parser.add_argument("action", type=lambda x: int(x, 16), help="Action byte (hex, e.g., 70)")
    raw_parser.add_argument("--payload", type=str, default="", help="Payload hex string (e.g., 00)")

    # Pair (Windows only: one-time pairing to populate GATT cache)
    pair_parser = subparsers.add_parser(
        "pair",
        help="[Windows] Pair device via Just Works BLE pairing (one-time setup, no user interaction needed)"
    )
    pair_parser.add_argument("mac", help="Device MAC address")

    # Test
    test_parser = subparsers.add_parser("test-check", help="Run CHECK command matrix test")
    test_parser.add_argument("mac", help="Device MAC address")

    # Bridge (backend for the Kotlin normlink-cli; stdio line protocol, runs until QUIT/EOF)
    bridge_parser = subparsers.add_parser(
        "bridge",
        help="Raw BLE byte-pipe backend for normlink-cli (stdio line protocol)",
    )
    bridge_parser.add_argument("mac", help="Device MAC address")

    return parser


async def execute_command(args: argparse.Namespace) -> None:
    cmd = args.command
    if not cmd:
        print("No command specified. Use --help for usage.")
        return

    if cmd == "scan":
        probe = Norm2Probe()
        await probe.scan(args.duration)
        return

    # pair command: does its own connection, doesn't go through normal connect()
    if cmd == "pair":
        probe = Norm2Probe()
        await probe.pair(args.mac)
        return

    # All other commands require a MAC address
    if not hasattr(args, "mac"):
        print(f"Command '{cmd}' requires no MAC address.")
        return

    probe = Norm2Probe()
    try:
        await probe.connect(args.mac)
        if not probe.client or not probe.client.is_connected:
            print("Failed to connect.")
            return

        if cmd == "connect":
            print("Connected. Disconnect with Ctrl+C.")
            try:
                await asyncio.sleep(float("inf"))
            except KeyboardInterrupt:
                pass

        elif cmd == "info":
            await probe.info()

        elif cmd == "watch":
            await probe.watch(args.duration)

        elif cmd == "battery":
            await commands.cmd_battery(probe)

        elif cmd == "version":
            await commands.cmd_version(probe, args.type)

        elif cmd == "sync-count":
            await commands.cmd_sync_count(probe)

        elif cmd == "brightness":
            await commands.cmd_brightness(probe, args.level)

        elif cmd == "dnd":
            await commands.cmd_dnd(probe)

        elif cmd == "switch":
            await commands.cmd_switch(probe)

        elif cmd == "raw":
            payload = bytes.fromhex(args.payload) if args.payload else b""
            await commands.cmd_raw(probe, args.cmd, args.action, payload)

        elif cmd == "test-check":
            await commands.cmd_test_check_matrix(probe)

        elif cmd == "pair":
            pass  # handled above before connect()

    finally:
        await probe.disconnect()


# Import asyncio here to avoid circular dependency
import asyncio
