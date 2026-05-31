package com.norm2hacked.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.norm2hacked.data.db.dao.BloodPressureDao
import com.norm2hacked.data.db.dao.HeartRateDao
import com.norm2hacked.data.db.dao.NotificationRuleDao
import com.norm2hacked.data.db.dao.SleepDao
import com.norm2hacked.data.db.dao.SportDao
import com.norm2hacked.data.db.dao.WorkoutDao
import com.norm2hacked.data.db.entities.BloodPressureEntity
import com.norm2hacked.data.db.entities.HeartRateEntity
import com.norm2hacked.data.db.entities.NotificationRuleEntity
import com.norm2hacked.data.db.entities.SleepSessionEntity
import com.norm2hacked.data.db.entities.SleepStageEntity
import com.norm2hacked.data.db.entities.SportEntity
import com.norm2hacked.data.db.entities.WorkoutEntity

@Database(
    entities = [
        SportEntity::class,
        HeartRateEntity::class,
        SleepSessionEntity::class,
        SleepStageEntity::class,
        BloodPressureEntity::class,
        WorkoutEntity::class,
        NotificationRuleEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class Norm2Database : RoomDatabase() {
    abstract fun sportDao(): SportDao
    abstract fun heartRateDao(): HeartRateDao
    abstract fun sleepDao(): SleepDao
    abstract fun bloodPressureDao(): BloodPressureDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun notificationRuleDao(): NotificationRuleDao
}
