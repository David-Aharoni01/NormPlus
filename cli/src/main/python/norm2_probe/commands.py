"""High-level command functions for watch interaction."""

import asyncio
from typing import Optional

from . import protocol
from .probe import Norm2Probe


async def cmd_battery(probe: Norm2Probe):
    pkt = await probe.send_and_await(protocol.CMDS["BATTERY_POWER"], protocol.ACTION_CHECK, bytes([0x00]))
    if pkt and pkt["payload"]:
        b = pkt["payload"][0]
        print(f"  Battery: {b & 0x7F}%  charging={'yes' if b & 0x80 else 'no'}")


async def cmd_version(probe: Norm2Probe, vtype: int = 6):
    pkt = await probe.send_and_await(protocol.CMDS["DEVICE_VERSION"], protocol.ACTION_CHECK, bytes([vtype]))
    if pkt and len(pkt["payload"]) >= 2:
        s = pkt["payload"][1:].decode("ascii", errors="replace").strip()
        print(f"  Version type={vtype}: {s}")


async def cmd_sync_count(probe: Norm2Probe):
    print("\n--- Sport/Sleep count ---")
    pkt = await probe.send_and_await(protocol.CMDS["TOTAL_SPORT_SLEEP_COUNT"], protocol.ACTION_CHECK, bytes([0x00]))
    if pkt and len(pkt["payload"]) >= 4:
        sport = pkt["payload"][0] | (pkt["payload"][1] << 8)
        sleep = pkt["payload"][2] | (pkt["payload"][3] << 8)
        print(f"  Sport records: {sport}  Sleep records: {sleep}")

    print("\n--- Heart rate count ---")
    pkt = await probe.send_and_await(protocol.CMDS["TOTAL_HEART_RATE_COUNT"], protocol.ACTION_CHECK, bytes([0x00]))
    if pkt and len(pkt["payload"]) >= 2:
        hr = pkt["payload"][0] | (pkt["payload"][1] << 8)
        print(f"  Heart rate records: {hr}")


async def cmd_brightness(probe: Norm2Probe, level: Optional[int] = None):
    if level is None:
        pkt = await probe.send_and_await(protocol.CMDS["SCREEN_BRIGHTNESS"], protocol.ACTION_CHECK, bytes([0x00]))
        if pkt and pkt["payload"]:
            print(f"  Brightness: {pkt['payload'][0] & 0xFF}")
    else:
        pkt = await probe.send_and_await(protocol.CMDS["SCREEN_BRIGHTNESS"], protocol.ACTION_SET, bytes([level & 0xFF]))
        print(f"  Set brightness to {level}")


async def cmd_dnd(probe: Norm2Probe):
    pkt = await probe.send_and_await(protocol.CMDS["DO_NOT_DISTURB"], protocol.ACTION_CHECK, bytes([0x00]))
    if pkt and len(pkt["payload"]) >= 5:
        p = pkt["payload"]
        print(f"  DND: enabled={bool(p[0])}  {p[1]:02d}:{p[2]:02d}–{p[3]:02d}:{p[4]:02d}")


async def cmd_switch(probe: Norm2Probe):
    pkt = await probe.send_and_await(protocol.CMDS["SWITCH_SETTING"], protocol.ACTION_CHECK, bytes([0x00]))
    if pkt and len(pkt["payload"]) >= 4:
        mask = int.from_bytes(pkt["payload"][:4], "little")
        bits = {
            0x001: "anti-lost", 0x002: "auto-sync", 0x004: "sleep", 0x008: "auto-sleep",
            0x010: "call", 0x020: "miss-call", 0x040: "sms", 0x080: "social",
            0x100: "email", 0x200: "calendar", 0x400: "sedentary", 0x800: "low-power",
            0x1000: "second-reminder", 0x2000: "ring", 0x4000: "raise-wake",
            0x8000: "goal-achieved", 0x10000: "real-hr", 0x40000: "hr-monitor",
        }
        on = [name for bit, name in bits.items() if mask & bit]
        print(f"  Switch mask: 0x{mask:06X}  on=[{', '.join(on)}]")


async def cmd_test_check_matrix(probe: Norm2Probe):
    """Send BATTERY_POWER CHECK in 4 variants to find which the watch responds to."""
    print("\n=== CHECK command matrix test (BATTERY_POWER) ===\n")
    variants = [
        ("A", bytes([0x00]), True,  "payload=[00] + trigger"),
        ("B", bytes([0x00]), False, "payload=[00] no trigger"),
        ("C", bytes([]),     True,  "payload=[]   + trigger"),
        ("D", bytes([]),     False, "payload=[]   no trigger"),
    ]
    for label, payload, trig, desc in variants:
        print(f"\n--- Variant {label}: {desc} ---")
        result = await probe.send_and_await(
            protocol.CMDS["BATTERY_POWER"], protocol.ACTION_CHECK, payload,
            timeout=5.0, use_trigger=trig,
        )
        if result:
            print(f"  ✓ RESPONDED")
        else:
            print(f"  ✗ no response")
        await asyncio.sleep(0.5)

    print("\n=== Matrix test complete ===")


async def cmd_raw(probe: Norm2Probe, cmd: int, action: int, payload: bytes = b""):
    await probe.send_and_await(cmd, action, payload)
