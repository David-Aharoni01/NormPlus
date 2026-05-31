package com.norm2hacked.protocol.commands

import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.Packet
import com.norm2hacked.protocol.PacketBuilder
import java.time.Instant
import java.time.ZoneId
import java.util.Calendar

// ── Battery ──────────────────────────────────────────────────────────────────

object BatteryCommand {
    // Source: MBluetooth.smali:getBatteryPower calls BatteryPower(callback, 1, 0)
    // intToByteArray(1,2)=[0x01,0x00] → len=1; intToByteArray(0,1)=[0x00] → payload=[0x00]
    fun buildQuery() = PacketBuilder.build(CommandCode.BATTERY_POWER, Action.CHECK, byteArrayOf(0x00))
    fun parse(p: Packet): BatteryState {
        val b = p.payload.firstOrNull()?.toInt()?.and(0xFF) ?: return BatteryState(0, false)
        return BatteryState(percent = b and 0x7F, charging = (b and 0x80) != 0)
    }
}

data class BatteryState(val percent: Int, val charging: Boolean)

// ── Device Version ────────────────────────────────────────────────────────────
// Response byte[0] = type (0=device, 1=software, 2=hardware, 3=protocol, 4=function, 6=info)
// bytes[1..] = ASCII string

object DeviceVersionCommand {
    // Source: MBluetooth.smali:getDeviceFunctionInfo uses (1, 6) → payload=[0x06] for default query
    fun buildQuery(type: Int = 6) =
        PacketBuilder.build(CommandCode.DEVICE_VERSION, Action.CHECK, byteArrayOf(type.toByte()))

    fun parseVersionString(p: Packet): String {
        if (p.payload.size < 2) return ""
        return String(p.payload, 1, p.payload.size - 1, Charsets.US_ASCII).trim(' ')
    }
}

// ── DateTime ─────────────────────────────────────────────────────────────────

object DateTimeCommand {
    fun buildSet(instant: Instant = Instant.now()): ByteArray {
        val cal = Calendar.getInstance().also { it.timeInMillis = instant.toEpochMilli() }
        val year = cal.get(Calendar.YEAR)
        val payload = byteArrayOf(
            ((year shr 8) and 0xFF).toByte(),
            (year and 0xFF).toByte(),
            (cal.get(Calendar.MONTH) + 1).toByte(),
            cal.get(Calendar.DAY_OF_MONTH).toByte(),
            cal.get(Calendar.HOUR_OF_DAY).toByte(),
            cal.get(Calendar.MINUTE).toByte(),
            cal.get(Calendar.SECOND).toByte(),
            cal.get(Calendar.DAY_OF_WEEK).toByte(),
            0, 0, 0, 0,
        )
        return PacketBuilder.build(CommandCode.DATETIME, Action.SET, payload)
    }
}

// ── Sync counts ───────────────────────────────────────────────────────────────

object SyncCountCommand {
    // Source: MBluetooth.smali:getAllDataTypeCount/getHeartRateCount both call (callback, 1, 0)
    // intToByteArray(1,2)=[0x01,0x00] → len=1; payload=[0x00]
    fun buildSportSleepCountQuery() =
        PacketBuilder.build(CommandCode.TOTAL_SPORT_SLEEP_COUNT, Action.CHECK, byteArrayOf(0x00))

    fun parseSportCount(p: Packet): Int =
        if (p.payload.size >= 2) (p.payload[0].toInt() and 0xFF) or ((p.payload[1].toInt() and 0xFF) shl 8) else 0

    fun parseSleepCount(p: Packet): Int =
        if (p.payload.size >= 4) (p.payload[2].toInt() and 0xFF) or ((p.payload[3].toInt() and 0xFF) shl 8) else 0

    fun buildHrCountQuery() =
        PacketBuilder.build(CommandCode.TOTAL_HEART_RATE_COUNT, Action.CHECK, byteArrayOf(0x00))

