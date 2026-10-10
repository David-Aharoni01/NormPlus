package com.normplus.ui.screens.today

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.normplus.ble.BleConnectionState
import com.normplus.ble.BleManager
import com.normplus.data.db.dao.HeartRateDao
import com.normplus.data.db.dao.SleepDao
import com.normplus.data.db.dao.SportDao
import com.normplus.data.preferences.WatchPreferences
import com.normplus.protocol.Action
import com.normplus.protocol.CommandCode
import com.normplus.protocol.commands.DeviceDisplayCommand
import com.normplus.protocol.commands.DeviceVersionCommand
import com.normplus.protocol.commands.SwitchSettingCommand
import com.normplus.status.SyncState
import com.normplus.status.WatchStatusSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

private const val TAG = "TodayVM"

/**
 * Everything Today prints (#99). Figures the watch has not reported are null: absent, never
 * zero.
 *
 * @property steps, [calories], [distanceMeters], [activeMinutes] the watch's own today summary
 *   (0x57), live or last known.
 * @property asOfEpochMs set when those figures are the last known ones: "as of 09:12".
 * @property nothingSynced no sync has ever finished and the watch has not reported today.
 * @property canSync the watch is connected and no sync is running: Retry and the pull work.
 */
@Immutable
data class TodayUiState(
    val date: LocalDate,
    val goal: Int = WatchPreferences.DEFAULT_STEP_GOAL,
    val steps: Int? = null,
    val calories: Int? = null,
    val distanceMeters: Int? = null,
    val activeMinutes: Int? = null,
    val asOfEpochMs: Long? = null,
    val sleep: SleepSummary? = null,
    val heartRate: HeartReading? = null,
    val yesterday: DaySteps = DaySteps(date.minusDays(1), null),
    val nothingSynced: Boolean = false,
    val syncing: Boolean = false,
    val canSync: Boolean = false,
    val shortfall: SyncShortfall? = null,
    val imperial: Boolean = false,
)

/** What Today reads from the database for a day. */
private data class Stored(
    val date: LocalDate,
    val sleep: SleepSummary?,
    val heartRate: HeartReading?,
    val yesterdaySteps: Int?,
)

