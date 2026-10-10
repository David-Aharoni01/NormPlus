package com.normplus.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.normplus.data.db.entities.BloodPressureEntity
import com.normplus.data.db.entities.HeartRateEntity
import com.normplus.data.db.entities.NotificationRuleEntity
import com.normplus.data.db.entities.SleepSessionEntity
import com.normplus.data.db.entities.SleepStageEntity
import com.normplus.data.db.entities.SportEntity
import com.normplus.data.db.entities.WorkoutEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SportDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<SportEntity>)

    @Query("SELECT * FROM sport_sessions WHERE timestampEpoch BETWEEN :start AND :end ORDER BY timestampEpoch ASC")
    fun queryByRange(start: Long, end: Long): Flow<List<SportEntity>>

    @Query("SELECT SUM(steps) FROM sport_sessions WHERE timestampEpoch BETWEEN :start AND :end")
    suspend fun sumSteps(start: Long, end: Long): Int?

    @Query("SELECT SUM(calories) FROM sport_sessions WHERE timestampEpoch BETWEEN :start AND :end")
    suspend fun sumCalories(start: Long, end: Long): Float?

    @Query("SELECT SUM(distanceMeters) FROM sport_sessions WHERE timestampEpoch BETWEEN :start AND :end")
    suspend fun sumDistance(start: Long, end: Long): Float?

    @Query("DELETE FROM sport_sessions")
    suspend fun deleteAll()

    // History (#100): the records by the page, and a signal when the table changes.
    @Query("SELECT * FROM sport_sessions WHERE timestampEpoch >= :start AND timestampEpoch < :end ORDER BY timestampEpoch ASC")
    suspend fun listInRange(start: Long, end: Long): List<SportEntity>

    @Query("SELECT MAX(timestampEpoch) FROM sport_sessions WHERE timestampEpoch < :before")
    suspend fun latestBefore(before: Long): Long?

    @Query("SELECT COUNT(*) FROM sport_sessions")
    fun observeCount(): Flow<Int>
}

@Dao
interface HeartRateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<HeartRateEntity>)

    @Query("SELECT * FROM heart_rate_samples WHERE timestampEpoch BETWEEN :start AND :end ORDER BY timestampEpoch ASC")
    fun queryByRange(start: Long, end: Long): Flow<List<HeartRateEntity>>

    @Query("SELECT AVG(bpm) FROM heart_rate_samples WHERE timestampEpoch BETWEEN :start AND :end")
    suspend fun avgBpm(start: Long, end: Long): Float?

    @Query("SELECT * FROM heart_rate_samples ORDER BY timestampEpoch DESC LIMIT 1")
    suspend fun queryLatest(): HeartRateEntity?

    @Query("DELETE FROM heart_rate_samples")
    suspend fun deleteAll()

    // History (#100): the readings by the page, and a signal when the table changes.
    @Query("SELECT * FROM heart_rate_samples WHERE timestampEpoch >= :start AND timestampEpoch < :end ORDER BY timestampEpoch ASC")
    suspend fun listInRange(start: Long, end: Long): List<HeartRateEntity>

    @Query("SELECT MAX(timestampEpoch) FROM heart_rate_samples WHERE timestampEpoch < :before")
    suspend fun latestBefore(before: Long): Long?

    @Query("SELECT COUNT(*) FROM heart_rate_samples")
    fun observeCount(): Flow<Int>
}

