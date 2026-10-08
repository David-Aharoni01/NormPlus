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
import com.norm2hacked.protocol.commands.SleepSessions
import com.norm2hacked.protocol.commands.SleepStage
import com.norm2hacked.protocol.commands.SportCommand
import com.norm2hacked.protocol.commands.SyncCountCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

private const val TAG = "SyncUseCase"

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

    /**
     * Each type read and stored, and -- with "delete after syncing" on, the default (#91) -- deleted
     * from the watch once all of it is in the database, so the next sync reads only what is new.
     * The watch cannot stream from an index, so this is the only way to read less (#85).
     *
     * Every delete erases that type's whole ring (0x0003DA14), and what was written after the read
     * would go with it. Sport is safe as it is: the watch erases it only if its count is still the
     * one this sync's count request reported (the data callback compares 0x10006BCC+0x420 with
     * +0x2F5), and otherwise acknowledges and keeps everything -- a :29/:59 record that landed
     * mid-sync is read next time. Heart rate and sleep have no such guard, so they are deleted only
     * if a second count says they have not changed: a measurement that ended in the meantime moves
     * the heart-rate count, and a sleep session that began makes the sleep count 0.
     */
    private suspend fun FlowCollector<SyncProgress>.syncEach(counts: DataCounts) {
        val delete = watchPreferences.isDeleteAfterSync()
        val sportRead = runCatching { syncSportData(counts.sport) { emit(it) } }
            .onFailure {
                Log.e(TAG, "Sport sync failed: ${it.message}", it)
                emit(SyncProgress.Error("Sport sync failed: ${it.message}"))
            }.getOrDefault(false)
        // Straight after the read, as the official app does: the watch's own guard covers it.
        if (delete && sportRead) deleteFromWatch("sport", CommandCode.DELETE_SPORT_DATA)

        val hrRead = runCatching { syncHeartRate(counts.heartRate) { emit(it) } }
            .onFailure {
                Log.e(TAG, "HR sync failed: ${it.message}", it)
                emit(SyncProgress.Error("HR sync failed: ${it.message}"))
            }.getOrDefault(false)
        val sleepRead = runCatching { syncSleep(counts.sleep) { emit(it) } }
            .onFailure {
                Log.e(TAG, "Sleep sync failed: ${it.message}", it)
                emit(SyncProgress.Error("Sleep sync failed: ${it.message}"))
            }.getOrDefault(false)

        if (!delete) {
            Log.i(TAG, "syncEach: delete after syncing is off — every record stays on the watch")
            return
        }
        if (!hrRead && !sleepRead) return
        // The second count also makes the watch write the half hour so far as a sport record; the
        // sport delete is already done, so that record stays on the watch for the next sync.
        val now = runCatching {
            SyncCountCommand.parseCounts(
                bleManager.sendAndAwait(CommandCode.TOTAL_SPORT_SLEEP_COUNT, Action.CHECK, byteArrayOf(0x00))
            )
        }.onFailure { Log.w(TAG, "syncEach: recount failed (${it.message}) — heart rate and sleep stay on the watch") }
            .getOrNull() ?: return
        if (hrRead) {
            if (now.heartRate == counts.heartRate) deleteFromWatch("heart rate", CommandCode.DELETE_HEART_RATE_DATA)
            else Log.i(TAG, "syncEach: heart rate went ${counts.heartRate} → ${now.heartRate} during the sync — kept on the watch")
        }
        if (sleepRead) {
            if (now.sleep == counts.sleep) deleteFromWatch("sleep", CommandCode.DELETE_SLEEP_DATA)
            else Log.i(TAG, "syncEach: sleep went ${counts.sleep} → ${now.sleep} during the sync — kept on the watch")
        }
    }

    /**
     * MBluetooth.deleteSportData / deleteHeartRateData / deleteSleepData: Delete*(callback, 1, 0),
     * a SET with content `[00]` (`[01]` on 0x5A would delete moods instead). The watch acknowledges
     * with the generic `01 81 [cmd 00]`. A delete that fails is only logged: the records stay on the
     * watch and the next sync reads them again, which the UNIQUE timestamps make harmless.
     */
    private suspend fun deleteFromWatch(label: String, cmd: CommandCode) {
        runCatching { bleManager.sendAndAwait(cmd, Action.SET, byteArrayOf(0x00)) }
            .onSuccess { ack ->
                val status = ack.payload.getOrNull(1)?.toInt()?.and(0xFF)
                if (status == 0) Log.i(TAG, "deleted $label from the watch")
                else Log.w(TAG, "delete $label: the watch answered status $status (${ack.payload.toHex()}) — kept")
            }
            .onFailure { Log.w(TAG, "delete $label failed: ${it.message} — the records stay on the watch") }
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

    /** True if every record came and is in the database: only then may the watch's copy go. */
    private suspend fun syncSportData(count: Int, onProgress: suspend (SyncProgress) -> Unit): Boolean {
        Log.i(TAG, "syncSportData: $count record(s) on watch")
        if (count == 0) return false

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
                activeMinutes = rec.activeMinutes,
            )
        }
        sportDao.insertAll(records)
        Log.i(TAG, "syncSportData: inserted ${records.size} record(s) into DB")
        return stream.complete
    }

    /** True if every record came and is in the database. */
    private suspend fun syncHeartRate(count: Int, onProgress: suspend (SyncProgress) -> Unit): Boolean {
        Log.i(TAG, "syncHeartRate: $count record(s) on watch")
        if (count == 0) return false

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
        return stream.complete
    }

    /**
     * True if every record came and every session is in the database. Records outside a session
     * are none of the official app's either, so they do not hold the delete back.
     */
    private suspend fun syncSleep(count: Int, onProgress: suspend (SyncProgress) -> Unit): Boolean {
        Log.i(TAG, "syncSleep: $count record(s) on watch")
        if (count == 0) return false

        // Source: MBluetooth.getSleepData → GetSleepData(callback, 1, 0, count) → payload=[0x00].
        // Streamed by the emulated watch's own firmware (#88, RecordStreamTest); the physical
        // watch has had none yet. While a session is still on, the count says 0.
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
            return false
        }

        // One session a night, 0x10 to 0x11, each record's stage lasting until the next record:
        // the official app's reading (SleepSessions, #90). Records outside a session -- the state
        // the watch writes again after each end -- belong to none.
        val sessions = SleepSessions.group(rawRecords)
        Log.i(TAG, "syncSleep: ${rawRecords.size} record(s) make ${sessions.size} session(s)")
        for (s in sessions) {
            Log.i(TAG, "  sleep ${s.startMs}–${s.endMs}: ${s.asleepSeconds}s asleep, " +
                "deep ${s.seconds(SleepStage.DEEP)}s light ${s.seconds(SleepStage.LIGHT)}s " +
                "awake ${s.seconds(SleepStage.AWAKE)}s")
            val stages = s.periods.map {
                SleepStageEntity(
                    sessionId = 0, // filled by insertSessionWithStages
                    timestampEpoch = it.startMs,
                    stage = it.stage.code,
                    durationSeconds = it.durationSeconds,
                )
            }
            sleepDao.insertSessionWithStages(SleepSessionEntity(startEpoch = s.startMs, endEpoch = s.endMs), stages)
        }
        return stream.complete
    }
}

private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
