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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Notification forwarding became an explicit WHITELIST: only apps the user picked in
            // the new notification-apps screen forward to the watch.
            //
            // Up to v3 `notification_rules` was an opt-*out* list — NotificationForwarder inserted
            // a row with enabled=1 for every app that ever posted a notification — so an enabled
            // row is indistinguishable from a deliberate user choice. Carrying those over would
            // whitelist every app that ever notified, i.e. exactly the flood the whitelist exists
            // to stop. So every rule is reset to disabled and the user re-picks from the picker.
            //
            // Rows are kept rather than deleted: appLabel / vibrateOnFirst / muteGroupChats are
            // preserved, so re-enabling an app restores its previous delivery preferences. Apps
            // the user had explicitly turned off stay off either way.
            //
            // (No schema change — NotificationRuleEntity.enabled only changed its *Kotlin* default,
            // which never reached SQL; the column is still `enabled INTEGER NOT NULL`.)
            db.execSQL("UPDATE notification_rules SET enabled = 0")
        }
    }

    private val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // sport_sessions.durationSeconds always held the record's sportTime as the watch sent
            // it, which is MINUTES (#89; SportRecord.activeMinutes). Renaming the column makes the
            // stored values right without touching them. (RENAME COLUMN needs SQLite 3.25; minSdk
            // 30 ships 3.28.)
            db.execSQL("ALTER TABLE sport_sessions RENAME COLUMN durationSeconds TO activeMinutes")
        }
    }

    @Provides @Singleton fun provideSportDao(db: Norm2Database) = db.sportDao()
    @Provides @Singleton fun provideHeartRateDao(db: Norm2Database) = db.heartRateDao()
    @Provides @Singleton fun provideSleepDao(db: Norm2Database) = db.sleepDao()
    @Provides @Singleton fun provideBloodPressureDao(db: Norm2Database) = db.bloodPressureDao()
    @Provides @Singleton fun provideWorkoutDao(db: Norm2Database) = db.workoutDao()
    @Provides @Singleton fun provideNotificationRuleDao(db: Norm2Database) = db.notificationRuleDao()
}
