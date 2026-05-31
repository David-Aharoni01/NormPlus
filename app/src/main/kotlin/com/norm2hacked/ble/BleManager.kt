package com.norm2hacked.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.Packet
import com.norm2hacked.protocol.PacketBuilder
import com.norm2hacked.protocol.WatchTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BleManager"

// Upper bound for the whole post-connect "Setting up…" phase (service discovery +
// notification/CCCD setup). If onServicesDiscovered never fires — e.g. the watch drops
// the link mid-discovery because another GATT client (the official app) is holding it —
// nothing else recovers, so the UI would sit in Discovering forever. This watchdog forces
// the normal disconnect/retry path instead of hanging.
private const val DISCOVERY_TIMEOUT_MS = 25_000L

@Singleton
@SuppressLint("MissingPermission")
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bonder: WatchBonder,
) : WatchTransport {
    // Singleton-scoped scope — lives for the app lifetime.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val btAdapter: BluetoothAdapter
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // ── Public state ──────────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    /** Typed, deframed packets from command characteristics (0x8002/0x8004/0x8005). */
    private val _parsedFlow = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    override val parsedFlow: SharedFlow<Packet> = _parsedFlow.asSharedFlow()

    /** Raw bytes from OTA characteristics (0x1531/0x1532) only — separate from command flow. */
    private val _otaFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val otaFlow: SharedFlow<ByteArray> = _otaFlow.asSharedFlow()

    /** Devices discovered during a scan (emits once per unique MAC). */
    private val _scanResultFlow = MutableSharedFlow<BluetoothDevice>(extraBufferCapacity = 20)
    val scanResultFlow: SharedFlow<BluetoothDevice> = _scanResultFlow.asSharedFlow()

    // ── Private GATT state ────────────────────────────────────────────────────

    private var gatt: BluetoothGatt? = null
    private var queue: BleWriteQueue? = null
    private var scanJob: Job? = null

    // Watchdog that fires if the post-connect discovery/setup phase stalls (see DISCOVERY_TIMEOUT_MS).
    private var discoveryWatchdog: Job? = null

    // Monotonic connection generation. Bumped on every connect() and again when a connection is
    // torn down, so a stale GATT callback (or the watchdog) for an already-superseded connection
    // can be ignored — preventing double retries / double reconnects.
    @Volatile private var connGen = 0

    // Forwarding channels from GATT callback into the shared flows.
    // Stored so they can be closed on disconnect, which stops the forwarding coroutines cleanly.
    @Volatile private var packetCh: Channel<Packet>? = null
    @Volatile private var otaCh: Channel<ByteArray>? = null

    // Mutex ensures only one connect() runs at a time, making the rate-limit check atomic.
    private val connectMutex = Mutex()
    private var lastConnectMs = 0L
    private var retryCount = 0

    // OTA write completions — one per connection; drains before each OTA session start.
    private val otaWriteCompleteChannel = Channel<Int>(capacity = 1)

    // Service/characteristic discovery: true if extended service 7006 found, else use base 6006
    @Volatile private var is8003Server7006 = false

    override val isConnected: Boolean get() = connectionState.value.isConnected

    // ── Scanning ──────────────────────────────────────────────────────────────

    /**
     * Start a BLE scan. Discovered devices are emitted on [scanResultFlow].
     *
     * @param autoConnect If true, connects to the first device found immediately.
     *   Set to false in the Pairing screen so the user can choose which device to pair.
     */
    fun startScan(autoConnect: Boolean = false) {
        scanJob?.cancel()
        Log.i(TAG, "startScan autoConnect=$autoConnect")
        _connectionState.value = BleConnectionState.Scanning
        scanJob = scope.launch {
            val scanner = btAdapter.bluetoothLeScanner ?: run {
                Log.e(TAG, "startScan: BluetoothLeScanner unavailable")
                _connectionState.value = BleConnectionState.Error("Bluetooth unavailable")
                return@launch
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            // The Norm 2 does NOT include the main service UUID in its advertisement packet,
            // so a service-UUID filter yields zero results. Scan with no hardware filter and
            // apply the name-prefix check in the callback instead — matching the original app's
            // behaviour (BluetoothScanEx passes null filters, ConnectWristBandPairTipFragment
            // checks startsWith("Norm") from MOVEMENT_BLE_NAME_PREXS).
            val seen = mutableSetOf<String>()
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val name = result.device.name
                    // Log every named device so we can diagnose what the watch is actually
                    // advertising (the name-prefix filter might be wrong).
                    if (name != null) {
                        Log.d(TAG, "scan raw: name=$name  addr=${result.device.address}  rssi=${result.rssi}")
                    }
                    if (name == null || !name.startsWith("Norm")) return
                    Log.i(TAG, "scan hit (Norm device): $name  ${result.device.address}")
                    if (seen.add(result.device.address)) {
                        scope.launch { _scanResultFlow.emit(result.device) }
                        if (autoConnect) scope.launch { connect(result.device.address) }
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "startScan failed errorCode=$errorCode")
                    _connectionState.value = BleConnectionState.Error("Scan failed ($errorCode)")
                }
            }
            scanner.startScan(null, settings, cb)
            delay(BleConstants.SCAN_TIMEOUT_MS)
            scanner.stopScan(cb)
            Log.i(TAG, "Scan timeout — stopping")
            if (_connectionState.value is BleConnectionState.Scanning)
                _connectionState.value = BleConnectionState.Disconnected
        }
    }

    fun stopScan() { Log.i(TAG, "stopScan"); scanJob?.cancel(); scanJob = null }

    // ── Connection ────────────────────────────────────────────────────────────

    override suspend fun connect(mac: String) {
        // Mutex makes the timestamp check + update atomic — prevents two coroutines from
        // both passing the rate-limit simultaneously (was a race with plain @Volatile).
        if (!connectMutex.tryLock()) {
            Log.d(TAG, "connect($mac): skipped — another connect() in progress")
            return
        }
        try {
            val now = System.currentTimeMillis()
            if (now - lastConnectMs < BleConstants.MIN_CONNECT_INTERVAL_MS) {
                Log.d(TAG, "connect($mac): rate-limited (${now - lastConnectMs}ms < ${BleConstants.MIN_CONNECT_INTERVAL_MS}ms)")
                return
            }
            lastConnectMs = now
        } finally {
            connectMutex.unlock()
        }

        // New connection generation — invalidates any in-flight watchdog/callbacks from a prior attempt.
        val myGen = ++connGen
        discoveryWatchdog?.cancel()
        Log.i(TAG, "connect($mac) retryCount=$retryCount gen=$myGen")
        val device = runCatching { btAdapter.getRemoteDevice(mac) }.getOrNull()
            ?: run { Log.e(TAG, "connect($mac): getRemoteDevice failed"); return }
        _connectionState.value = BleConnectionState.Connecting(device)

        // Ensure the watch is bonded first (it requires it — see BondingPolicy /
        // bluetooth_bond/BluetoothUtils.smali). Best-effort: an already-bonded device
        // returns immediately; a failure is logged but does not abort the connect,
        // since the GATT connection may still proceed and trigger bonding itself.
        when (val bond = bonder.ensureBonded(mac)) {
            is BondResult.Failed -> Log.w(TAG, "connect($mac): bonding failed (${bond.reason}); continuing")
            else -> Log.d(TAG, "connect($mac): bond=$bond")
        }

        // Each connection gets its own descriptor-write channel.
        // localDescCh is passed through the call chain so a second connect() call replacing
        // descWriteCh on a concurrent reconnect doesn't orphan the in-flight descriptor write.
        val localDescCh = Channel<Int>(Channel.CONFLATED)

        // Close old forwarding channels — this causes the for-in-channel coroutines from the
        // previous connection to exit cleanly rather than leaking into the singleton scope.
        packetCh?.close()
        otaCh?.close()
        val newPacketCh = Channel<Packet>(64)
        val newOtaCh = Channel<ByteArray>(64)
        packetCh = newPacketCh
        otaCh = newOtaCh
        scope.launch { for (p in newPacketCh) _parsedFlow.emit(p) }
        scope.launch { for (b in newOtaCh) _otaFlow.emit(b) }

        val q = BleWriteQueue(scope, _parsedFlow).also { queue = it; it.start() }

        val cb = BleGattCallback(
            onConnectionStateChange = { g, connected ->
                if (connected) {
                    _connectionState.value = BleConnectionState.Discovering(device)
                    g.discoverServices()
                    startDiscoveryWatchdog(g, mac, myGen)
                } else {
                    scope.launch { handleDisconnect(mac, myGen) }
                }
            },
            onServicesDiscovered = { g -> scope.launch { setupNotifications(g, device, localDescCh) } },
            onCommandWriteComplete = { status -> q.onCommandWriteComplete(status) },
            onOtaWriteComplete = { status -> otaWriteCompleteChannel.trySend(status) },
            onDescriptorWriteComplete = { status -> localDescCh.trySend(status) },
            packetChannel = newPacketCh,
            otaNotifyChannel = newOtaCh,
        )

        gatt?.close()
        val newGatt = device.connectGatt(context, false, cb, BluetoothDevice.TRANSPORT_LE)
            ?: run {
                _connectionState.value = BleConnectionState.Error("connectGatt returned null")
                return
            }
        gatt = newGatt
        q.attach(newGatt)
    }

    // Launches the discovery/setup watchdog for connection generation [gen]. If the phase hasn't
    // reached Ready by DISCOVERY_TIMEOUT_MS (and this is still the current generation), it forces
    // the normal disconnect/retry path so the UI doesn't hang at "Setting up…".
    private fun startDiscoveryWatchdog(g: BluetoothGatt, mac: String, gen: Int) {
        discoveryWatchdog?.cancel()
        discoveryWatchdog = scope.launch {
            delay(DISCOVERY_TIMEOUT_MS)
            if (gen == connGen && _connectionState.value is BleConnectionState.Discovering) {
                Log.w(TAG, "Discovery/setup stalled >${DISCOVERY_TIMEOUT_MS}ms (gen=$gen) — forcing reconnect. " +
                        "Likely the watch dropped the link mid-discovery (another GATT client holding it?).")
                runCatching { g.disconnect() }
                handleDisconnect(mac, gen)
            }
        }
    }

    private suspend fun handleDisconnect(mac: String, gen: Int) {
        if (gen != connGen) {
            Log.d(TAG, "handleDisconnect($mac): stale gen=$gen (current=$connGen) — ignoring")
            return
        }
        // Invalidate this generation immediately so a duplicate callback (e.g. the GATT
        // disconnect arriving after the watchdog already forced recovery) is rejected.
        connGen++
        discoveryWatchdog?.cancel()
        Log.i(TAG, "handleDisconnect($mac) — closing GATT, retryCount=$retryCount")
        gatt?.close(); gatt = null
        queue?.detach()
        packetCh?.close(); packetCh = null
        otaCh?.close(); otaCh = null
        retryCount++
        if (retryCount <= 3) {
            val delayMs = minOf(retryCount * 2_000L, 10_000L)
            Log.i(TAG, "Reconnecting to $mac in ${delayMs}ms (attempt $retryCount/3)")
            _connectionState.value = BleConnectionState.Error("Disconnected", retryCount)
            delay(delayMs)
            connect(mac)
        } else {
            Log.w(TAG, "Max reconnect attempts (3) reached for $mac — giving up")
            retryCount = 0
            _connectionState.value = BleConnectionState.Disconnected
        }
    }

    // localDescCh is passed in (not read from an instance field) so it stays bound to
    // this specific connection even if a concurrent reconnect replaces the field.
    private suspend fun setupNotifications(
        g: BluetoothGatt,
        device: BluetoothDevice,
        localDescCh: Channel<Int>,
    ) {
        // Service discovery: detect which service is available (6006 base, 7006 extended)
        // Source: AppsCommDevice.smali:296-360
        val hasBase = g.getService(BleConstants.SERVICE_MAIN) != null
        val hasExtended = g.getService(BleConstants.SERVICE_EXTEND) != null
        is8003Server7006 = hasExtended
        Log.i(TAG, "Service discovery: base(6006)=$hasBase extended(7006)=$hasExtended → is8003Server7006=$is8003Server7006")

        if (!hasBase && !hasExtended) {
            Log.e(TAG, "Neither base (6006) nor extended (7006) service found — cannot communicate")
            return
        }

        val mainSvc = g.getService(BleConstants.SERVICE_MAIN) ?: run {
            Log.w(TAG, "Main service 6006 not found, trying extended 7006")
            g.getService(BleConstants.SERVICE_EXTEND)
        } ?: run {
            Log.e(TAG, "Both services unavailable after discovery")
            return
        }

        // Enable notifications on 8002 and 8004 from whichever service is available
        listOf(BleConstants.CHAR_NOTIFY_8002, BleConstants.CHAR_NOTIFY_8004).forEach { uuid ->
            enableNotify(g, mainSvc.getCharacteristic(uuid), localDescCh)
        }

        // Extended service may have 8005 (transparent data channel)
        g.getService(BleConstants.SERVICE_EXTEND)?.let { extSvc ->
            enableNotify(g, extSvc.getCharacteristic(BleConstants.CHAR_8005), localDescCh)
            Log.d(TAG, "Extended service 7006 found — enabled notification on 8005")
        }

        // After the watch reboots into DFU bootloader the Apollo service (0x1530) appears.
        // Enable notifications on both OTA chars so ACKs from the watch reach ApolloOtaProtocol.
        // On a normal (non-OTA) connection this service is absent and the block is skipped.
        g.getService(BleConstants.SERVICE_APOLLO_DFU)?.let { dfuSvc ->
            listOf(BleConstants.CHAR_APOLLO_1531, BleConstants.CHAR_APOLLO_1532).forEach { uuid ->
                enableNotify(g, dfuSvc.getCharacteristic(uuid), localDescCh)
            }
        }

        discoveryWatchdog?.cancel()
        retryCount = 0
        _connectionState.value = BleConnectionState.Ready(device, device.name ?: "Norm 2")
        Log.i(TAG, "BLE ready — notifications enabled" +
                if (g.getService(BleConstants.SERVICE_APOLLO_DFU) != null) " (main + DFU)" else " (main)")
    }

    private suspend fun enableNotify(
        g: BluetoothGatt,
        char: BluetoothGattCharacteristic?,
        localDescCh: Channel<Int>,
    ) {
        char ?: return
        g.setCharacteristicNotification(char, true)
        char.getDescriptor(BleConstants.CCCD)?.let { desc ->
            @Suppress("DEPRECATION")
            desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(desc)
            runCatching { withTimeout(3_000L) { localDescCh.receive() } }
                .onFailure { Log.w(TAG, "Descriptor write timed out for ${char.uuid}") }
        }
    }

    override fun disconnect() {
        Log.i(TAG, "disconnect: user-initiated")
        connGen++              // invalidate any in-flight watchdog/callbacks
        discoveryWatchdog?.cancel()
        gatt?.disconnect(); gatt?.close(); gatt = null
        queue?.detach()
        packetCh?.close(); packetCh = null
        otaCh?.close(); otaCh = null
        retryCount = 0
        _connectionState.value = BleConnectionState.Disconnected
    }

    // ── Command send ──────────────────────────────────────────────────────────

    /**
     * Send a framed command and await the matching response packet.
     *
     * Routes through [BleWriteQueue] so the actor serialises all command traffic,
     * handles MTU chunking, and subscribes to [parsedFlow] *before* writing
     * (avoiding the race where the watch replies before our subscriber is active).
     *
     * Characteristic selection: if extended service 7006 is available, writes to 8003;
     * otherwise writes to 8001 (source: AppsCommDevice.smali:296-360).
     */
    override suspend fun sendAndAwait(
        cmd: CommandCode,
        action: Action,
        payload: ByteArray,
        timeoutMs: Long,
    ): Packet = sendAndAwait(cmd, action, payload, timeoutMs, urgent = false)

    suspend fun sendAndAwait(
        cmd: CommandCode,
        action: Action,
        payload: ByteArray = byteArrayOf(),
        timeoutMs: Long = BleConstants.WRITE_TIMEOUT_MS,
        urgent: Boolean = false,
    ): Packet {
        if (!connectionState.value.isConnected) throw IllegalStateException("Not connected")
        val q = queue ?: throw IllegalStateException("Write queue not initialised")
        val bytes = PacketBuilder.build(cmd, action, payload)
        // Select write characteristic based on service discovery result
        val charUuid = if (is8003Server7006) BleConstants.CHAR_WRITE_8003 else BleConstants.CHAR_WRITE_8001
        return q.enqueue(
            BleRequest(
                bytes = bytes,
                charUuid = charUuid,
                expectedCmd = cmd,
                timeoutMs = timeoutMs,
                urgent = urgent,
            )
        )
    }

    /**
     * Fire-and-forget write to a specific characteristic.
     * Only use for single-MTU payloads where no response is needed
     * (e.g., OTA control bytes, clock sync, find-device).
     */
    override fun writeToChar(bytes: ByteArray, charUuid: java.util.UUID) {
        val g = gatt ?: run { Log.w(TAG, "writeToChar: GATT null, dropping write to ${charUuid.shortId()}"); return }
        val char = g.services?.flatMap { it.characteristics }
            ?.firstOrNull { it.uuid == charUuid }
            ?: run { Log.w(TAG, "writeToChar: char ${charUuid.shortId()} not found in GATT table"); return }
        Log.d(TAG, "→ writeToChar ${charUuid.shortId()} hex=${bytes.toHex()}")
        @Suppress("DEPRECATION")
        char.value = bytes
        @Suppress("DEPRECATION")
        g.writeCharacteristic(char)
    }

    /**
     * Suspend write for OTA: sends bytes in MTU-sized chunks, awaiting
     * [onCharacteristicWrite] between each (WRITE_WITH_RESPONSE on 0x1531).
     */
    suspend fun writeToCharAwait(
        bytes: ByteArray,
        charUuid: java.util.UUID,
        mtu: Int = BleConstants.MTU_DEFAULT,
        timeoutPerChunkMs: Long = 5_000L,
    ) {
        val g = gatt ?: throw IllegalStateException("GATT not connected")
        val char = g.services?.flatMap { it.characteristics }
            ?.firstOrNull { it.uuid == charUuid }
            ?: throw IllegalStateException("Characteristic $charUuid not found")
        val totalChunks = (bytes.size + mtu - 1) / mtu
        Log.d(TAG, "writeToCharAwait ${charUuid.shortId()} totalBytes=${bytes.size} mtu=$mtu chunks=$totalChunks")
        var offset = 0
        var chunkNum = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + mtu, bytes.size))
            chunkNum++
            @Suppress("DEPRECATION")
            char.value = chunk
            @Suppress("DEPRECATION")
            g.writeCharacteristic(char)
            val status = withTimeout(timeoutPerChunkMs) { otaWriteCompleteChannel.receive() }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "writeToCharAwait FAILED chunk $chunkNum/$totalChunks offset=$offset status=0x${"%02X".format(status)}")
                throw IOException("OTA write failed: status=0x${"%02X".format(status)} on $charUuid at offset $offset")
            }
            offset += chunk.size
        }
        Log.d(TAG, "writeToCharAwait complete ($chunkNum chunks)")
    }

    /**
     * Drain any stale OTA write completions from a previous cancelled session
     * before starting a new OTA. Call once at the beginning of [ApolloOtaProtocol.flash].
     */
    fun drainOtaWriteChannel() {
        while (otaWriteCompleteChannel.tryReceive().isSuccess) { /* drain */ }
    }

    private fun java.util.UUID.shortId() = toString().substring(4, 8).uppercase()
    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }

    /** Wait until the Apollo DFU service appears after the bootloader reboots. */
    suspend fun waitForDfuService(timeoutMs: Long = 30_000L) {
        withTimeout(timeoutMs) {
            connectionState.filter {
                it is BleConnectionState.Ready &&
                        gatt?.getService(BleConstants.SERVICE_APOLLO_DFU) != null
            }.first()
        }
    }
}
