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

    fun parse(p: Packet): SportRecord? {
        val b = p.payload
        if (b.size < 22) return null
        val timestamp = b.readInt32LE(1) * 1000L
        val steps = b.readInt32LE(5)
        val calories = b.readInt32LE(9).toFloat() / 1000f
        val distance = b.readInt32LE(13).toFloat()
        val avgHr = b.readInt32LE(17)
        val sportType = b[21].toInt() and 0xFF
        // Duration field at offset 22 requires at least 26 bytes (22 + 4).
        val durationSec = if (b.size >= 26) b.readInt32LE(22) else 0
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
