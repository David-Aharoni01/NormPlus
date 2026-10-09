package com.normplus.ble

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
import com.normplus.protocol.Action
import com.normplus.protocol.CommandCode
import com.normplus.protocol.Packet
import com.normplus.protocol.PacketBuilder
import com.normplus.protocol.WatchTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "BleManager"

// Settle delay between CONNECTED and discoverServices().
//
// This watch hides its entire GATT database until the bonded link is ENCRYPTED. On a fresh
// connection the Android SMP/encryption setup is racy on this device — the stack reports
// `Encrypted:T` but then `smp skipping encryption enable`, so the link often isn't truly
// encrypted on the first 1-2 attempts and discovery hangs (empty database). No per-attempt
// settle reliably fixes this (even 2s still hangs); only a fresh connection cycle clears the
// stuck SMP state. So we keep the settle short and lean on FAST detect-and-retry instead.
private const val DISCOVERY_SETTLE_MS = 700L

// How long after discoverServices() to wait before declaring the attempt stuck. A healthy
// (encrypted) discovery returns in ~300ms. Total connect time is gated by how long the racy SMP
// encryption takes to settle (~8-11s on a cold start), NOT by how fast we detect/retry — measured:
// faster detection just churns more attempts to reach the same moment. So we keep detection
// unhurried (gentler on the BT stack) rather than thrashing. Plus settle = the "Setting up…" budget.
private const val DISCOVERY_TIMEOUT_MS = DISCOVERY_SETTLE_MS + 3_500L

// The watch hides its services until the bonded link is encrypted, and the Samsung BT stack's
// encryption for this device is racy — it can take several seconds / a few connection cycles to
// settle on a cold start (a BT-adapter reset clears it instantly). Counter-intuitively, fast
// retries make it WORSE (they thrash SMP); moderate spacing lets encryption settle. So we allow
// enough spaced attempts to span that window. Reset to 0 on a successful connect.
private const val MAX_RECONNECT_ATTEMPTS = 8

// Warm-reconnect delay. When an ALREADY-ESTABLISHED (Ready/encrypted) link drops, the BT stack
// still holds the SMP/encryption state for this device for a short while. Reconnecting almost
// immediately rides that warm state and re-establishes in ~1s — sidestepping the slow cold-connect
// SMP race entirely (the whole point of holding the link alive in BleService). Kept just above
// MIN_CONNECT_INTERVAL_MS so connect()'s rate-limiter never skips the reconnect. Contrast with the
// 1–1.5s *spaced* delays of the cold path, where fast retries actively make the SMP race worse.
private const val WARM_RECONNECT_DELAY_MS = 250L

// A connection that stays Ready at least this long is treated as "stable" — a later drop is a
// genuine warm drop, not a flap. Shorter-lived Ready periods count as flaps (see below).
private const val WARM_STABLE_MS = 5_000L

// After this many consecutive flaps (Ready that dropped before WARM_STABLE_MS), stop taking the
// fast 250ms warm-reconnect path and fall through to the bounded, spaced cold-retry path. Without
// this, a chronically flapping link reconnects every ~250ms forever (retryCount is reset to 0 on
// every warm drop), pinning the radio and never giving up.
private const val MAX_CONSECUTIVE_WARM_FLAPS = 5

