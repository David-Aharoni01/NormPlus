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

/**
 * Per-app notification rule. This table is a **whitelist**: a row exists only for apps the user
 * has touched in the notification-apps picker, and only `enabled = true` rows forward to the
 * watch. A missing row means "never forward" — see [com.norm2hacked.notification.NotificationWhitelist].
 *
 * [enabled] therefore defaults to **false**: a row created for any reason other than an explicit
 * user opt-in must not start forwarding. (It defaulted to `true` up to DB v3, when this table was
 * an opt-*out* list the forwarder auto-populated; `MIGRATION_3_4` resets those rows.)
 */
@Entity(tableName = "notification_rules", indices = [Index("packageName", unique = true)])
data class NotificationRuleEntity(
    @PrimaryKey val packageName: String,
    val appLabel: String,
    val enabled: Boolean = false,
    /**
     * **Unused — retained only so the column survives.** It never controlled vibration: the old
     * forwarder used it to gate *sending* entirely, which is what [enabled] means now. Kept rather
     * than dropped because removing a column costs a table rebuild for no behavioural gain.
     */
    val vibrateOnFirst: Boolean = true,
    /**
     * Apply duplicate suppression to this app (see `RecentNotificationCache`). Historical name: it
     * gated a 30s group-chat mute window; it now gates the general "identical text already on the
     * watch" cache, which holds until the notification is dismissed on the phone.
     */
    val muteGroupChats: Boolean = true,
)
