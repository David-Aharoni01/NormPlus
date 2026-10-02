"""The Android emulator's netsim endpoint, served the way emulator 36.x needs.

Bumble 0.0.229's controller-mode ``android-netsim`` transport predates the
emulator's newer ``PacketRequest.packet`` field -- raw H4 bytes instead of the
structured ``hci_packet`` -- and drops those with "Unexpected request type:
packet". This is bumble's ``open_android_netsim_controller_transport`` with
that one case added; everything else is unchanged.

Two things serve it: ``normplus.phone.bridge`` (a real dongle behind the
endpoint) and the watch emulator (the emulated watch's virtual radio behind
it, see ``normplus/watch/fw/blelink.py``). Both import it from here rather than
carrying their own copy.
"""
import asyncio
import logging

from bumble import transport
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

logger = logging.getLogger("netsim_transport")


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


def install() -> None:
    """Route bumble's controller-mode ``android-netsim:`` spec through the fix,
    so ``open_transport("android-netsim:_:8877,mode=controller")`` works."""
    ns.open_android_netsim_controller_transport = open_controller_transport_fixed
