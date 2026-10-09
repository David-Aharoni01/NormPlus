package com.normplus.protocol.commands

import com.normplus.protocol.Action
import com.normplus.protocol.CommandCode
import com.normplus.protocol.Packet

// ── Watch Settings commands ────────────────────────────────────────────────────
//
// Pattern: each command object exposes:
//   CMD         – the CommandCode
//   queryPayload() – payload for CHECK (Action.CHECK) queries
//   setPayload(…)  – payload for SET (Action.SET) writes
//   parse(Packet)  – reads the watch's CHECK response
//
// Callers use WatchTransport.sendAndAwait(CMD, Action.CHECK/SET, payload) directly,
// so PacketBuilder framing is centralized in one place and large payloads are
// automatically chunked by BleWriteQueue.writeChunked().
//
// Sources: NORM/smali_classes2/cn/appscomm/bluetooth/protocol/Setting/*.smali

object BrightnessCommand {
    val CMD = CommandCode.SCREEN_BRIGHTNESS
    fun queryPayload(): ByteArray = byteArrayOf(0x00)
    fun setPayload(level: Int): ByteArray = byteArrayOf(level.coerceIn(0, 100).toByte())
    fun parse(p: Packet): Int = p.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
}

object ScreenTimeoutCommand {
    val CMD = CommandCode.BRIGHT_SCREEN_TIME
    fun queryPayload(): ByteArray = byteArrayOf(0x00)
    fun setPayload(seconds: Int): ByteArray = byteArrayOf(
        (seconds and 0xFF).toByte(), ((seconds shr 8) and 0xFF).toByte()
    )
    fun parse(p: Packet): Int = if (p.payload.size >= 2)
        (p.payload[0].toInt() and 0xFF) or ((p.payload[1].toInt() and 0xFF) shl 8) else 0
}

object DoNotDisturbCommand {
    val CMD = CommandCode.DO_NOT_DISTURB
    fun queryPayload(): ByteArray = byteArrayOf(0x00)
    fun setPayload(enabled: Boolean, startHour: Int, startMin: Int, endHour: Int, endMin: Int): ByteArray =
        byteArrayOf(if (enabled) 1 else 0, startHour.toByte(), startMin.toByte(), endHour.toByte(), endMin.toByte())

    fun parse(p: Packet): DndState {
        if (p.payload.size < 5) return DndState()
        return DndState(
            enabled = p.payload[0].toInt() != 0,
            startHour = p.payload[1].toInt() and 0xFF,
            startMin = p.payload[2].toInt() and 0xFF,
            endHour = p.payload[3].toInt() and 0xFF,
            endMin = p.payload[4].toInt() and 0xFF,
        )
    }
}

data class DndState(
    val enabled: Boolean = false,
    val startHour: Int = 22, val startMin: Int = 0,
    val endHour: Int = 7, val endMin: Int = 0,
)

object VibrationCommand {
    val CMD = CommandCode.SHOCK_MODE
    fun queryPayload(): ByteArray = byteArrayOf(0x00)
    // First byte = shock sub-mode (0x02 = DEFAULT); second = pattern index
    fun setPayload(mode: Int): ByteArray = byteArrayOf(0x02, mode.toByte())
    fun parse(p: Packet): Int = if (p.payload.size >= 2) p.payload[1].toInt() and 0xFF else 0
}

object LanguageCommand {
    val CMD = CommandCode.LANGUAGE
    fun queryPayload(): ByteArray = byteArrayOf(0x00)
    fun setPayload(languageId: Int): ByteArray = byteArrayOf(languageId.toByte())
    fun parse(p: Packet): Int = p.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0
}

object UnitCommand {
    val CMD = CommandCode.UNIT
    fun queryPayload(): ByteArray = byteArrayOf(0x00)
    fun setPayload(metric: Boolean): ByteArray = byteArrayOf(if (metric) 0 else 1)
    fun parse(p: Packet): Boolean = (p.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0) == 0
}

object WorkModeCommand {
    val CMD = CommandCode.WORK_MODE
    fun queryPayload(): ByteArray = byteArrayOf(0x00)
    fun setPayload(powerSave: Boolean): ByteArray = byteArrayOf(if (powerSave) 0 else 1)
    fun parse(p: Packet): Boolean = (p.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0) == 0
}