// What a record stream asks of the link (#86), measured on the physical watch syncing 952 sport
// records from the AVD: 139 s as the link was, 71 s with the MTU, 32 s with the MTU and the
// priority re-asserted.
//
// The ATT MTU: at 23 a 34-byte sport frame is two notifications; at 247 it is one. Asked once
// per connection, at the first stream, not at connect (the cold-connect story, #48). The
// official app never asks (PBluetooth.requestMtu has no caller).
private const val STREAM_MTU = 247
// The connection interval: the watch streams about 32 frames/s at 15 ms, but some 20 s after
// every connection it asks for 120-180 ms, latency 2 (its own table at 0x000CE1F4), and Android
// grants it -- 10 frames/s. HIGH priority at the start of a stream does not stop that request;
// asking again does win the link back, and in every stream measured the watch asked only once,
// so a stream re-asserts HIGH every few seconds until it ends.
private const val STREAM_PRIORITY_REASSERT_MS = 4_000L

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

    // Whether the CURRENT connection ever reached Ready (a fully-encrypted, service-discovered
    // link). Distinguishes a "warm drop" (an established link that fell over — reconnect fast on
    // the still-warm SMP state) from a "cold failure" (an attempt that never came up — use the
    // slow, spaced retry path). Reset at the start of every connect(); set in setupNotifications().
    @Volatile private var reachedReady = false

    // Flapping-link guard for the warm-reconnect path: when the current connection reached Ready,
    // and how many consecutive short-lived (flapping) Ready periods we've seen. See
    // WARM_STABLE_MS / MAX_CONSECUTIVE_WARM_FLAPS.
    @Volatile private var readyAtMs = 0L
    private var consecutiveWarmFlaps = 0

    // Forwarding channels from GATT callback into the shared flows.
    // Stored so they can be closed on disconnect, which stops the forwarding coroutines cleanly.
    @Volatile private var packetCh: Channel<Packet>? = null
    @Volatile private var otaCh: Channel<ByteArray>? = null

    // Guards only the rate-limit check (timestamp read + update) so two coroutines can't both pass
    // it simultaneously. It is NOT held across the whole connect() — that would block for the
    // bonding/connect latency (up to ~30s on a first bond). A connect() superseded by a later one
    // is instead neutralised by the connGen generation guard, not by mutual exclusion here.
    private val connectMutex = Mutex()
    private var lastConnectMs = 0L
    private var retryCount = 0

    // OTA write completions — one per connection; drains before each OTA session start.
    private val otaWriteCompleteChannel = Channel<Int>(capacity = 1)
    /** Results of [requestMtu], from [BleGattCallback.onMtuChanged]. */
    private val mtuChannel = Channel<Int>(Channel.CONFLATED)
    /** The ATT MTU of the current link: 23 until something negotiates more. */
    @Volatile var attMtu: Int = 23
        private set

    // Service/characteristic discovery: true if extended service 7006 found, else use base 6006
    @Volatile private var is8003Server7006 = false

    override val isConnected: Boolean get() = connectionState.value.isConnected

    /**
     * The resolved main-channel write characteristic for the current connection: 0x8003 when the
     * extended 7006 service is present, else 0x8001 (source: AppsCommDevice.smali:296-360).
     *
     * Exposed so fire-and-forget writers (the OTA-mode trigger, clock sync, notification pushes)
     * target the SAME characteristic the command queue uses. Hardcoding 0x8001 silently fails on
     * watches that only expose the 7006/0x8003 service — `writeToChar` can't find 0x8001 and drops
     * the write, which (for the OTA-mode trigger) means the watch never enters the bootloader.
     */
    val commandWriteChar: java.util.UUID
        get() = if (is8003Server7006) BleConstants.CHAR_WRITE_8003 else BleConstants.CHAR_WRITE_8001

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
        reachedReady = false
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

        val q = BleWriteQueue(scope, _parsedFlow, streamLink).also { queue = it; it.start() }

        val cb = BleGattCallback(
            onConnectionStateChange = { g, connected ->
                if (connected) {
                    _connectionState.value = BleConnectionState.Discovering(device)
                    startDiscoveryWatchdog(g, mac, myGen)
                    // Discover after a short settle delay so the bonded link finishes encrypting
                    // (the watch refuses GATT discovery on an unencrypted link). With autoConnect
                    // the stack establishes encryption properly, so the first attempt succeeds.
                    scope.launch {
                        delay(DISCOVERY_SETTLE_MS)
                        if (myGen == connGen) {
                            Log.d(TAG, "discoverServices() (gen=$myGen, after ${DISCOVERY_SETTLE_MS}ms settle)")
                            g.discoverServices()
                        }
                    }
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
            onMtuChanged = { mtu, status ->
                if (status == BluetoothGatt.GATT_SUCCESS) attMtu = mtu
                mtuChannel.trySend(if (status == BluetoothGatt.GATT_SUCCESS) mtu else attMtu)
            },
        )
        attMtu = 23

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
                Log.w(TAG, "Discovery stalled >${DISCOVERY_TIMEOUT_MS}ms (gen=$gen) — forcing reconnect. " +
                        "Watch exposes no services until the bonded link is encrypted; SMP encryption " +
                        "is racy on a cold start and may take a few connection cycles to settle.")
                runCatching { g.disconnect() }
                // Run recovery in a SEPARATE coroutine: handleDisconnect()/connect() call
                // discoveryWatchdog?.cancel(), which would otherwise cancel THIS coroutine
                // mid-retry and silently abort the reconnect.
                scope.launch { handleDisconnect(mac, gen) }
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

        // Was this an established, encrypted (Ready) link that fell over — or a cold attempt that
        // never came up? Capture before resetting: a warm drop reconnects fast on the still-warm
        // SMP state; a cold failure must use the slow, spaced retry path below.
        val wasWarm = reachedReady
        reachedReady = false

        Log.i(TAG, "handleDisconnect($mac) — closing GATT, wasWarm=$wasWarm retryCount=$retryCount")
        gatt?.close(); gatt = null
        queue?.detach()
        packetCh?.close(); packetCh = null
        otaCh?.close(); otaCh = null

        if (wasWarm) {
            // Distinguish a genuine warm drop from a flapping link: a connection that held Ready
            // for at least WARM_STABLE_MS resets the flap counter; a shorter-lived one increments
            // it. After MAX_CONSECUTIVE_WARM_FLAPS we stop spinning the fast path (which resets
            // retryCount every time and so never gives up) and fall through to the bounded cold path.
            val readyDurationMs = System.currentTimeMillis() - readyAtMs
            consecutiveWarmFlaps = if (readyDurationMs >= WARM_STABLE_MS) 0 else consecutiveWarmFlaps + 1

            if (consecutiveWarmFlaps <= MAX_CONSECUTIVE_WARM_FLAPS) {
                // The link was up and encrypted; the BT stack still holds the SMP/encryption state.
                // Reconnect right away to ride it before the stack tears the keys down (which would
                // force a slow cold connect). This is what makes warm reconnects near-instant — and
                // why BleService holds the link alive. A fresh budget: if this fast attempt itself
                // fails to reach Ready, the next handleDisconnect sees wasWarm=false and falls
                // through to the cold spaced-retry path below.
                retryCount = 0
                _connectionState.value = BleConnectionState.Error("Connection lost — reconnecting", 0)
                Log.i(TAG, "Warm reconnect to $mac in ${WARM_RECONNECT_DELAY_MS}ms (SMP still warm, readyFor=${readyDurationMs}ms)")
                delay(WARM_RECONNECT_DELAY_MS)
                connect(mac)
                return
            }
            // Link keeps flapping — stop spinning the fast path and fall through to the bounded,
            // spaced cold-retry path below (which gives up after MAX_RECONNECT_ATTEMPTS).
            Log.w(TAG, "Warm link flapping ($consecutiveWarmFlaps consecutive Ready periods < ${WARM_STABLE_MS}ms) — switching to cold spaced retries")
        }

        retryCount++
        if (retryCount <= MAX_RECONNECT_ATTEMPTS) {
            // Moderate spacing: give the racy SMP/encryption state time to settle between
            // attempts. Fast retries thrash it and take LONGER; ~1.5s pauses let it recover.
            val delayMs = when (retryCount) {
                1 -> 1_000L
                else -> 1_500L
            }
            Log.i(TAG, "Reconnecting to $mac in ${delayMs}ms (attempt $retryCount/$MAX_RECONNECT_ATTEMPTS)")
            _connectionState.value = BleConnectionState.Error("Disconnected", retryCount)
            delay(delayMs)
            connect(mac)
        } else {
            Log.w(TAG, "Max reconnect attempts ($MAX_RECONNECT_ATTEMPTS) reached for $mac — giving up")
            retryCount = 0
            consecutiveWarmFlaps = 0
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
            // Empty discovery (the classic first-connect failure). Leave the watchdog running so
            // it forces a retry — do NOT cancel it here.
            Log.e(TAG, "Neither base (6006) nor extended (7006) service found — cannot communicate")
            return
        }
        // Discovery genuinely succeeded. Stop the watchdog now: the remaining notification setup
        // has its own per-write timeouts and cannot hang, so it needs no watchdog coverage (and
        // a slow-but-progressing setup must not trip a false reconnect).
        discoveryWatchdog?.cancel()

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

        // The Apollo DFU service (0x1530) is part of the running application's GATT table --
        // UPGRADE_MODE is a mode switch, not a reboot into a bootloader. Every OTA reply is a
        // notification on 0x1531 (0x1532 has no CCCD; enableNotify skips it), so it is enabled
        // here, before anything is written, for ApolloOtaSession via BleOtaTransport.
        g.getService(BleConstants.SERVICE_APOLLO_DFU)?.let { dfuSvc ->
            listOf(BleConstants.CHAR_APOLLO_1531, BleConstants.CHAR_APOLLO_1532).forEach { uuid ->
                enableNotify(g, dfuSvc.getCharacteristic(uuid), localDescCh)
            }
        }

        retryCount = 0
        reachedReady = true   // link is up & encrypted — a later drop is a "warm" drop (fast reconnect)
        readyAtMs = System.currentTimeMillis()
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
        reachedReady = false   // a deliberate teardown must not trigger a warm reconnect
        consecutiveWarmFlaps = 0
        discoveryWatchdog?.cancel()
        gatt?.disconnect(); gatt?.close(); gatt = null
        queue?.detach()
        packetCh?.close(); packetCh = null
        otaCh?.close(); otaCh = null
        retryCount = 0
        _connectionState.value = BleConnectionState.Disconnected
    }

    /**
     * Lightweight link keep-alive. Triggers a remote-RSSI read on the live GATT — pure radio
     * activity that touches neither the command queue nor the 0x6F protocol, so it can never
     * time out or fail the connection. Used by [BleService] to keep an idle link warm. No-op if
     * not connected. The result lands in [BleGattCallback.onReadRemoteRssi] (logged only).
     */
    fun readRemoteRssi() {
        val g = gatt ?: return
        runCatching { g.readRemoteRssi() }
            .onFailure { Log.w(TAG, "readRemoteRssi failed: ${it.message}") }
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
        // Select write characteristic based on service discovery result (see [commandWriteChar]).
        val charUuid = commandWriteChar
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
     * Send one command and collect the stream of responses it provokes (see
     * [WatchTransport.sendAndStream]): queued like [sendAndAwait], and the queue is held until the
     * stream ends, so nothing is written to the watch in the middle of it.
     */
    override fun sendAndStream(
        cmd: CommandCode,
        action: Action,
        payload: ByteArray,
        idleTimeoutMs: Long,
        isLast: (Packet) -> Boolean,
    ): Flow<Packet> = flow {
        if (!connectionState.value.isConnected) throw IllegalStateException("Not connected")
        val q = queue ?: throw IllegalStateException("Write queue not initialised")
        // UNLIMITED: the queue's actor must never wait on a slow collector (BleWriteQueue.processStream).
        val responses = Channel<Packet>(Channel.UNLIMITED)
        q.enqueueStream(
            BleRequest(
                bytes = PacketBuilder.build(cmd, action, payload),
                charUuid = commandWriteChar,
                expectedCmd = cmd,
                timeoutMs = idleTimeoutMs,
                stream = responses,
                isLast = isLast,
            )
        )
        // Ends when the queue closes the channel, rethrowing what it closed it with. A collector
        // that leaves early cancels the channel; the actor then drains the stream into nothing.
        emitAll(responses)
    }

    /**
     * Serialised, MTU-chunked, fire-and-forget command write. Goes through [BleWriteQueue] exactly
     * like [sendAndAwait] — so it can't race the command-write path on the shared characteristic and
     * is chunked across MTU boundaries — but does NOT wait for a protocol response. Use for SET
     * pushes the watch may not ACK and that can exceed one MTU, e.g. notification pushes (a raw
     * [writeToChar] would send them as a single, truncated write).
     */
    suspend fun sendCommandNoResponse(
        cmd: CommandCode,
        action: Action,
        payload: ByteArray = byteArrayOf(),
        urgent: Boolean = false,
    ) {
        if (!connectionState.value.isConnected) {
            Log.w(TAG, "sendCommandNoResponse($cmd): not connected, dropping")
            return
        }
        val q = queue ?: run { Log.w(TAG, "sendCommandNoResponse($cmd): queue not initialised"); return }
        // Truly fire-and-forget: the queue still awaits the write completing, which can throw a
        // BleTimeoutException if the link drops mid-write (e.g. a reconnect flap). That MUST NOT
        // propagate — callers like the notification forwarder run in a SupervisorJob scope where an
        // uncaught exception crashes the app. Swallow + log; there's no result to deliver anyway.
        runCatching {
            q.enqueue(
                BleRequest(
                    bytes = PacketBuilder.build(cmd, action, payload),
                    charUuid = commandWriteChar,
                    expectedCmd = cmd,
                    timeoutMs = BleConstants.WRITE_TIMEOUT_MS,
                    urgent = urgent,
                    awaitResponse = false,
                )
            )
        }.onFailure { Log.w(TAG, "sendCommandNoResponse($cmd): write failed (${it.message})") }
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
     * Suspend write for OTA: sends bytes in [mtu]-sized chunks, awaiting
     * [BleGattCallback.onCharacteristicWrite] between each. The DFU characteristics 0x1531 and
     * 0x1532 have WRITE_WITHOUT_RESPONSE only, so OTA passes [writeType]
     * WRITE_TYPE_NO_RESPONSE; Android still reports each write once the stack has taken it.
     */
    suspend fun writeToCharAwait(
        bytes: ByteArray,
        charUuid: java.util.UUID,
        mtu: Int = BleConstants.MTU_DEFAULT,
        timeoutPerChunkMs: Long = 5_000L,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
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
            char.writeType = writeType
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
     * What a record stream asks of the link (#86): the larger MTU, and a fast interval for as long
     * as the stream lasts ([STREAM_MTU], [STREAM_PRIORITY_REASSERT_MS]).
     *
     * When the stream ends the link goes to LOW_POWER (100-125 ms, latency 2), the nearest public
     * setting to the 120-180 ms, latency 2 the watch asks for itself: BALANCED would hold an
     * always-on connection at 50 ms, faster than the watch wants for its battery.
     */
    private val streamLink = object : BulkLink {
        private var reassert: Job? = null

        override suspend fun begin() {
            val g = gatt ?: return
            if (STREAM_MTU > attMtu) Log.i(TAG, "stream: ATT MTU ${requestMtu(STREAM_MTU)}")
            if (!g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)) {
                Log.w(TAG, "stream: requestConnectionPriority(HIGH) was not accepted")
            }
            reassert?.cancel()
            reassert = scope.launch {
                while (true) {
                    delay(STREAM_PRIORITY_REASSERT_MS)
                    gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                }
            }
        }

        override fun end() {
            reassert?.cancel()
            reassert = null
            gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        }
    }

    /**
     * Ask for an ATT MTU of [mtu] and return what the link settles on (the current one if the
     * request fails or nothing answers within [timeoutMs]). An OTA of the resource partition
     * writes 128 bytes at a time, which needs at least 131; tools/watchemu's reference client
     * asks for 247.
     */
    suspend fun requestMtu(mtu: Int, timeoutMs: Long = 5_000L): Int {
        val g = gatt ?: throw IllegalStateException("GATT not connected")
        while (mtuChannel.tryReceive().isSuccess) { /* drain */ }
        if (!g.requestMtu(mtu)) {
            Log.w(TAG, "requestMtu($mtu) was not accepted; staying at $attMtu")
            return attMtu
        }
        return withTimeoutOrNull(timeoutMs) { mtuChannel.receive() } ?: attMtu
    }

    /**
     * Drain any stale OTA write completions from a previous cancelled session
     * before starting a new OTA. [BleOtaTransport.openDfu] calls it.
     */
    fun drainOtaWriteChannel() {
        while (otaWriteCompleteChannel.tryReceive().isSuccess) { /* drain */ }
    }

    private fun java.util.UUID.shortId() = toString().substring(4, 8).uppercase()
    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
}
