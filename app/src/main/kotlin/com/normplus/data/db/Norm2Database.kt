package com.normplus.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.normplus.data.db.dao.BloodPressureDao
import com.normplus.data.db.dao.HeartRateDao
import com.normplus.data.db.dao.NotificationRuleDao
import com.normplus.data.db.dao.SleepDao
import com.normplus.data.db.dao.SportDao
import com.normplus.data.db.dao.WorkoutDao
import com.normplus.data.db.entities.BloodPressureEntity
import com.normplus.data.db.entities.HeartRateEntity
import com.normplus.data.db.entities.NotificationRuleEntity
import com.normplus.data.db.entities.SleepSessionEntity
import com.normplus.data.db.entities.SleepStageEntity
import com.normplus.data.db.entities.SportEntity
import com.normplus.data.db.entities.WorkoutEntity

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
    version = 5,
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
