package com.normplus.ui.screens.firstrun

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.ble.BleConnectionState
import com.normplus.ble.BleManager
import com.normplus.ble.BleService
import com.normplus.ble.BondState
import com.normplus.ble.BondStates
import com.normplus.data.preferences.WatchPreferences
import com.normplus.domain.usecase.BindResult
import com.normplus.domain.usecase.BindWatchUseCase
import com.normplus.status.Blocker
import com.normplus.status.WatchStatusSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "FirstRunViewModel"

/**
 * The first run (#98): one step at a time, each permission asked where it is needed.
 *
 * The connection is BleManager's and the bind is BindWatchUseCase's, used exactly as the old
 * pairing screen used them: scan, `connect(mac)` (which bonds first through AndroidBonder, and
 * retries on its own), the MAC saved on Ready, and the bind run once per link when the link
 * comes up, with Try again and Continue anyway on a failure. What is new is only what the
 * person sees of it: the bond's state ([BondStates], read only) for Android's pairing request,
 * and BleManager's retry count, worded by [connectPhase].
 *
 * The permissions are asked by the screen through the shell's fixes (`rememberStatusFixes`),
 * which sample the status again when Android's dialog or settings page returns; whether each
 * is granted is read from [WatchStatusSource]'s blockers, the same facts the fix-its on Watch
 * show afterwards for anything skipped here.
 */
