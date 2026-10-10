package com.normplus.domain.model

data class DailyStats(
    val dateEpochMs: Long,
    val steps: Int = 0,
    val stepGoal: Int = 7_000,
    val calories: Float = 0f,
    val distanceMeters: Float = 0f,
    val avgHeartRate: Int = 0,
    val lastHrEpochMs: Long = 0L,
    val sleepMinutes: Int = 0,
    val deepSleepMinutes: Int = 0,
    val lightSleepMinutes: Int = 0,
    val remSleepMinutes: Int = 0,
)

data class SleepSummary(
    val nightEpochMs: Long,
    val totalMinutes: Int,
    val deepMinutes: Int,
    val lightMinutes: Int,
    val remMinutes: Int,
)

data class WorkoutSummary(
    val id: Long,
    val startEpochMs: Long,
    val durationSeconds: Int,
    val sportType: Int,
    val steps: Int,
    val calories: Float,
    val distanceMeters: Float,
    val avgHeartRate: Int,
    val hasGps: Boolean,
)

data class GpsPoint(val lat: Double, val lon: Double, val speed: Float, val epochMs: Long)

data class WatchSettings(
    val brightness: Int = 70,
    val screenTimeoutSeconds: Int = 5,
    val dnd: DndSettings = DndSettings(),
    val vibrationMode: Int = 2,
    val language: Int = 0,
    val metricUnits: Boolean = true,
    val powerSaveMode: Boolean = false,
    val switchMask: Int = 0,
    val stepGoal: Int = 7_000,
    val calorieGoal: Int = 500,
    val distanceGoalMeters: Int = 5000,
    val sleepGoalMinutes: Int = 480,
    val pageOrder: List<Int> = listOf(1, 3, 6, 9, 2, 7, 8, 4, 5, 10),
)

data class DndSettings(
    val enabled: Boolean = false,
    val startHour: Int = 22,
    val startMin: Int = 0,
    val endHour: Int = 7,
    val endMin: Int = 0,
)

data class FirmwareInfo(
    val watchVersion: String = "",
    val bundledVersion: String = "F0.2B01",
)

// Sport types from BluetoothCommandConstant.smali REAL_TIME_SPORT_TYPE_*
object SportType {
    val labels = mapOf(
        0x00 to "Unknown", 0x01 to "Walking", 0x02 to "Running",
        0x03 to "Crunches", 0x04 to "Swimming", 0x05 to "Cycling",
        0x06 to "Stair Climbing", 0x07 to "Climbing", 0x08 to "Standing",
        0x09 to "Sitting", 0x0A to "Indoor Cycling", 0x0B to "Weightlifting",
        0x0C to "Aerobics", 0x0D to "Indoor Walking", 0x0E to "Indoor Running",
        0x0F to "Yoga", 0x10 to "Strength Training", 0x11 to "Elliptical",
        0x12 to "Stair Stepper", 0x13 to "Dance", 0x14 to "Badminton",
        0x15 to "Basketball", 0x16 to "Free Training", 0x17 to "Hiking",
        0x18 to "Off-Road Running",
    )
    fun label(type: Int) = labels[type] ?: "Workout"
}

// Watch screen page labels — from AppSettingCommand constants
object AppPage {
    val labels = mapOf(
        0x01 to "Activity", 0x02 to "Alarm", 0x03 to "Heart Rate",
        0x04 to "Music", 0x05 to "Reminders", 0x06 to "Sleep",
        0x07 to "Stopwatch", 0x08 to "Timer", 0x09 to "Weather",
        0x0A to "Workouts",
    )
    fun label(type: Int) = labels[type] ?: "Screen $type"
}
