package com.normplus.ui.screens.watch

import androidx.compose.runtime.Immutable
import com.normplus.domain.model.DndSettings
import com.normplus.domain.model.WatchSettings
import com.normplus.protocol.commands.SwitchSettingCommand
import com.normplus.ui.components.SendState

/**
 * Every setting Watch sends on its own (#102): each is one row, and each row carries its own
 * [SendState]. A switch-mask bit is its own setting, though the watch takes the whole mask in
 * one SET (SwitchSettingCommand).
 */
enum class SettingKey {
    Brightness, Timeout, RaiseToWake, Dnd, Vibration, PowerSave, Units,
    Calls, MissedCalls, Sms, Social, Email, Calendar,
    StepGoal, HeartRateMonitor, Sedentary, GoalAlert,
}

/** The switch-mask bit behind a [SettingKey], or null for one with its own command. */
val SettingKey.maskBit: Int?
    get() = when (this) {
        SettingKey.RaiseToWake -> SwitchSettingCommand.BIT_RAISE_WAKE
        SettingKey.Calls -> SwitchSettingCommand.BIT_CALL
        SettingKey.MissedCalls -> SwitchSettingCommand.BIT_MISS_CALL
        SettingKey.Sms -> SwitchSettingCommand.BIT_SMS
        SettingKey.Social -> SwitchSettingCommand.BIT_SOCIAL
        SettingKey.Email -> SwitchSettingCommand.BIT_EMAIL
        SettingKey.Calendar -> SwitchSettingCommand.BIT_CALENDAR
        SettingKey.HeartRateMonitor -> SwitchSettingCommand.BIT_HEART_RATE_MONITOR
        SettingKey.Sedentary -> SwitchSettingCommand.BIT_SEDENTARY
        SettingKey.GoalAlert -> SwitchSettingCommand.BIT_GOAL_ACHIEVED
        else -> null
    }

/**
 * The screen timeouts Watch offers, in seconds. The command takes any 16-bit count of seconds
 * (ScreenTimeoutCommand); the official app's own choices are not yet traced, so these are a
 * temporary set (#102's open question) that includes the watch's default, 5.
 */
val TIMEOUT_CHOICES = listOf(5, 10, 15, 20, 30)

/** The step goals the Health picker offers: 1,000 to 20,000 in steps of 500 (brief §5). */
val STEP_GOAL_CHOICES = (1_000..20_000 step 500).toList()

/**
 * Everything Watch shows (#102).
 *
 * @param settings what the watch holds, as last read or last sent.
 * @param readAtEpochMs when [settings] were last read from the watch; null if never. Reading
 *   settings back is unverified on the physical watch (#1), so the rows say "as of" it.
 * @param sends each row's send, while it is under way, failed, or just landed.
 */
@Immutable
data class WatchUiState(
    val connected: Boolean = false,
    val loading: Boolean = false,
    val settings: WatchSettings = WatchSettings(),
    val readAtEpochMs: Long? = null,
    val sends: Map<SettingKey, SendState> = emptyMap(),
    val stepGoal: Int = WatchSettings().stepGoal,
    val deleteAfterSync: Boolean = true,
    val forwardingApps: Int = 0,
    val version: String = "",
) {
    val dnd: DndSettings get() = settings.dnd
    fun bit(key: SettingKey): Boolean = key.maskBit?.let { settings.switchMask and it != 0 } ?: false
}