    fun parseHrCount(p: Packet): Int =
        if (p.payload.size >= 2) (p.payload[0].toInt() and 0xFF) or ((p.payload[1].toInt() and 0xFF) shl 8) else 0
}

// ── Sport data ────────────────────────────────────────────────────────────────

object SportCommand {
    fun buildDelete() =
        PacketBuilder.build(CommandCode.DELETE_SPORT_DATA, Action.SET)

    // Record layout — source: GetSportData.smali parse80BytesArray + SportBT.<init>(IIIIIIJII).
    // All multi-byte fields are little-endian (ParseUtil.bytesToLong, first byte = LSB).
    //   [0..1]   index        (2 bytes — NOT 1; this was the off-by-one in the old parser)
    //   [2..5]   timeStamp    (epoch seconds)
    //   [6..9]   step
    //   [10..13] calories
    //   [14..17] distance     (metres)
    //   [18..21] sportTime    (duration, seconds)
    //   [22]     avgBpm       (1 byte)
    //   [23]     type         (1 byte)
    //   [24..27] staticCalorie
    // The smali reads each trailing field only when the payload is long enough; we mirror that.
    fun parse(p: Packet): SportRecord? {
        val b = p.payload
        if (b.size < 6) return null // need at least the timestamp
        val timestamp = b.readInt32LE(2) * 1000L
        val steps = if (b.size >= 10) b.readInt32LE(6) else 0
        val calories = if (b.size >= 14) b.readInt32LE(10).toFloat() / 1000f else 0f
        val distance = if (b.size >= 18) b.readInt32LE(14).toFloat() else 0f
        val durationSec = if (b.size >= 22) b.readInt32LE(18) else 0
        val avgHr = if (b.size >= 23) b[22].toInt() and 0xFF else 0
        val sportType = if (b.size >= 24) b[23].toInt() and 0xFF else 0
        return SportRecord(timestamp, steps, calories, distance, avgHr, sportType, durationSec)
    }
}

data class SportRecord(
    val timestampMs: Long,
    val steps: Int,
    val calories: Float,
    val distanceMeters: Float,
    val avgHr: Int,
    val sportType: Int,
    val durationSeconds: Int,
)

// ── Device display data (the watch-face "today" totals) ─────────────────────────
//
// This is the live, cumulative daily summary the watch shows on its own face — NOT the
// sum of the discrete GET_SPORT_DATA history records (those are auto-detected activity
// snippets that get deleted after sync). Source: DeviceDisplayData.smali (cmd 0x57, CHECK;
// request payload [0x00] per MBluetooth.getDeviceDisplay → DeviceDisplayData(cb, 1, 0)).
object DeviceDisplayCommand {
    val CMD = CommandCode.DEVICE_DISPLAY_DATA

    /** Request payload — single zero byte → wire packet [6F 57 70 01 00 00 8F]. */
    fun queryPayload() = byteArrayOf(0x00)

    // Response is a sequence of 4-byte little-endian ints, addressed by index
    // (DeviceDisplayData.parse80BytesArray packed-switch):
    //   [0] step  [1] calorie  [2] distance  [3] sleep  [4] sportTime  [5] heartRate  [6] mood
    // The watch may send fewer than 7 (length is always a multiple of 4); absent fields read 0.
    fun parse(p: Packet): DeviceDisplay {
        val b = p.payload
        fun field(i: Int): Int = if (b.size >= (i + 1) * 4) b.readInt32LE(i * 4) else 0
        return DeviceDisplay(
            step = field(0),
            calorie = field(1),
            distanceMeters = field(2),
            sleepMinutes = field(3),
            sportTimeMinutes = field(4),
            heartRate = field(5),
            mood = field(6),
        )
    }
}

data class DeviceDisplay(
    val step: Int,
    val calorie: Int,
    val distanceMeters: Int,
    val sleepMinutes: Int,
    val sportTimeMinutes: Int,
    val heartRate: Int,
    val mood: Int,
)

// ── Heart rate ────────────────────────────────────────────────────────────────

