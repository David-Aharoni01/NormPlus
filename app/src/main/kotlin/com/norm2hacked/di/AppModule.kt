package com.norm2hacked.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.norm2hacked.data.db.Norm2Database
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): Norm2Database =
        Room.databaseBuilder(ctx, Norm2Database::class.java, "norm2.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add durationSeconds column — default 300 (5 min) for existing rows.
            db.execSQL(
                "ALTER TABLE sleep_stages ADD COLUMN durationSeconds INTEGER NOT NULL DEFAULT 300"
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add unique constraints so OnConflictStrategy.IGNORE actually prevents duplicates
            // when a sync retries after a failed DELETE_*_DATA command.
            // Deduplicate existing rows first (keep lowest id), then create the unique indices.

            db.execSQL("DELETE FROM sport_sessions WHERE id NOT IN (SELECT MIN(id) FROM sport_sessions GROUP BY timestampEpoch)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sport_sessions_timestampEpoch ON sport_sessions(timestampEpoch)")

            db.execSQL("DELETE FROM heart_rate_samples WHERE id NOT IN (SELECT MIN(id) FROM heart_rate_samples GROUP BY timestampEpoch)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_heart_rate_samples_timestampEpoch ON heart_rate_samples(timestampEpoch)")

            db.execSQL("DELETE FROM sleep_sessions WHERE id NOT IN (SELECT MIN(id) FROM sleep_sessions GROUP BY startEpoch)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sleep_sessions_startEpoch ON sleep_sessions(startEpoch)")

            // Replace the per-column timestampEpoch index on sleep_stages with a composite
            // unique index on (sessionId, timestampEpoch) to prevent duplicate stage records.
            db.execSQL("DROP INDEX IF EXISTS index_sleep_stages_timestampEpoch")
            db.execSQL("DELETE FROM sleep_stages WHERE id NOT IN (SELECT MIN(id) FROM sleep_stages GROUP BY sessionId, timestampEpoch)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_sleep_stages_sessionId_timestampEpoch ON sleep_stages(sessionId, timestampEpoch)")
        }
    }

    @Provides @Singleton fun provideSportDao(db: Norm2Database) = db.sportDao()
    @Provides @Singleton fun provideHeartRateDao(db: Norm2Database) = db.heartRateDao()
    @Provides @Singleton fun provideSleepDao(db: Norm2Database) = db.sleepDao()
    @Provides @Singleton fun provideBloodPressureDao(db: Norm2Database) = db.bloodPressureDao()
    @Provides @Singleton fun provideWorkoutDao(db: Norm2Database) = db.workoutDao()
    @Provides @Singleton fun provideNotificationRuleDao(db: Norm2Database) = db.notificationRuleDao()
}
