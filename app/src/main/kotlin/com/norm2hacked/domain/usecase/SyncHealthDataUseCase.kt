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
import com.norm2hacked.protocol.Packet
import com.norm2hacked.protocol.commands.DataCounts
import com.norm2hacked.protocol.commands.DateTimeCommand
import com.norm2hacked.protocol.commands.HeartRateCommand
import com.norm2hacked.protocol.commands.RecordStream
import com.norm2hacked.protocol.commands.RecordStreams
import com.norm2hacked.protocol.commands.SleepCommand
import com.norm2hacked.protocol.commands.SportCommand
import com.norm2hacked.protocol.commands.SyncCountCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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

/**
 * Reads the watch's records into the database: one count, then each record type as ONE stream.
 *
 * As the official app does it (SyncBluetoothDataNew): getAllDataTypeCount, then getSportData /
 * getHeartRateData / getSleepData with the count, each a single request that the watch answers
 * with every record, one frame each (see [RecordStreams]). Asking per index, as this used to,
 * restarts the watch's stream on every request: the replies were records 1..~4 over and over
 * while the abandoned streams flooded the link, and the sync timed out (#85).
 */
class SyncHealthDataUseCase @Inject constructor(
    private val bleManager: WatchTransport,
    private val sportDao: SportDao,
    private val heartRateDao: HeartRateDao,
    private val sleepDao: SleepDao,
    private val watchPreferences: WatchPreferences,
) {
    fun syncAll(): Flow<SyncProgress> = flow {
        Log.i(TAG, "syncAll: starting")

        emit(SyncProgress.Running("Counting records…", 0, 0))
        // One TOTAL_SPORT_SLEEP_COUNT for all three counts (SyncCountCommand.parseCounts). It has
        // to be one: asking makes the watch write the half hour so far as another sport record,
        // so a second ask could count one more record than the first stream delivers.
        // Source: MBluetooth.getAllDataTypeCount → AllDataTypeCount(callback, 1, 0) → payload=[0x00]
        val counts = runCatching {
            SyncCountCommand.parseCounts(
                bleManager.sendAndAwait(CommandCode.TOTAL_SPORT_SLEEP_COUNT, Action.CHECK, byteArrayOf(0x00))
            )
        }.onFailure {
            Log.e(TAG, "Counting records failed: ${it.message}", it)
            emit(SyncProgress.Error("Counting records failed: ${it.message}"))
        }.getOrNull()

        if (counts != null) {
            Log.i(TAG, "syncAll: on the watch: $counts")
            syncEach(counts)
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

    private suspend fun FlowCollector<SyncProgress>.syncEach(counts: DataCounts) {
        runCatching { syncSportData(counts.sport) { emit(it) } }
            .onFailure {
                Log.e(TAG, "Sport sync failed: ${it.message}", it)
                emit(SyncProgress.Error("Sport sync failed: ${it.message}"))
            }
        runCatching { syncHeartRate(counts.heartRate) { emit(it) } }
            .onFailure {
                Log.e(TAG, "HR sync failed: ${it.message}", it)
                emit(SyncProgress.Error("HR sync failed: ${it.message}"))
            }
        runCatching { syncSleep(counts.sleep) { emit(it) } }
            .onFailure {
                Log.e(TAG, "Sleep sync failed: ${it.message}", it)
                emit(SyncProgress.Error("Sleep sync failed: ${it.message}"))
            }
    }

    /**
     * One request for every record of a type, collected as the watch streams it. A stream that
     * stops short still delivers what came — it is inserted, and reported as an error so the UI
     * says the sync was incomplete rather than silently showing a partial history.
     */
    private suspend fun <T> readStream(
        label: String,
        cmd: CommandCode,
        request: ByteArray,
        count: Int,
        onProgress: suspend (SyncProgress) -> Unit,
        parse: (Packet) -> T?,
    ): RecordStream<T> {
        val packets = mutableListOf<Packet>()
        val started = System.currentTimeMillis()
        onProgress(SyncProgress.Running(label, 0, count))
        bleManager.sendAndStream(cmd, Action.CHECK, request, isLast = RecordStreams.isLast(count))
            .collect { pkt ->
                packets += pkt
                onProgress(SyncProgress.Running(label, minOf(packets.size, count), count))
            }
        val stream = RecordStreams.assemble(packets, count, parse)
        Log.i(TAG, "$label: ${stream.records.size} of $count record(s) in ${System.currentTimeMillis() - started}ms" +
            " (${packets.size} frame(s), ${stream.duplicates} duplicate(s), ${stream.unparsed} unparsed)")
        if (!stream.complete) {
            Log.w(TAG, "$label: incomplete — ${stream.missing.size} missing, first ${stream.missing.take(10)}")
            onProgress(SyncProgress.Error(
                "$label sync incomplete: ${stream.records.size} of $count records" +
                    (if (stream.missing.isNotEmpty()) ", ${stream.missing.size} never came" else "") +
                    (if (stream.unparsed > 0) ", ${stream.unparsed} unreadable" else "")))
        }
        return stream
    }

    private suspend fun syncSportData(count: Int, onProgress: suspend (SyncProgress) -> Unit) {
        Log.i(TAG, "syncSportData: $count record(s) on watch")
        if (count == 0) return

        // Source: MBluetooth.getSportData → GetSportData(callback, 2, 0, count) → payload=[0x00, 0x00]
        val stream = readStream("Sport data", CommandCode.GET_SPORT_DATA, RecordStreams.SPORT_REQUEST,
            count, onProgress) { pkt ->
            SportCommand.parse(pkt) ?: run {
                Log.w(TAG, "  sport[${RecordStreams.index(pkt)}]: parse returned null, payload=${pkt.payload.toHex()}")
                null
            }
        }
        val records = stream.records.map { rec ->
            SportEntity(
                timestampEpoch = rec.timestampMs,
                steps = rec.steps,
                calories = rec.calories,
                distanceMeters = rec.distanceMeters,
                avgHeartRate = rec.avgHr,
                sportType = rec.sportType,
                durationSeconds = rec.durationSeconds,
            )
        }
        sportDao.insertAll(records)
        Log.i(TAG, "syncSportData: inserted ${records.size} record(s) into DB")
        // Source: MBluetooth.smali deleteSportData → DeleteSportData(callback, 1, 0) → payload=[0x00]
        // Only after a complete read: deleting would lose whatever the stream dropped.
        if (DELETE_AFTER_SYNC && stream.complete) {
            val delPkt = bleManager.sendAndAwait(CommandCode.DELETE_SPORT_DATA, Action.SET, byteArrayOf(0x00))
            Log.i(TAG, "syncSportData: delete ack action=${delPkt.action}")
        } else {
            Log.i(TAG, "syncSportData: non-destructive mode — leaving ${count} record(s) on watch")
        }
    }

    private suspend fun syncHeartRate(count: Int, onProgress: suspend (SyncProgress) -> Unit) {
        Log.i(TAG, "syncHeartRate: $count record(s) on watch")
        if (count == 0) return

        // Source: MBluetooth.getHeartRateData → GetHeartRateData(callback, 1, 0, count) → payload=[0x00]
        val stream = readStream("Heart rate", CommandCode.GET_HEART_RATE_DATA, RecordStreams.SINGLE_BYTE_REQUEST,
            count, onProgress) { pkt ->
            HeartRateCommand.parse(pkt) ?: run {
                Log.w(TAG, "  hr[${RecordStreams.index(pkt)}]: parse returned null, payload=${pkt.payload.toHex()}")
                null
            }
        }
        val records = stream.records.map { HeartRateEntity(timestampEpoch = it.timestampMs, bpm = it.bpm) }
        heartRateDao.insertAll(records)
        Log.i(TAG, "syncHeartRate: inserted ${records.size} record(s) into DB")
        // Source: MBluetooth.smali deleteHeartRateData → DeleteHeartRateData(callback, 1, 0) → payload=[0x00]
        if (DELETE_AFTER_SYNC && stream.complete) {
            val delPkt = bleManager.sendAndAwait(CommandCode.DELETE_HEART_RATE_DATA, Action.SET, byteArrayOf(0x00))
            Log.i(TAG, "syncHeartRate: delete ack action=${delPkt.action}")
        } else {
            Log.i(TAG, "syncHeartRate: non-destructive mode — leaving ${count} record(s) on watch")
        }
    }

    private suspend fun syncSleep(count: Int, onProgress: suspend (SyncProgress) -> Unit) {
        Log.i(TAG, "syncSleep: $count record(s) on watch")
        if (count == 0) return

        // Source: MBluetooth.getSleepData → GetSleepData(callback, 1, 0, count) → payload=[0x00].
        // Not yet seen on hardware: the physical watch has had no sleep records to stream.
        val stream = readStream("Sleep data", CommandCode.GET_SLEEP_DATA, RecordStreams.SINGLE_BYTE_REQUEST,
            count, onProgress) { pkt ->
            SleepCommand.parse(pkt) ?: run {
                Log.w(TAG, "  sleep[${RecordStreams.index(pkt)}]: parse returned null, payload=${pkt.payload.toHex()}")
                null
            }
        }
        val rawRecords = stream.records

        if (rawRecords.isEmpty()) {
            Log.w(TAG, "syncSleep: none of $count record(s) came or parsed — skipping insert and delete")
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
        if (DELETE_AFTER_SYNC && stream.complete) {
            val delPkt = bleManager.sendAndAwait(CommandCode.DELETE_SLEEP_DATA, Action.SET, byteArrayOf(0x00))
            Log.i(TAG, "syncSleep: delete ack action=${delPkt.action}")
        } else {
            Log.i(TAG, "syncSleep: non-destructive mode — leaving ${count} record(s) on watch")
        }
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