object HeartRateCommand {

    fun buildDelete() =
        PacketBuilder.build(CommandCode.DELETE_HEART_RATE_DATA, Action.SET)

    // 7-byte records: [index(1)] [timestamp(4 LE)] [bpm(1)] [spare(1)]
    fun parse(p: Packet): HeartRateRecord? {
        val b = p.payload
        if (b.size < 6) return null
        val timestamp = b.readInt32LE(1) * 1000L
        val bpm = b[5].toInt() and 0xFF
        return HeartRateRecord(timestamp, bpm)
    }
}

data class HeartRateRecord(val timestampMs: Long, val bpm: Int)

// ── Sleep ─────────────────────────────────────────────────────────────────────

object SleepCommand {

    fun buildDelete() =
        PacketBuilder.build(CommandCode.DELETE_SLEEP_DATA, Action.SET)

    // 7-byte records: [index(1)] [timestamp(4 LE)] [stage(1)] [spare(1)]
    fun parse(p: Packet): SleepRecord? {
        val b = p.payload
        if (b.size < 6) return null
        val timestamp = b.readInt32LE(1) * 1000L
        val stage = b[5].toInt() and 0xFF
        val mappedStage = if (stage == 0x12) 0x11 else stage
        return SleepRecord(timestamp, mappedStage)
    }
}

data class SleepRecord(val timestampMs: Long, val stage: Int)

// ── Control device ────────────────────────────────────────────────────────────

object ControlDeviceCommand {
    private const val SUBCODE_SHOCK: Byte = 0x18  // CONTROL_DEVICE_SET_SHOCK

    fun buildFindWatch() =
        PacketBuilder.build(CommandCode.CONTROL_DEVICE, Action.SET, byteArrayOf(SUBCODE_SHOCK))
}

// ── Upgrade mode ──────────────────────────────────────────────────────────────

object UpgradeModeCommand {
    fun buildSet(mode: Byte = 0x01) =
        PacketBuilder.build(CommandCode.UPGRADE_MODE, Action.SET, byteArrayOf(mode))
}

// ── Find device ───────────────────────────────────────────────────────────────

object FindDeviceCommand {
    fun buildSet() = PacketBuilder.build(CommandCode.FIND_DEVICE, Action.SET, byteArrayOf(0x01))
}

// ── Notification push ─────────────────────────────────────────────────────────

object NotificationPushCommand {
    // SMS push: [type][content bytes]
    fun buildSmsPush(type: Byte, content: ByteArray): ByteArray {
        val payload = ByteArray(1 + content.size)
        payload[0] = type
        content.copyInto(payload, 1)
        return PacketBuilder.build(CommandCode.SMS_PUSH, Action.SET, payload)
    }

    // SocialNewPush: arbitrary payload for maximum flexibility
    fun buildSocialNewPush(payload: ByteArray): ByteArray =
        PacketBuilder.build(CommandCode.SOCIAL_NEW_PUSH, Action.SET, payload)

    // MsgCountPush: silent badge update [count byte]
    fun buildMsgCountPush(count: Byte): ByteArray =
        PacketBuilder.build(CommandCode.MSG_COUNT_PUSH, Action.SET, byteArrayOf(count))

    fun buildPhoneNamePush(type: Byte, nameOrNumber: ByteArray): ByteArray {
        val payload = ByteArray(1 + nameOrNumber.size)
        payload[0] = type
        nameOrNumber.copyInto(payload, 1)
        return PacketBuilder.build(CommandCode.PHONE_NAME_PUSH, Action.SET, payload)
    }

    fun buildEmailPush(type: Byte, content: ByteArray): ByteArray {
        val payload = ByteArray(1 + content.size)
        payload[0] = type
        content.copyInto(payload, 1)
        return PacketBuilder.build(CommandCode.EMAIL_PUSH, Action.SET, payload)
    }
}

// ── Byte helpers ──────────────────────────────────────────────────────────────

fun ByteArray.readInt32LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
