package com.norm2hacked.domain.usecase

import android.util.Log
import com.norm2hacked.protocol.WatchTransport
import com.norm2hacked.data.db.dao.HeartRateDao
import com.norm2hacked.data.db.dao.SleepDao
import com.norm2hacked.data.db.dao.SportDao
import com.norm2hacked.data.db.entities.HeartRateEntity
import com.norm2hacked.data.db.entities.SleepSessionEntity
import com.norm2hacked.data.db.entities.SleepStageEntity
import com.norm2hacked.data.db.entities.SportEntity
import com.norm2hacked.data.preferences.WatchPreferences
import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.commands.DateTimeCommand
import com.norm2hacked.protocol.commands.HeartRateCommand
import com.norm2hacked.protocol.commands.SleepCommand
import com.norm2hacked.protocol.commands.SportCommand
import com.norm2hacked.protocol.commands.SyncCountCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

private const val TAG = "SyncUseCase"

// Whether to delete records off the watch after reading them.
//
// The original protocol deletes after each sync to free watch storage (single-consumer model).
// But as a reverse-engineering tool that must COEXIST with the official app, deleting is
// destructive: whichever app syncs first drains the records, leaving the other showing zeroes.
// Default OFF so our sync is non-destructive. The watch keeps its records; our DB de-dupes via
// the UNIQUE timestamp constraints, so re-reading the same records on each sync is harmless.
private const val DELETE_AFTER_SYNC = false

sealed class SyncProgress {
    data object Idle : SyncProgress()
    data class Running(val label: String, val current: Int, val total: Int) : SyncProgress()
    data object Done : SyncProgress()
    data class Error(val message: String) : SyncProgress()
}