@HiltViewModel
@SuppressLint("MissingPermission")
class FirstRunViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bleManager: BleManager,
    private val watchPreferences: WatchPreferences,
    private val bindWatch: BindWatchUseCase,
    private val bondStates: BondStates,
    private val statusSource: WatchStatusSource,
) : ViewModel() {

    private val _state = MutableStateFlow(FirstRunUiState())
    val state: StateFlow<FirstRunUiState> = _state.asStateFlow()

    private val scanResults = linkedMapOf<String, FoundWatch>()
    private var scanCollectJob: Job? = null
    private var bindJob: Job? = null
    private var bondJob: Job? = null
    private var tickJob: Job? = null

    // The Connect step's facts, folded into ConnectPhase by refreshConnect().
    private var link = LinkFact.Idle
    private var attempt = 0
    private var bond = BondState.None
    private var bondingSinceMs = 0L
    private var pairingClosed = false
    private var started = false

    init {
        viewModelScope.launch {
            statusSource.status.collect { status ->
                val b = status.blockers
                val grants = Grants(
                    bluetooth = Blocker.BluetoothPermissionMissing !in b,
                    notifications = Blocker.NotificationsBlocked !in b,
                    notificationAccess = Blocker.NotificationAccessOff !in b,
                    calls = Blocker.CallsNotAllowed !in b,
                    battery = Blocker.BatteryOptimisationOn !in b,
                )
                val before = _state.value
                _state.update { it.copy(grants = grants, bluetoothOn = Blocker.BluetoothOff !in b) }
                onGrantsChanged(before)
            }
        }
        viewModelScope.launch {
            bleManager.connectionState.collect(::onConnectionState)
        }
    }

    // ── Moving through the steps ────────────────────────────────────────────────

    /** The way on from the step on screen: Get started, Continue, Not now, Skip. */
    fun next() {
        val s = _state.value
        val to = nextPlace(Place(s.step, s.notificationPart), s.grants, linked = s.bind != BindPhase.NotStarted)
        if (to == null) finish() else goTo(to)
    }

    /** System Back; false when it is the system's (the screen then lets it through). */
    fun back(): Boolean {
        val s = _state.value
        val to = previousPlace(Place(s.step, s.notificationPart), s.grants) ?: return false
        goTo(to)
        return true
    }

    /** Remembers that [ask] was put to the person, so a refusal reads as denied. */
    fun asked(ask: Ask) {
        _state.update { it.copy(asked = it.asked + ask) }
    }

    /** "Not now" to the battery exemption: it stays a fix-it on Watch, out of the banner. */
    fun notNowBattery() {
        statusSource.dismissBatteryOptimisation()
        next()
    }

    fun notificationAppsOpened() {
        _state.update { it.copy(appsOpened = true) }
    }

    /** Ends the run: the first sync starts (only while connected) and the shell goes to Today. */
    fun finish() {
        if (_state.value.finished) return
        statusSource.sync()
        _state.update { it.copy(finished = true) }
    }

    private fun goTo(place: Place) {
        _state.update { it.copy(step = place.step, notificationPart = place.part) }
        if (place.step == FirstRunStep.Find) startScan()
    }

    /** A permission came through while its step was on screen: on to the next one by itself. */
    private fun onGrantsChanged(before: FirstRunUiState) {
        val s = _state.value
        if (before.grants == s.grants && before.bluetoothOn == s.bluetoothOn) return
        if (s.grants.bluetooth && !before.grants.bluetooth) {
            // What the activity did when the launch request came back with Nearby devices (#97):
            // the connection service starts; with no watch saved yet, it waits for this run.
            runCatching { BleService.start(context) }.onFailure { Log.w(TAG, "service not started: ${it.message}") }
        }
        val granted = when (s.step) {
            FirstRunStep.Bluetooth -> s.grants.bluetooth
            FirstRunStep.Notifications -> when (s.notificationPart) {
                NotificationPart.Post -> s.grants.notifications && !before.grants.notifications
                NotificationPart.Access -> s.grants.notificationAccess && !before.grants.notificationAccess
                NotificationPart.Apps -> false
            }
            FirstRunStep.Calls -> s.grants.calls && !before.grants.calls
            FirstRunStep.Battery -> s.grants.battery && !before.grants.battery
            else -> false
        }
        if (granted) next()
        // Bluetooth came on while looking for the watch: look now.
        if (s.step == FirstRunStep.Find && s.bluetoothOn && !before.bluetoothOn && s.scan != ScanState.Scanning) startScan()
    }

    // ── Find ──────────────────────────────────────────────────────────────────

    fun startScan() {
        val s = _state.value
        if (!s.grants.bluetooth || !s.bluetoothOn) {
            _state.update { it.copy(scan = ScanState.Idle) }
            return
        }
        scanResults.clear()
        scanCollectJob?.cancel()
        _state.update { it.copy(scan = ScanState.Scanning, found = emptyList()) }
        // BleManager.startScan() emits each Norm watch once on scanResultFlow (as for the old screen).
        scanCollectJob = viewModelScope.launch {
            bleManager.scanResultFlow.collect { device ->
                val name = runCatching { device.name }.getOrNull()
                if (scanResults.put(device.address, FoundWatch(name, device.address)) == null) {
                    _state.update { it.copy(found = scanResults.values.toList()) }
                }
            }
        }
        runCatching { bleManager.startScan() }.onFailure {
            Log.w(TAG, "startScan failed: ${it.message}")
            _state.update { st -> st.copy(scan = ScanState.Failed) }
        }
    }

    fun onAddressChange(text: String) {
        _state.update { it.copy(address = text, addressInvalid = false) }
    }

    /** Connect by what was typed or pasted (an address or the QR code's text). */
    fun connectByAddress() {
        val mac = parseWatchAddress(_state.value.address)
        if (mac == null) {
            Log.w(TAG, "connectByAddress: cannot parse '${_state.value.address}'")
            _state.update { it.copy(addressInvalid = true) }
            return
        }
        val name = runCatching { bleManagerName(mac) }.getOrNull()
        connectTo(FoundWatch(name, mac))
    }

    fun connectTo(watch: FoundWatch) {
        Log.i(TAG, "connectTo: ${watch.address}")
        bleManager.stopScan()
        scanCollectJob?.cancel()
        _state.update { it.copy(scan = ScanState.Idle, watch = watch, step = FirstRunStep.Connect, bind = BindPhase.NotStarted) }
        beginAttempt(watch.address)
        viewModelScope.launch { bleManager.connect(watch.address) }
    }

    // ── Connect ────────────────────────────────────────────────────────────────

    /** Try again after the pairing request closed or BleManager gave up: a fresh connect. */
    fun retryConnect() {
        val watch = _state.value.watch ?: return
        bleManager.disconnect()
        beginAttempt(watch.address)
        viewModelScope.launch { bleManager.connect(watch.address) }
    }

    /** Back to Find, to choose another watch or type its address. */
    fun chooseAnotherWatch() {
        bleManager.disconnect()
        bondJob?.cancel()
        tickJob?.cancel()
        _state.update { it.copy(watch = null, bind = BindPhase.NotStarted) }
        goTo(Place(FirstRunStep.Find))
    }

    private fun beginAttempt(mac: String) {
        link = LinkFact.Idle
        attempt = 0
        pairingClosed = false
        started = false
        bondJob?.cancel()
        bondJob = viewModelScope.launch {
            bondStates.of(mac).collect { b ->
                if (b == BondState.Bonding && bond != BondState.Bonding) {
                    bondingSinceMs = System.currentTimeMillis()
                    pairingClosed = false
                }
                // A request that closes without a bond: declined, or the 30 s ran out.
                if (b == BondState.None && bond == BondState.Bonding) pairingClosed = true
                bond = b
                refreshConnect()
            }
        }
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            // The pairing request's seconds left, counted down while it is open.
            while (isActive) {
                delay(1_000L)
                if (bond == BondState.Bonding) refreshConnect()
            }
        }
        refreshConnect()
    }

    private fun refreshConnect() {
        val seconds = if (bond == BondState.Bonding) ((System.currentTimeMillis() - bondingSinceMs) / 1_000L).toInt() else 0
        val phase = connectPhase(link, attempt, bond, seconds, pairingClosed, started)
        _state.update { it.copy(connect = phase, paired = bond == BondState.Bonded) }
    }

    private fun onConnectionState(cs: BleConnectionState) {
        val s = _state.value
        // The scan on the Find step.
        if (s.step == FirstRunStep.Find) {
            when (cs) {
                is BleConnectionState.Disconnected -> if (s.scan == ScanState.Scanning) _state.update { it.copy(scan = ScanState.Finished) }
                is BleConnectionState.Error -> if (s.scan == ScanState.Scanning) _state.update { it.copy(scan = ScanState.Failed) }
                else -> Unit
            }
        }
        // The Connect step's facts.
        link = when (cs) {
            is BleConnectionState.Disconnected -> LinkFact.Idle
            is BleConnectionState.Scanning -> LinkFact.Scanning
            is BleConnectionState.Connecting -> LinkFact.Connecting
            is BleConnectionState.Discovering -> LinkFact.SettingUp
            is BleConnectionState.Ready -> LinkFact.Ready
            is BleConnectionState.Error -> LinkFact.Retrying
        }
        if (cs is BleConnectionState.Error) attempt = cs.retryCount
        if (cs !is BleConnectionState.Disconnected && cs !is BleConnectionState.Scanning) started = true
        refreshConnect()

        if (cs is BleConnectionState.Ready) {
            viewModelScope.launch { watchPreferences.saveDeviceMac(cs.device.address) }
            // The app's bind flow, as on the old pairing screen: a link that comes up here gets
            // the handshake a fresh watch is waiting for. Once per link: Ready can re-emit on a
            // reconnect, and the watch must not see bindStart twice.
            if (bindJob?.isActive != true && _state.value.bind == BindPhase.NotStarted) bind()
            if (_state.value.step == FirstRunStep.Find || _state.value.step == FirstRunStep.Connect) {
                _state.update { it.copy(step = FirstRunStep.Bind, watch = it.watch ?: FoundWatch(cs.deviceName, cs.device.address)) }
            }
        }
    }

    // ── Bind ───────────────────────────────────────────────────────────────────

    /** Run (or re-run, after a failure) the bind handshake on the current link. */
    fun bind() {
        bindJob = viewModelScope.launch {
            _state.update { it.copy(bind = BindPhase.Binding) }
            when (val result = bindWatch.bind()) {
                is BindResult.Bound -> {
                    Log.i(TAG, "bind: $result")
                    _state.update { it.copy(bind = BindPhase.Bound) }
                }
                is BindResult.AlreadyBound -> {
                    Log.i(TAG, "bind: $result")
                    _state.update { it.copy(bind = BindPhase.AlreadyBound) }
                }
                is BindResult.Failed -> {
                    Log.w(TAG, "bind failed at ${result.step}: ${result.message}")
                    _state.update { it.copy(bind = BindPhase.Failed(result.step, result.message)) }
                }
            }
        }
    }

    /** The person's call: go on with a watch that did not bind. */
    fun continueWithoutBind() = next()

    private fun bleManagerName(mac: String): String? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        return adapter?.getRemoteDevice(mac)?.name
    }

    override fun onCleared() {
        bleManager.stopScan()
        scanCollectJob?.cancel()
    }
}