@Dao
interface SleepDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSession(session: SleepSessionEntity): Long

    @Query("SELECT id FROM sleep_sessions WHERE startEpoch = :startEpoch LIMIT 1")
    suspend fun findSessionIdByStart(startEpoch: Long): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStages(stages: List<SleepStageEntity>)

    @Transaction
    suspend fun insertSessionWithStages(session: SleepSessionEntity, stages: List<SleepStageEntity>) {
        var id = insertSession(session)
        if (id == -1L) {
            // IGNORE conflict means this session already exists.
            // Look up the real id so stages get the correct FK — writing with id=0 would
            // violate the foreign key constraint or orphan the stages entirely.
            id = findSessionIdByStart(session.startEpoch) ?: return
        }
        insertStages(stages.map { it.copy(sessionId = id) })
    }

    @Query("SELECT * FROM sleep_sessions WHERE startEpoch BETWEEN :start AND :end ORDER BY startEpoch ASC")
    fun querySessions(start: Long, end: Long): Flow<List<SleepSessionEntity>>

    @Query("SELECT * FROM sleep_stages WHERE sessionId = :sessionId ORDER BY timestampEpoch ASC")
    fun queryStages(sessionId: Long): Flow<List<SleepStageEntity>>

    // Stage codes are SleepStage's: 0 deep, 1 light, 2 awake (#90).
    @Query("""
        SELECT COALESCE(SUM(CASE WHEN s.stage = 0 THEN s.durationSeconds ELSE 0 END), 0) AS deepSec,
               COALESCE(SUM(CASE WHEN s.stage = 1 THEN s.durationSeconds ELSE 0 END), 0) AS lightSec,
               COALESCE(SUM(CASE WHEN s.stage = 2 THEN s.durationSeconds ELSE 0 END), 0) AS awakeSec
        FROM sleep_stages s
        JOIN sleep_sessions ss ON s.sessionId = ss.id
        WHERE ss.startEpoch BETWEEN :start AND :end
    """)
    suspend fun querySleepBreakdown(start: Long, end: Long): SleepBreakdown?

    @Query("DELETE FROM sleep_sessions")
    suspend fun deleteAll()

    // History (#100): a night belongs to the day it ends on, so these go by the session's end.
    @Query("SELECT * FROM sleep_sessions WHERE endEpoch >= :start AND endEpoch < :end ORDER BY startEpoch ASC")
    suspend fun listSessionsEndingIn(start: Long, end: Long): List<SleepSessionEntity>

    @Query("""
        SELECT s.* FROM sleep_stages s
        JOIN sleep_sessions ss ON s.sessionId = ss.id
        WHERE ss.endEpoch >= :start AND ss.endEpoch < :end
        ORDER BY s.sessionId, s.timestampEpoch ASC
    """)
    suspend fun listStagesOfSessionsEndingIn(start: Long, end: Long): List<SleepStageEntity>

    @Query("SELECT MAX(endEpoch) FROM sleep_sessions WHERE endEpoch < :before")
    suspend fun latestEndBefore(before: Long): Long?

    @Query("SELECT COUNT(*) FROM sleep_sessions")
    fun observeCount(): Flow<Int>
}

data class SleepBreakdown(val deepSec: Int, val lightSec: Int, val awakeSec: Int) {
    /** Time asleep: deep and light, not awake -- the official app's total (SleepNewDBService). */
    val totalSec: Int get() = deepSec + lightSec
    val totalMinutes: Int get() = totalSec / 60
}

@Dao
interface BloodPressureDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<BloodPressureEntity>)

    @Query("SELECT * FROM blood_pressure WHERE timestampEpoch BETWEEN :start AND :end ORDER BY timestampEpoch ASC")
    fun queryByRange(start: Long, end: Long): Flow<List<BloodPressureEntity>>

    @Query("DELETE FROM blood_pressure")
    suspend fun deleteAll()
}

@Dao
interface WorkoutDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<WorkoutEntity>)

    @Query("SELECT * FROM workouts ORDER BY startEpoch DESC")
    fun queryAll(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE startEpoch BETWEEN :start AND :end ORDER BY startEpoch DESC")
    fun queryByRange(start: Long, end: Long): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun queryById(id: Long): WorkoutEntity?
}

@Dao
interface NotificationRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: NotificationRuleEntity)

    @Query("SELECT * FROM notification_rules ORDER BY appLabel ASC")
    fun queryAll(): Flow<List<NotificationRuleEntity>>

    @Query("SELECT * FROM notification_rules WHERE packageName = :pkg")
    suspend fun queryByPackage(pkg: String): NotificationRuleEntity?

    @Query("DELETE FROM notification_rules WHERE packageName = :pkg")
    suspend fun delete(pkg: String)
}