class SyncHealthDataUseCase @Inject constructor(
    private val bleManager: WatchTransport,
    private val sportDao: SportDao,
    private val heartRateDao: HeartRateDao,
    private val sleepDao: SleepDao,
    private val watchPreferences: WatchPreferences,
) {
    fun syncAll(): Flow<SyncProgress> = flow {
        Log.i(TAG, "syncAll: starting")

        emit(SyncProgress.Running("Syncing sport data…", 0, 0))
        runCatching { syncSportData { emit(it) } }
            .onFailure {
                Log.e(TAG, "Sport sync failed: ${it.message}", it)
                emit(SyncProgress.Error("Sport sync failed: ${it.message}"))
            }

        emit(SyncProgress.Running("Syncing heart rate…", 0, 0))
        runCatching { syncHeartRate { emit(it) } }
            .onFailure {
                Log.e(TAG, "HR sync failed: ${it.message}", it)
                emit(SyncProgress.Error("HR sync failed: ${it.message}"))
            }

        emit(SyncProgress.Running("Syncing sleep…", 0, 0))
        runCatching { syncSleep { emit(it) } }
            .onFailure {
                Log.e(TAG, "Sleep sync failed: ${it.message}", it)
                emit(SyncProgress.Error("Sleep sync failed: ${it.message}"))
            }

        // Sync the phone's clock to the watch every session. Routed through sendAndAwait (not a
        // raw writeToChar) so it serialises with the rest of the sync traffic and targets the
        // resolved write characteristic — the watch ACKs DATETIME SET with a SET_RESPONSE.
        runCatching { bleManager.sendAndAwait(CommandCode.DATETIME, Action.SET, DateTimeCommand.setPayload()) }
            .onSuccess { Log.i(TAG, "Clock sync sent to watch") }
            .onFailure { Log.w(TAG, "Clock sync failed: ${it.message}") }

        watchPreferences.saveLastSyncEpoch(System.currentTimeMillis())
        Log.i(TAG, "syncAll: complete")
        emit(SyncProgress.Done)
    }

    private suspend fun syncSportData(onProgress: suspend (SyncProgress) -> Unit) {
        // Source: MBluetooth.smali getAllDataTypeCount → TotalSportSleepCount(callback, 1, 0)
        //   content=[0x00], contentLen=1 → wire: [6F 52 70 01 00 00 8F]
        val countPkt = bleManager.sendAndAwait(CommandCode.TOTAL_SPORT_SLEEP_COUNT, Action.CHECK, byteArrayOf(0x00))
        val count = SyncCountCommand.parseSportCount(countPkt)
        Log.i(TAG, "syncSportData: $count record(s) on watch")
        if (count == 0) return

        val records = mutableListOf<SportEntity>()
        for (i in 1..count) {
            onProgress(SyncProgress.Running("Sport data", i, count))
            val pkt = bleManager.sendAndAwait(
                CommandCode.GET_SPORT_DATA, Action.CHECK, i.toLE2()
            )
            SportCommand.parse(pkt)?.let { rec ->
                Log.d(TAG, "  sport[$i/$count] ts=${rec.timestampMs} steps=${rec.steps} type=${rec.sportType} dur=${rec.durationSeconds}s")
                records += SportEntity(
                    timestampEpoch = rec.timestampMs,
                    steps = rec.steps,
                    calories = rec.calories,
                    distanceMeters = rec.distanceMeters,
                    avgHeartRate = rec.avgHr,
                    sportType = rec.sportType,
                    durationSeconds = rec.durationSeconds,
                )
            } ?: Log.w(TAG, "  sport[$i/$count]: parse returned null — skipping, payload=${pkt.payload.toHex()}")
        }
        sportDao.insertAll(records)
        Log.i(TAG, "syncSportData: inserted ${records.size} record(s) into DB")
        // Source: MBluetooth.smali deleteSportData → DeleteSportData(callback, 1, 0) → payload=[0x00]
        if (DELETE_AFTER_SYNC) {
            val delPkt = bleManager.sendAndAwait(CommandCode.DELETE_SPORT_DATA, Action.SET, byteArrayOf(0x00))
            Log.i(TAG, "syncSportData: delete ack action=${delPkt.action}")
        } else {
            Log.i(TAG, "syncSportData: non-destructive mode — leaving ${count} record(s) on watch")
        }
    }

    private suspend fun syncHeartRate(onProgress: suspend (SyncProgress) -> Unit) {
        // Source: MBluetooth.smali getHeartRateCount → TotalHeartRateCount(callback, 1, 0) → payload=[0x00]
        val countPkt = bleManager.sendAndAwait(CommandCode.TOTAL_HEART_RATE_COUNT, Action.CHECK, byteArrayOf(0x00))
        val count = SyncCountCommand.parseHrCount(countPkt)
        Log.i(TAG, "syncHeartRate: $count record(s) on watch")
        if (count == 0) return

        val records = mutableListOf<HeartRateEntity>()
        for (i in 1..count) {
            onProgress(SyncProgress.Running("Heart rate", i, count))
            val pkt = bleManager.sendAndAwait(
                CommandCode.GET_HEART_RATE_DATA, Action.CHECK, i.toLE2()
            )
            HeartRateCommand.parse(pkt)?.let { rec ->
                Log.d(TAG, "  hr[$i/$count] ts=${rec.timestampMs} bpm=${rec.bpm}")
                records += HeartRateEntity(timestampEpoch = rec.timestampMs, bpm = rec.bpm)
            } ?: Log.w(TAG, "  hr[$i/$count]: parse returned null — skipping, payload=${pkt.payload.toHex()}")
        }
        heartRateDao.insertAll(records)
        Log.i(TAG, "syncHeartRate: inserted ${records.size} record(s) into DB")
        // Source: MBluetooth.smali deleteHeartRateData → DeleteHeartRateData(callback, 1, 0) → payload=[0x00]
        if (DELETE_AFTER_SYNC) {
            val delPkt = bleManager.sendAndAwait(CommandCode.DELETE_HEART_RATE_DATA, Action.SET, byteArrayOf(0x00))
            Log.i(TAG, "syncHeartRate: delete ack action=${delPkt.action}")
        } else {
            Log.i(TAG, "syncHeartRate: non-destructive mode — leaving ${count} record(s) on watch")
        }
    }

    private suspend fun syncSleep(onProgress: suspend (SyncProgress) -> Unit) {
        // Reuses TOTAL_SPORT_SLEEP_COUNT — same packet, parseSleepCount reads bytes [2..3]
        val countPkt = bleManager.sendAndAwait(CommandCode.TOTAL_SPORT_SLEEP_COUNT, Action.CHECK, byteArrayOf(0x00))
        val count = SyncCountCommand.parseSleepCount(countPkt)
        Log.i(TAG, "syncSleep: $count record(s) on watch")
        if (count == 0) return

        val rawRecords = mutableListOf<com.norm2hacked.protocol.commands.SleepRecord>()
        for (i in 1..count) {
            onProgress(SyncProgress.Running("Sleep data", i, count))
            val pkt = bleManager.sendAndAwait(
                CommandCode.GET_SLEEP_DATA, Action.CHECK, i.toLE2()
            )
            SleepCommand.parse(pkt)?.let { rawRecords += it }
                ?: Log.w(TAG, "  sleep[$i/$count]: parse returned null, payload=${pkt.payload.toHex()}")
        }

        if (rawRecords.isEmpty()) {
            Log.w(TAG, "syncSleep: all $count record(s) failed to parse — skipping insert and delete")
            return
        }

        // Sort chronologically — records may not arrive in order.
        val sorted = rawRecords.sortedBy { it.timestampMs }

        // Compute actual stage durations as the gap to the next record.
        // The last stage uses a 5-minute convention (standard for sleep tracker APIs).
        // Source: original app infers duration from consecutive timestamps.
        val stageEntities = sorted.mapIndexed { idx, rec ->
            val nextMs = if (idx < sorted.size - 1) sorted[idx + 1].timestampMs
                         else rec.timestampMs + 5 * 60_000L
            SleepStageEntity(
                sessionId = 0, // filled by insertSessionWithStages
                timestampEpoch = rec.timestampMs,
                stage = rec.stage,
                durationSeconds = ((nextMs - rec.timestampMs) / 1000).toInt().coerceAtLeast(0),
            )
        }

        val startMs = sorted.first().timestampMs
        val endMs = sorted.last().timestampMs + (stageEntities.last().durationSeconds * 1000L)
        Log.i(TAG, "syncSleep: ${rawRecords.size} stage(s) parsed into session ${startMs}–${endMs}")
        val session = SleepSessionEntity(startEpoch = startMs, endEpoch = endMs)
        sleepDao.insertSessionWithStages(session, stageEntities)

        // Source: MBluetooth.smali deleteSleepData → DeleteSleepData(callback, 1, 0) → payload=[0x00]
        if (DELETE_AFTER_SYNC) {
            val delPkt = bleManager.sendAndAwait(CommandCode.DELETE_SLEEP_DATA, Action.SET, byteArrayOf(0x00))
            Log.i(TAG, "syncSleep: delete ack action=${delPkt.action}")
        } else {
            Log.i(TAG, "syncSleep: non-destructive mode — leaving ${count} record(s) on watch")
        }
    }
}

// 2-byte little-endian index as required by the watch protocol.
// Source: GetSportData / GetSleepData smali — both use intToByteArray(index, 2).
private fun Int.toLE2(): ByteArray = byteArrayOf(
    (this and 0xFF).toByte(),
    ((this ushr 8) and 0xFF).toByte(),
)

private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
