#!/usr/bin/env python3
"""
Bumble HCI bridge for the Norm+ emulator, with two fixes the stock
`bumble-hci-bridge` lacks on this setup:

1. **netsim `packet` proto field** — Android emulator >= 36.x sends some HCI
   packets in the newer `PacketRequest.packet` (raw H4 bytes) field instead of
   the older structured `hci_packet` field. Stock Bumble (0.0.229) drops those
   with "Unexpected request type: packet". We monkeypatch the controller-mode
   netsim transport to accept both fields (no site-packages edit).

2. **Google vendor HCI short-circuit** — Android's BT stack sends
   `LE_GET_VENDOR_CAPABILITIES` (0xFD53) and friends during init. Cheap/older
   dongles (e.g. CSR8510) silently ignore unknown vendor opcodes, so Android
   times out for 2s and then *fatally* aborts stack startup. We answer those
   opcodes locally with a "not supported" Command Complete so init proceeds.

Usage:
  py -3.11 norm_emu_bridge.py [--port 8877] [--usb usb:0A12:0001]

Then launch the emulator pointed at this bridge:
  emulator -avd Galaxy_S24_API35 -packet-streamer-endpoint localhost:8877 \
           -writable-system -no-snapshot-load
"""
import argparse
import asyncio
import logging

import bumble.logging
from bumble import hci, transport
from bumble.bridge import HCI_Bridge
import bumble.transport.android_netsim as ns
from bumble.transport.common import ParserSource, PumpedPacketSink
from bumble.transport.grpc_protobuf.netsim.common_pb2 import ChipKind
from bumble.transport.grpc_protobuf.netsim.hci_packet_pb2 import HCIPacket
from bumble.transport.grpc_protobuf.netsim.packet_streamer_pb2 import (
    PacketResponse,
)
from bumble.transport.grpc_protobuf.netsim.packet_streamer_pb2_grpc import (
    PacketStreamerServicer,
    add_PacketStreamerServicer_to_server,
)
import grpc.aio

logger = logging.getLogger("norm_emu_bridge")

# Google vendor-specific opcodes (OGF=0x3f) that older controllers ignore.
# We answer these locally instead of forwarding to the dongle.
VENDOR_SHORT_CIRCUIT = {
    hci.hci_command_op_code(0x3F, 0x0153),  # LE_GET_VENDOR_CAPABILITIES
    hci.hci_command_op_code(0x3F, 0x015B),  # follow-up Google VS command
}


# -----------------------------------------------------------------------------
# Fixed controller-mode netsim transport (accepts both `hci_packet` and `packet`)
# This is a copy of bumble's open_android_netsim_controller_transport with the
# data-packet handling extended; everything else is unchanged.
# -----------------------------------------------------------------------------
async def open_controller_transport_fixed(server_host, server_port, options):
    if server_host == '_' or not server_host:
        server_host = 'localhost'

    instance_number = int(options.get('instance', "0"))
    if not ns.publish_grpc_port(server_port, instance_number):
        logger.warning("unable to publish gRPC port")

    class HciDevice:
        def __init__(self, context, server):
            self.context = context
            self.server = server
            self.name = None
            self.sink = None
            self.raw = False  # set when the client uses the newer `packet` field
            self.loop = asyncio.get_running_loop()
            self.done = self.loop.create_future()

        async def pump(self):
            try:
                await self.pump_loop()
            except asyncio.CancelledError:
                logger.debug('Pump task canceled')
            finally:
                if self.sink:
                    self.server.release_sink()
                    self.sink = None

        async def pump_loop(self):
            while True:
                request = await self.context.read()
                if request == grpc.aio.EOF:
                    if not self.done.done():
                        self.done.set_result(None)
                    return

                if self.name is None:
                    if request.WhichOneof('request_type') == 'initial_info':
                        self.name = request.initial_info.name
                        if request.initial_info.chip.kind != ChipKind.BLUETOOTH:
                            await self.context.write(
                                PacketResponse(error='Unsupported chip type')
                            )
                            continue
                        self.sink = self.server.lease_sink(self)
                        if self.sink is None:
                            await self.context.write(
                                PacketResponse(error='Device busy')
                            )
                            continue
                        continue

                request_type = request.WhichOneof('request_type')
                if request_type == 'hci_packet':
                    data = (
                        bytes([request.hci_packet.packet_type])
                        + request.hci_packet.packet
                    )
                elif request_type == 'packet':
                    # emulator 36.x: raw H4 framed bytes (type prefix + payload)
                    self.raw = True
                    data = request.packet
                else:
                    logger.warning(f'Unexpected request type: {request_type}')
                    await self.context.write(
                        PacketResponse(error='Unexpected request type')
                    )
                    continue

                assert self.sink is not None
                self.sink(data)

        async def send_packet(self, data):
            if self.raw:
                return await self.context.write(PacketResponse(packet=bytes(data)))
            return await self.context.write(
                PacketResponse(
                    hci_packet=HCIPacket(packet_type=data[0], packet=data[1:])
                )
            )

    server_address = f'{server_host}:{server_port}'

    class Server(PacketStreamerServicer, ParserSource):
        def __init__(self):
            PacketStreamerServicer.__init__(self)
            ParserSource.__init__(self)
            self.device = None
            self.grpc_server = grpc.aio.server(options=(('grpc.so_reuseport', 0),))
            add_PacketStreamerServicer_to_server(self, self.grpc_server)
            self.port = self.grpc_server.add_insecure_port(server_address)

        async def start(self):
            await self.grpc_server.start()

        async def serve(self):
            try:
                await self.grpc_server.wait_for_termination()
            except asyncio.CancelledError:
                await self.grpc_server.stop(None)

        async def send_packet(self, packet):
            if not self.device:
                return
            return await self.device.send_packet(packet)

        def lease_sink(self, device):
            if self.device:
                return None
            self.device = device
            return self.parser.feed_data

        def release_sink(self):
            self.device = None

        async def StreamPackets(self, request_iterator, context):
            device = HciDevice(context, self)
            self.device_obj = device
            try:
                await device.pump()
            finally:
                pass

    server = Server()
    await server.start()
    asyncio.get_running_loop().create_task(server.serve())

    sink = PumpedPacketSink(server.send_packet)
    sink.start()
    return transport.Transport(server, sink)


async def async_main(port: int, usb: str):
    # Route controller-mode android-netsim through our fixed implementation.
    ns.open_android_netsim_controller_transport = open_controller_transport_fixed

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


def main():
    p = argparse.ArgumentParser(description="Norm+ emulator Bumble HCI bridge")
    p.add_argument("--port", type=int, default=8877)
    p.add_argument("--usb", default="usb:0A12:0001")
    args = p.parse_args()
    bumble.logging.setup_basic_logging()
    asyncio.run(async_main(args.port, args.usb))


if __name__ == "__main__":
    main()
