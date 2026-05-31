package com.norm2hacked.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sport_sessions", indices = [Index("timestampEpoch", unique = true)])
data class SportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpoch: Long,
    val steps: Int,
    val calories: Float,
    val distanceMeters: Float,
    val avgHeartRate: Int,
    val sportType: Int,
    val durationSeconds: Int,
)

@Entity(tableName = "heart_rate_samples", indices = [Index("timestampEpoch", unique = true)])
data class HeartRateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpoch: Long,
    val bpm: Int,
)

@Entity(tableName = "sleep_sessions", indices = [Index("startEpoch", unique = true)])
data class SleepSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpoch: Long,
    val endEpoch: Long,
)

@Entity(
    tableName = "sleep_stages",
    foreignKeys = [ForeignKey(
        entity = SleepSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId"), Index(value = ["sessionId", "timestampEpoch"], unique = true)],
)
data class SleepStageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val timestampEpoch: Long,
    val stage: Int,         // 2=light, 3=deep, 4=REM
    val durationSeconds: Int, // gap to next stage record; last stage uses 5-min convention
)

@Entity(tableName = "blood_pressure", indices = [Index("timestampEpoch")])
data class BloodPressureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpoch: Long,
    val systolic: Int,
    val diastolic: Int,
)

@Entity(tableName = "workouts", indices = [Index("startEpoch")])
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpoch: Long,
    val durationSeconds: Int,
    val sportType: Int,
    val steps: Int,
    val calories: Float,
    val distanceMeters: Float,
    val avgHeartRate: Int,
    val gpsPointsJson: String?,  // JSON array of {lat, lon, speed, epoch}
)

@Entity(tableName = "notification_rules", indices = [Index("packageName", unique = true)])
data class NotificationRuleEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val enabled: Boolean = true,
    val vibrateOnFirst: Boolean = true,
    val muteGroupChats: Boolean = true,
)
