#!/usr/bin/env python3
"""
Bumble HCI bridge for the Norm+ emulator, with two fixes the stock
`bumble-hci-bridge` lacks on this setup:

1. **netsim `packet` proto field** — Android emulator >= 36.x sends some HCI
   packets in the newer `PacketRequest.packet` (raw H4 bytes) field instead of
   the older structured `hci_packet` field. Stock Bumble (0.0.229) drops those
   with "Unexpected request type: packet". `netsim_transport.py` (next to this
   file) is the controller-mode netsim transport with both fields accepted,
   monkeypatched in (no site-packages edit). The watch emulator serves the same
   endpoint through it (`normwatch boot --netsim`).

2. **Google vendor HCI short-circuit** — Android's BT stack sends
   `LE_GET_VENDOR_CAPABILITIES` (0xFD53) and friends during init. Cheap/older
   dongles (e.g. CSR8510) silently ignore unknown vendor opcodes, so Android
   times out for 2s and then *fatally* aborts stack startup. We answer those
   opcodes locally with a "not supported" Command Complete so init proceeds.

Usage:
  normphone bridge [--port 8877] [--usb usb:0A12:0001]     (normphone start runs it for you)

Then launch the emulator pointed at this bridge:
  emulator -avd Pixel_8_API35 -packet-streamer-endpoint localhost:8877 \
           -writable-system -no-snapshot-load
"""
import argparse
import asyncio
import logging

import bumble.logging
from bumble import hci, transport
from bumble.bridge import HCI_Bridge

from . import netsim_transport

logger = logging.getLogger("norm_emu_bridge")

# Google vendor-specific opcodes (OGF=0x3f) that older controllers ignore.
# We answer these locally instead of forwarding to the dongle.
VENDOR_SHORT_CIRCUIT = {
    hci.hci_command_op_code(0x3F, 0x0153),  # LE_GET_VENDOR_CAPABILITIES
    hci.hci_command_op_code(0x3F, 0x015B),  # follow-up Google VS command
}


async def async_main(port: int, usb: str):
    # Route controller-mode android-netsim through our fixed implementation.
    netsim_transport.install()

    host_spec = f"android-netsim:_:{port},mode=controller"
    print(f">>> opening netsim controller on :{port}")
    async with await transport.open_transport(host_spec) as (host_src, host_sink):
        print(">>> netsim ready, opening dongle", usb)
        async with await transport.open_transport(usb) as (ctrl_src, ctrl_sink):
            print(">>> dongle ready, bridging")

            def host_to_controller_filter(packet):
                if (
                    packet.hci_packet_type == hci.HCI_COMMAND_PACKET
                    and packet.op_code in VENDOR_SHORT_CIRCUIT
                ):
                    logger.info(
                        f"short-circuiting vendor opcode 0x{packet.op_code:04X}"
                    )
                    response = hci.HCI_Command_Complete_Event(
                        num_hci_command_packets=1,
                        command_opcode=packet.op_code,
                        return_parameters=hci.HCI_StatusReturnParameters(
                            status=hci.HCI_UNKNOWN_HCI_COMMAND_ERROR
                        ),
                    )
                    return (bytes(response), True)  # respond to sender, don't forward
                return None

            _ = HCI_Bridge(
                host_src, host_sink, ctrl_src, ctrl_sink,
                host_to_controller_filter, None,
            )
            await asyncio.get_running_loop().create_future()


def main(argv=None):
    p = argparse.ArgumentParser(prog="normphone bridge",
                                description="Norm+ emulator Bumble HCI bridge")
    p.add_argument("--port", type=int, default=8877)
    p.add_argument("--usb", default="usb:0A12:0001")
    args = p.parse_args(argv)
    bumble.logging.setup_basic_logging()
    asyncio.run(async_main(args.port, args.usb))


if __name__ == "__main__":
    main()