@HiltViewModel
class TodayViewModel @Inject constructor(
    private val bleManager: BleManager,
    private val statusSource: WatchStatusSource,
    private val sportDao: SportDao,
    private val heartRateDao: HeartRateDao,
    private val sleepDao: SleepDao,
    private val prefs: WatchPreferences,
) : ViewModel() {

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val stored = MutableStateFlow(Stored(LocalDate.now(), null, null, null))
    private val dismissedShortfall = MutableStateFlow<Long?>(null)
    private val readMutex = Mutex()

    val state: StateFlow<TodayUiState> = combine(
        statusSource.status,
        stored,
        prefs.todaySummary.map { TodaySummary.decode(it) }.catchAs(null),
        prefs.stepGoal.catchAs(WatchPreferences.DEFAULT_STEP_GOAL),
        combine(prefs.units.catchAs("METRIC"), dismissedShortfall) { u, d -> u to d },
    ) { status, db, storedSummary, goal, (units, dismissed) ->
        val summary = TodayFacts.summaryFor(db.date, storedSummary, zone)
        TodayUiState(
            date = db.date,
            goal = goal,
            steps = summary?.steps,
            calories = summary?.calories,
            distanceMeters = summary?.distanceMeters,
            activeMinutes = summary?.activeMinutes,
            asOfEpochMs = TodayFacts.asOf(summary, status.isReady, System.currentTimeMillis()),
            sleep = TodayFacts.sleep(db.sleep, summary),
            heartRate = TodayFacts.heartRate(db.heartRate, summary),
            yesterday = DaySteps(db.date.minusDays(1), db.yesterdaySteps),
            nothingSynced = status.lastSyncEpochMs == null && summary == null,
            syncing = status.isSyncing,
            canSync = status.isReady && !status.isSyncing,
            shortfall = TodayFacts.shortfall(status.sync, dismissed),
            imperial = units == "IMPERIAL",
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState(LocalDate.now()))

    init {
        loadStored()
        viewModelScope.launch {
            // On every connection: the summary, and the chores the old dashboard did on connect.
            bleManager.connectionState
                .map { it is BleConnectionState.Ready }
                .distinctUntilChanged()
                .collect { ready ->
                    if (ready) {
                        readSummary()
                        connectChores()
                    }
                }
        }
        viewModelScope.launch {
            // After every sync: the synced records changed, and the watch's totals may have too.
            var wasSyncing = false
            statusSource.status.collect { status ->
                if (wasSyncing && status.sync is SyncState.Finished) {
                    loadStored()
                    readSummary()
                    connectChores() // the old dashboard ran them after every sync too
                }
                wasSyncing = status.isSyncing
            }
        }
    }

    /** Pull to refresh, Sync and Retry: the shared sync (WatchStatusSource, #97). */
    fun sync() {
        statusSource.sync()
    }

    fun dismissShortfall(atEpochMs: Long) {
        dismissedShortfall.value = atEpochMs
    }

    /**
     * Today came to the front: a new day may have begun, the records may have changed, and a
     * connected watch's totals are read again when the last reading is no longer live.
     */
    fun onShown() {
        loadStored()
        viewModelScope.launch {
            val last = runCatching { TodaySummary.decode(prefs.todaySummary.first()) }.getOrNull()
            if (last == null || System.currentTimeMillis() - last.readAtEpochMs >= TodayFacts.LIVE_FOR_MS) readSummary()
        }
    }

    private fun loadStored() {
        viewModelScope.launch {
            val today = LocalDate.now()
            runCatching {
                val day = TodayFacts.dayWindow(today, zone)
                val night = TodayFacts.lastNightWindow(today, zone)
                val yesterday = TodayFacts.dayWindow(today.minusDays(1), zone)

                val sessions = sleepDao.querySessions(night.first, night.last).first()
                val sleep = if (sessions.isEmpty()) null else {
                    val breakdown = sleepDao.querySleepBreakdown(night.first, night.last)
                    SleepSummary(
                        asleepMinutes = breakdown?.totalMinutes ?: 0,
                        deepMinutes = breakdown?.let { it.deepSec / 60 },
                        startEpochMs = sessions.minOf { it.startEpoch },
                        endEpochMs = sessions.maxOf { it.endEpoch },
                    )
                }
                val heart = heartRateDao.queryByRange(day.first, day.last).first().lastOrNull()
                    ?.let { HeartReading(it.bpm, it.timestampEpoch) }
                val yesterdaySteps = sportDao.sumSteps(yesterday.first, yesterday.last)
                Stored(today, sleep, heart, yesterdaySteps)
            }.onSuccess { stored.value = it }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.e(TAG, "reading the synced records failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    stored.value = stored.value.copy(date = today)
                }
        }
    }

    /**
     * The watch-face "today" totals, the live cumulative summary the watch shows on its own
     * screen: the source for Today's steps, calories, distance and active minutes (brief §7.1).
     * The synced sport records are activity snippets, not the all-day total.
     * Source: DeviceDisplayData.smali (cmd 0x57), CHECK with [00]: wire 6F 57 70 01 00 00 8F.
     */
    private suspend fun readSummary() = readMutex.withLock {
        if (!bleManager.isConnected) return@withLock
        runCatching {
            bleManager.sendAndAwait(DeviceDisplayCommand.CMD, Action.CHECK, DeviceDisplayCommand.queryPayload())
        }.onSuccess { pkt ->
            // Real data comes back as a CHECK_RESPONSE; a bare generic SET_RESPONSE ack
            // (payload [0x57, status]) means "no data": keep the last reading.
            val summary = if (pkt.action == Action.CHECK_RESPONSE) {
                TodaySummary.fromPayload(pkt.payload, System.currentTimeMillis())
            } else {
                null
            }
            if (summary == null) {
                Log.w(TAG, "0x57 returned no data (action=${pkt.action}, payload=${pkt.payload.toHex()}): keeping the last reading")
            } else {
                Log.i(TAG, "today summary: $summary")
                prefs.saveTodaySummary(summary.encode())
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "reading the today summary failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * What the old dashboard did on every connection, kept as it was (#92: no change to BLE or
     * notification behaviour): the watch's version string for the firmware screen, and the
     * notification kinds switched on in the watch's SwitchSetting mask, without which the
     * watch drops every notification it is sent. Each is on its own, so one failing cannot
     * stop the other.
     */
    private suspend fun connectChores() {
        runCatching {
            // Source: MBluetooth.smali getDeviceFunctionInfo → DeviceVersion(callback, 1, 6):
            // payload [06] (type 6 = the full version info string).
            val verPkt = bleManager.sendAndAwait(CommandCode.DEVICE_VERSION, Action.CHECK, byteArrayOf(6))
            prefs.saveDeviceVersion(DeviceVersionCommand.parseVersionString(verPkt))
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "reading the version failed: ${e.message}")
        }

        // OR the notification kinds into the current mask (non-destructive: every other bit,
        // high bytes included, is kept). The first query right after connect can time out, so
        // it is tried up to three times.
        var current: Int? = null
        repeat(3) {
            if (current != null) return@repeat
            current = runCatching {
                SwitchSettingCommand.parse(
                    bleManager.sendAndAwait(SwitchSettingCommand.CMD, Action.CHECK, SwitchSettingCommand.queryPayload()),
                )
            }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        }
        val cur = current
        if (cur == null) {
            Log.w(TAG, "could not read the switch mask to enable notification kinds")
            return
        }
        val wanted = cur or SwitchSettingCommand.NOTIFICATION_BITS
        if (wanted == cur) {
            Log.d(TAG, "notification kinds already enabled (mask=0x%08X)".format(cur))
            return
        }
        runCatching {
            bleManager.sendAndAwait(SwitchSettingCommand.CMD, Action.SET, SwitchSettingCommand.setPayload(wanted))
        }.onSuccess {
            Log.i(TAG, "enabled notification kinds on the watch: 0x%08X -> 0x%08X".format(cur, wanted))
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Log.w(TAG, "enabling notification kinds failed: ${e.message}")
        }
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.catchAs(default: T) = catch { e ->
        Log.w(TAG, "preferences: ${e.javaClass.simpleName}: ${e.message}")
        emit(default)
    }
}

private fun ByteArray.toHex() = joinToString(" ") { "%02X".format(it) }
