package com.norm2hacked.protocol

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Platform-agnostic BLE transport interface.
 *
 * Implemented by BleManager (Android); the use cases depend on this rather than
 * on BleManager so they can be driven without a GATT stack.
 *
 * Deliberately minimal: only the operations the use cases (SyncHealthDataUseCase,
 * BindWatchUseCase) actually need. OTA-specific methods (writeToCharAwait,
 * drainOtaWriteChannel, waitForDfuService) stay on BleManager.
 */
interface WatchTransport {

    /** True once the transport is connected and notifications are set up. */
    val isConnected: Boolean

    /**
     * All deframed [Packet] objects received from the watch (chars 8002/8004/8005).
     * Backed by a SharedFlow; subscribers receive packets from the moment they
     * subscribe. [sendAndAwait] subscribes *before* writing to avoid the race
     * where the response arrives before the subscriber is active.
     */
    val parsedFlow: SharedFlow<Packet>

    /**
     * Connect to the watch at [mac] and set up notifications.
     * Suspends until the transport reports Ready or throws on failure.
     */
    suspend fun connect(mac: String)

    /** Cleanly close the BLE connection. */
    fun disconnect()

    /**
     * Build and send a framed command, then await the matching response packet.
     *
     * The response is matched on [cmd] + the expected response action
     * (CHECK→CHECK_RESPONSE, SET→SET_RESPONSE). Uses [timeoutMs] for the
     * per-command deadline.
     *
     * @throws IllegalStateException if not connected
     * @throws kotlinx.coroutines.TimeoutCancellationException on timeout
     */
    suspend fun sendAndAwait(
        cmd: CommandCode,
        action: Action,
        payload: ByteArray = byteArrayOf(),
        timeoutMs: Long = 10_000L,
    ): Packet

    /**
     * Send one command and receive every response it provokes: a reply that comes as a stream.
     *
     * GET_SPORT_DATA and GET_HEART_RATE_DATA are answered with every record on the watch, one
     * frame each (MBluetooth.getSportData → GetSportData, whose parse80BytesArray keeps
     * receiving until it holds the count). Emits each response matched as [sendAndAwait]
     * matches one, as it arrives, and completes after the one [isLast] accepts — or when
     * [idleTimeoutMs] passes without a response. That is an idle timer, restarted by every
     * frame, as the official app keeps (Leaf.isTimeout, reset by setLastSendTime): the physical
     * watch takes ~29 s to stream 920 sport records. Stopping early completes normally, so the
     * caller checks what came; not one response at all is a timeout. The write path is held for
     * the whole stream, so nothing is written to the watch in the middle of it.
     *
     * @throws IllegalStateException if not connected
     */
    fun sendAndStream(
        cmd: CommandCode,
        action: Action,
        payload: ByteArray,
        idleTimeoutMs: Long = 10_000L,
        isLast: (Packet) -> Boolean,
    ): Flow<Packet>

    /**
     * Fire-and-forget write to a specific characteristic UUID.
     * Used for clock sync, find-device, and OTA control bytes where no
     * response packet is expected.
     */
    fun writeToChar(bytes: ByteArray, charUuid: UUID)
}
