"""
Bridge mode: a raw BLE byte-pipe backend for the Kotlin ``normlink-cli``.

The Kotlin ``PythonBridgeTransport`` spawns ``python -m norm2_probe bridge <MAC>``
and speaks a tiny line protocol over stdio. **All** protocol framing/parsing lives
in Kotlin (``:protocol``) — exactly the code the Android app uses. This side only
moves raw bytes over BLE and performs the one-time Just Works bonding.

Line protocol (UTF-8, one message per line, ``\\n``-terminated, flushed each write):

  stdout (clean protocol ONLY):
    READY              connected + bonded + notifications subscribed
    N <hex>            raw notification bytes from 8002/8004 (NOT deframed)
    ERR <message>      fatal error; the process then exits non-zero

  stdin (Kotlin -> bridge):
    W <hex>            write the frame to the write char, then trigger [0x03]->8002
    QUIT               disconnect and exit (stdin EOF also quits)

Every human/debug ``print`` from the probe is redirected to **stderr** so it can
never corrupt the protocol stream on stdout.
"""

import asyncio
import sys

from .probe import Norm2Probe


async def run_bridge(mac: str) -> int:
    # The probe logs with print(); route all of that to stderr so stdout carries
    # only the line protocol. Protocol lines are written to the saved real stdout.
    real_out = sys.stdout
    sys.stdout = sys.stderr

    def emit(line: str) -> None:
        real_out.write(line + "\n")
        real_out.flush()

    probe = Norm2Probe()

    try:
        await probe.connect(mac)
    except Exception as e:  # noqa: BLE001 - surface any failure as a protocol error
        emit(f"ERR connect: {type(e).__name__}: {e}")
        return 2
    if not probe.client or not probe.client.is_connected:
        emit("ERR connect failed")
        return 2

    emit("READY")

    stop = asyncio.Event()

    async def pump_notifications() -> None:
        # Forward every notification's raw bytes to Kotlin, which owns deframing.
        while not stop.is_set():
            try:
                _uuid, data = await asyncio.wait_for(probe.notify_queue.get(), timeout=0.2)
            except asyncio.TimeoutError:
                continue
            emit("N " + bytes(data).hex())

    async def read_stdin() -> None:
        loop = asyncio.get_running_loop()
        while not stop.is_set():
            # readline() blocks; run it off the event loop so notifications keep flowing.
            line = await loop.run_in_executor(None, sys.stdin.readline)
            if line == "":  # EOF — parent closed our stdin
                break
            line = line.strip()
            if not line:
                continue
            if line == "QUIT":
                break
            if line.startswith("W "):
                try:
                    data = bytes.fromhex(line[2:].strip())
                except ValueError:
                    emit("ERR bad hex")
                    continue
                try:
                    await probe.write_raw(data)   # -> write char (8003/8001)
                    await probe.trigger()         # [0x03] -> 8002, as send_and_await does
                except Exception as e:  # noqa: BLE001
                    emit(f"ERR write: {type(e).__name__}: {e}")
            else:
                emit(f"ERR unknown command: {line!r}")
        stop.set()

    notif_task = asyncio.create_task(pump_notifications())
    try:
        await read_stdin()
    finally:
        stop.set()
        notif_task.cancel()
        try:
            await notif_task
        except asyncio.CancelledError:
            pass
        try:
            await probe.disconnect()
        except Exception as e:  # noqa: BLE001
            print(f"[bridge] disconnect error: {e}", file=sys.stderr)
    return 0
