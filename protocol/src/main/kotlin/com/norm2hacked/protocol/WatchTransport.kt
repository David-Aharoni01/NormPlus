package com.norm2hacked.protocol

import java.util.UUID
import kotlinx.coroutines.flow.SharedFlow

/**
 * Platform-agnostic BLE transport interface.
 *
 * Implemented by:
 *  - BleManager (Android) — full GATT stack via Android bluetooth APIs
 *  - WinTransport (CLI)   — Win32 BluetoothGATT* APIs via JNA
 *
 * Deliberately minimal: only the operations that SyncHealthDataUseCase and the
 * CLI test commands actually need. OTA-specific methods (writeToCharAwait,
 * drainOtaWriteChannel, waitForDfuService) stay on BleManager — they are
 * Android-only and the CLI does not perform OTA.
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
     * Fire-and-forget write to a specific characteristic UUID.
     * Used for clock sync, find-device, and OTA control bytes where no
     * response packet is expected.
     */
    fun writeToChar(bytes: ByteArray, charUuid: UUID)
}