// Bitmask controlling per-notification-type toggles.
// Source: BluetoothCommandConstant.smali SWITCH_BIT_* and SWITCH_TYPE_* constants
object SwitchSettingCommand {
    val CMD = CommandCode.SWITCH_SETTING
    const val BIT_ANTI = 0x1
    const val BIT_AUTO_SYNC = 0x2
    const val BIT_SLEEP = 0x4
    const val BIT_AUTO_SLEEP = 0x8
    const val BIT_CALL = 0x10
    const val BIT_MISS_CALL = 0x20
    const val BIT_SMS = 0x40
    const val BIT_SOCIAL = 0x80
    const val BIT_EMAIL = 0x100
    const val BIT_CALENDAR = 0x200
    const val BIT_SEDENTARY = 0x400
    const val BIT_LOW_POWER = 0x800
    const val BIT_SECOND_REMINDER = 0x1000
    const val BIT_RING = 0x2000
    const val BIT_RAISE_WAKE = 0x4000
    const val BIT_GOAL_ACHIEVED = 0x8000
    const val BIT_REAL_HEART_RATE = 0x10000
    const val BIT_HEART_RATE_MONITOR = 0x40000
    const val BIT_MOOD = 0x200000

    // The notification-kind bits the watch must have enabled to actually DISPLAY pushed
    // notifications (calls, missed calls, SMS, social-app messages, email, calendar). If these
    // are off, the watch receives a push and silently drops it. Enabled by default on connect.
    val NOTIFICATION_BITS =
        BIT_CALL or BIT_MISS_CALL or BIT_SMS or BIT_SOCIAL or BIT_EMAIL or BIT_CALENDAR

    fun queryPayload(): ByteArray = byteArrayOf(0x00)

    // Full-mask SET content is 5 bytes: a CONSTANT 0x00 sub-command byte, then the 4-byte LE mask.
    // Source: MBluetooth.setSwitchSetting(cb, long, int) → longToByteArray(mask, 4) →
    // SwitchSetting(cb, 5, byte[]) constructor, which builds content = [0x00][mask LE4].
    // (The [0x01]-prefixed 3-byte variant is a DIFFERENT command that toggles a single switch by
    // type, not the full mask — using it, or a bare mask, makes the watch ack-but-ignore.)
    fun setPayload(mask: Int): ByteArray = byteArrayOf(
        0x00,
        (mask and 0xFF).toByte(),
        ((mask shr 8) and 0xFF).toByte(),
        ((mask shr 16) and 0xFF).toByte(),
        ((mask shr 24) and 0xFF).toByte(),
    )

    // Response carries the bare 4-byte LE mask (no prefix). Source: SwitchSetting.smali parse.
    fun parse(p: Packet): Int {
        if (p.payload.size < 4) return 0
        return (p.payload[0].toInt() and 0xFF) or
                ((p.payload[1].toInt() and 0xFF) shl 8) or
                ((p.payload[2].toInt() and 0xFF) shl 16) or
                ((p.payload[3].toInt() and 0xFF) shl 24)
    }
}

// Screen/app order on the watch.
// Source: AppSetting.smali — pageQueueArray is an ordered list of non-zero page type bytes.
// Source: BluetoothCommandConstant.smali APP_SETTING_PAGE_TYPE_* constants
object AppSettingCommand {
    val CMD = CommandCode.APP_SETTING
    const val TYPE_ACTIVITY = 0x01
    const val TYPE_ALARM = 0x02
    const val TYPE_HEART_RATE = 0x03
    const val TYPE_MUSIC = 0x04
    const val TYPE_REMINDERS = 0x05
    const val TYPE_SLEEP = 0x06
    const val TYPE_STOP_WATCH = 0x07
    const val TYPE_TIMER = 0x08
    const val TYPE_WEATHER = 0x09
    const val TYPE_WORKOUTS = 0x0A

    fun queryPayload(): ByteArray = byteArrayOf(0x01)
    fun setPayload(pageOrder: List<Int>): ByteArray {
        val payload = ByteArray(2 + pageOrder.size)
        payload[0] = 0x01  // appSettingType
        payload[1] = pageOrder.size.toByte()
        pageOrder.forEachIndexed { i, page -> payload[2 + i] = page.toByte() }
        return payload
    }
    // Non-zero bytes = enabled pages in order; zeros = padding/disabled.
    fun parse(p: Packet): List<Int> {
        if (p.payload.size < 2 || p.payload[0].toInt() != 0x01) return emptyList()
        return p.payload.drop(2).filter { it.toInt() != 0 }.map { it.toInt() and 0xFF }
    }
}

object GoalCommand {
    val CMD = CommandCode.GOAL
    fun queryPayload(): ByteArray = byteArrayOf(0x00)
    private fun goalPayload(type: Byte, value: Int): ByteArray = byteArrayOf(
        type, 0,
        (value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()
    )
    fun setStepsPayload(steps: Int) = goalPayload(0x00, steps)
    fun setCaloriesPayload(kcal: Int) = goalPayload(0x01, kcal)
    fun setDistancePayload(meters: Int) = goalPayload(0x02, meters)
    fun setSleepPayload(minutes: Int) = goalPayload(0x03, minutes)
}
