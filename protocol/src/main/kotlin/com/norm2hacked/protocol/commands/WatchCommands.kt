package com.norm2hacked.protocol.commands

import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.Packet
import com.norm2hacked.protocol.PacketBuilder
import com.norm2hacked.protocol.util.BidiUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

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
    /**
     * Raw 12-byte DATETIME SET payload (for routing through sendAndAwait / the write queue).
     *
     * Byte order verified from `DateTime.smali` + its callers (`MBluetooth.setDateTime` passes
     * `len=0xC`; `SyncBluetoothDataNew.setTimeToDevice` supplies the field values):
     * ```
     * [yearLo][yearHi][month][day][hour][min][sec][0][reHome][tzSign][tzHour][tzMin]
     *    0       1       2     3     4     5     6  (7)   (8)    (9)    (10)    (11)
     * ```
     * The year is LITTLE-endian: `ParseUtil.intToByteArray(year, 2)` is the same call that builds
     * the frame's 2-byte content length, which the wire format + `PacketTest` confirm is LE, as is
     * every other 2-byte field in this protocol (lengths, counts, goals, switch mask). (Was
     * big-endian here, which made the watch read a garbage year on every clock sync.)
     *
     * byte[7] is always 0 — we previously put `DAY_OF_WEEK` there, and dropped the timezone triplet
     * at [9..11] entirely, so the watch got a stray weekday and a UTC+00:00 offset.
     *
     * [reHome] sets byte[8]: 0 for a plain clock sync, **1 only for hand calibration** — it tells
     * the watch to drive the physical hands to the set time from their current 12:00 position (see
     * `CalibrationSaveCommand`). Sending 1 on a routine sync would sweep the hands unnecessarily.
     */
    fun setPayload(
        instant: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        reHome: Boolean = false,
    ): ByteArray {
        val local = instant.atZone(zone)
        val year = local.year
        // tz triplet = getTimeZone4City(): [sign, hours, minutes], sign 1 = '+', 0 = '-'.
        val offsetMinutes = zone.rules.getOffset(instant).totalSeconds / 60
        val absMinutes = abs(offsetMinutes)
        return byteArrayOf(
            (year and 0xFF).toByte(),
            ((year shr 8) and 0xFF).toByte(),
            local.monthValue.toByte(),
            local.dayOfMonth.toByte(),
            local.hour.toByte(),
            local.minute.toByte(),
            local.second.toByte(),
            0,                                  // byte7: always 0
            if (reHome) 1 else 0,               // byte8: re-home the hands (calibration only)
            (if (offsetMinutes >= 0) 1 else 0).toByte(), // byte9:  tz sign
            (absMinutes / 60).toByte(),         // byte10: tz offset hours
            (absMinutes % 60).toByte(),         // byte11: tz offset minutes
        )
    }

    /** Framed DATETIME SET packet (for fire-and-forget writers that don't route via the queue). */
    fun buildSet(instant: Instant = Instant.now()): ByteArray =
        PacketBuilder.build(CommandCode.DATETIME, Action.SET, setPayload(instant))
}

// ── Sync counts ───────────────────────────────────────────────────────────────

object SyncCountCommand {
    // Source: MBluetooth.smali:getAllDataTypeCount/getHeartRateCount both call (callback, 1, 0)
    // intToByteArray(1,2)=[0x01,0x00] → len=1; payload=[0x00]
    fun buildSportSleepCountQuery() =
        PacketBuilder.build(CommandCode.TOTAL_SPORT_SLEEP_COUNT, Action.CHECK, byteArrayOf(0x00))

    /**
     * Every count in one TOTAL_SPORT_SLEEP_COUNT reply, as AllDataTypeCount.parse80BytesArray
     * reads it: LE16 sport [0..1], sleep [2..3], and heart rate [4..5] only when the reply is
     * longer than 4 bytes. The physical watch answers `[98 03 00 00 0f 01 00 00]`: 920 sport,
     * no sleep, 271 heart rate (2026-10-07).
     *
     * This, not TOTAL_HEART_RATE_COUNT, is where the heart-rate count comes from: that one's
     * reply from this watch is four bytes (`[0f 01 00 00]`), and HeartRateCount.smali takes
     * exactly two, so the official app cannot be counting this watch's records with it.
     *
     * Asking writes a record: the watch stores the half hour so far as one more sport record
     * before it answers (#51), so the count is only good for the stream read right after it.
     */
    fun parseCounts(p: Packet): DataCounts {
        fun le16(at: Int) =
            (p.payload[at].toInt() and 0xFF) or ((p.payload[at + 1].toInt() and 0xFF) shl 8)
        return DataCounts(
            sport = if (p.payload.size >= 2) le16(0) else 0,
            sleep = if (p.payload.size >= 4) le16(2) else 0,
            heartRate = if (p.payload.size > 4) le16(4) else 0,
        )
    }
}

data class DataCounts(val sport: Int, val sleep: Int, val heartRate: Int)

// ── Record streams ────────────────────────────────────────────────────────────
//
// One GET_SPORT_DATA / GET_HEART_RATE_DATA / GET_SLEEP_DATA request is answered with EVERY record
// on the watch, one frame each, indexed from 1 (MBluetooth.getSportData → GetSportData(cb, 2, 0,
// count), getHeartRateData/getSleepData → (cb, 1, 0, count); parse80BytesArray returns 3, "keep
// receiving", until the list holds `count`). The index in the request is ignored: asking for
// [02 00] still streams from record 1, on the physical watch and the emulated one. Asking once per
// index -- what the sync used to do -- restarts the stream on every request, so it only ever got
// records 1 to ~4 back while the streams it left running flooded the link (#85).

object RecordStreams {
    /** GetSportData's request: contentLen 2, content intToByteArray(0, 2). */
    val SPORT_REQUEST: ByteArray get() = byteArrayOf(0x00, 0x00)

    /** GetHeartRateData's and GetSleepData's: contentLen 1, content [0x00]. */
    val SINGLE_BYTE_REQUEST: ByteArray get() = byteArrayOf(0x00)

    /** A record's 1-based index: bytes [0..1] LE in every record type (bytesToLong(0, 1)). */
    fun index(p: Packet): Int =
        if (p.payload.size < 2) 0
        else (p.payload[0].toInt() and 0xFF) or ((p.payload[1].toInt() and 0xFF) shl 8)

    /** The stream's last frame is the one indexed [count]. */
    fun isLast(count: Int): (Packet) -> Boolean = { index(it) >= count }

    /**
     * What a stream delivered, against the [count] asked for: each index once (the first copy;
     * a later one is counted in [RecordStream.duplicates]), in index order, through [parse].
     */
    fun <T> assemble(packets: List<Packet>, count: Int, parse: (Packet) -> T?): RecordStream<T> {
        val byIndex = sortedMapOf<Int, Packet>()
        var duplicates = 0
        var outOfRange = 0
        for (p in packets) {
            val i = index(p)
            when {
                i !in 1..count -> outOfRange++
                byIndex.putIfAbsent(i, p) != null -> duplicates++
            }
        }
        val records = mutableListOf<T>()
        var unparsed = outOfRange
        for (p in byIndex.values) parse(p)?.let { records += it } ?: unparsed++
        val missing = (1..count).filter { it !in byIndex }
        return RecordStream(records, count, missing, duplicates, unparsed)
    }
}

data class RecordStream<T>(
    val records: List<T>,
    /** How many the count said there were. */
    val expected: Int,
    /** Indices in 1..expected that never came. */
    val missing: List<Int>,
    val duplicates: Int,
    /** Frames that came but did not parse, or whose index was outside 1..expected. */
    val unparsed: Int,
) {
    val complete: Boolean get() = missing.isEmpty() && unparsed == 0
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
    //   [18..21] sportTime    (active minutes -- see activeMinutes)
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
        val activeMinutes = if (b.size >= 22) b.readInt32LE(18) else 0
        val avgHr = if (b.size >= 23) b[22].toInt() and 0xFF else 0
        val sportType = if (b.size >= 24) b[23].toInt() and 0xFF else 0
        return SportRecord(timestamp, steps, calories, distance, avgHr, sportType, activeMinutes)
    }
}

data class SportRecord(
    val timestampMs: Long,
    val steps: Int,
    val calories: Float,
    val distanceMeters: Float,
    val avgHr: Int,
    val sportType: Int,
    /**
     * The record's `sportTime`: MINUTES of activity in its half hour, not seconds (#89). The
     * official app shows a day's worth as "Active time N min" (view_activity_active_time_tab.xml,
     * `@string/unit_min`) against a goal that defaults to 30 (DeviceGoalInfo.activeTime), and the
     * records agree: the physical watch's 911-step half hour says 11, ~70 s of walking on the
     * emulated watch says 1.
     */
    val activeMinutes: Int,
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

    // 7-byte records: [index(2 LE)] [timestamp(4 LE)] [bpm(1)]
    // Source: GetHeartRateData.smali parse80BytesArray — index=bytesToLong(0,1) is TWO bytes,
    // timestamp=bytesToLong(2,5), bpm=byte[6]. The index is 2 bytes, not 1 — the same off-by-one
    // the sport parser already fixed. Reading ts at offset 1 / bpm at 5 shifted every record by a
    // byte, so synced HR timestamps never matched the real time.
    fun parse(p: Packet): HeartRateRecord? {
        val b = p.payload
        if (b.size < 7) return null
        val timestamp = b.readInt32LE(2) * 1000L
        val bpm = b[6].toInt() and 0xFF
        return HeartRateRecord(timestamp, bpm)
    }
}

data class HeartRateRecord(val timestampMs: Long, val bpm: Int)

// ── Sleep ─────────────────────────────────────────────────────────────────────

object SleepCommand {

    fun buildDelete() =
        PacketBuilder.build(CommandCode.DELETE_SLEEP_DATA, Action.SET)

    // [index(2 LE)] [timestamp(4 LE)] [stage(1)], and 3 zero bytes: the firmware sends 10
    // (#88), GetSleepData reads 7. Stage 0x10 starts a session, 0x11 ends it; between them
    // 0 deep, 1 light, 2-4 awake (SleepNewDBService), each until the next record.
    // Source: GetSleepData.smali parse80BytesArray — index=bytesToLong(0,1) is TWO bytes,
    // timestamp=bytesToLong(2,5), stage=byte[6] (with the 0x12→0x11 normalisation below).
    // The index is 2 bytes, not 1 — same off-by-one as HR/sport; ts at offset 1 / stage at 5
    // shifted every record by a byte.
    fun parse(p: Packet): SleepRecord? {
        val b = p.payload
        if (b.size < 7) return null
        val timestamp = b.readInt32LE(2) * 1000L
        val stage = b[6].toInt() and 0xFF
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
    /**
     * `[00]`: MBluetooth.enterUpdateMode builds `UpgradeMode(callback, 1, 0)`, whose third
     * argument is the one content byte. The firmware acknowledges it on 8001 and stays in the
     * running application -- no reset (README "Rehearsing an OTA").
     */
    const val MODE: Byte = 0x00

    fun payload() = byteArrayOf(MODE)

    fun buildSet(mode: Byte = MODE) =
        PacketBuilder.build(CommandCode.UPGRADE_MODE, Action.SET, byteArrayOf(mode))
}

// ── Find device ───────────────────────────────────────────────────────────────

object FindDeviceCommand {
    fun buildSet() = PacketBuilder.build(CommandCode.FIND_DEVICE, Action.SET, byteArrayOf(0x01))
}

// ── Notification push ─────────────────────────────────────────────────────────

object NotificationPushCommand {

    // CRUD ops — source: MessageBT.smali constants.
    const val CRUD_ADD: Byte = 0x00
    const val CRUD_EDIT: Byte = 0x01
    const val CRUD_DEL_ONE: Byte = 0x02
    const val CRUD_DEL_ALL: Byte = 0x03

    // messageType selects the icon shown on the watch.
    // Source: PackageTypeData.smali (package→type map) + MessagePerfectBTFactory.smali (calls).
    const val TYPE_MISSED_CALL: Byte = 0x00
    const val TYPE_SMS: Byte = 0x01
    const val TYPE_GENERIC: Byte = 0x02   // generic "message" icon — default for unknown apps
    const val TYPE_EMAIL: Byte = 0x03
    const val TYPE_CALENDAR: Byte = 0x04
    const val TYPE_INCOMING_CALL: Byte = 0x05
    const val TYPE_CALL_ENDED: Byte = 0x06

    // Known social apps → icon type (PackageTypeData.initSocialData). Unknown → TYPE_GENERIC.
    private val PACKAGE_TYPE: Map<String, Byte> = mapOf(
        "com.tencent.mm" to 0x07,            // WeChat
        "com.viber.voip" to 0x08,            // Viber
        "com.snapchat.android" to 0x09,      // Snapchat
        "com.whatsapp" to 0x0A,              // WhatsApp
        "com.whatsapp.w4b" to 0x0A,          // WhatsApp Business
        "com.tencent.mobileqq" to 0x0B,      // QQ
        "com.facebook.katana" to 0x0C,       // Facebook
        "com.facebook.orca" to 0x0D,         // Messenger
        "com.instagram.android" to 0x0F,     // Instagram
        "com.twitter.android" to 0x10,       // Twitter/X
        "com.linkedin.android" to 0x11,      // LinkedIn
        "com.google.android.gm" to TYPE_EMAIL,
        "com.microsoft.office.outlook" to TYPE_EMAIL,
    )

    /** Icon type for a package: known social app → its type, email apps → email, else generic. */
    fun socialTypeForPackage(pkg: String): Byte = PACKAGE_TYPE[pkg] ?: TYPE_GENERIC

    /**
     * Build an app-notification push — SocialNewPush (cmd 0x79, SET) carrying a MessageBT body.
     * Source: MessageBT.getBytes() + MBluetooth.sendNewMessage.
     *
     * MessageBT body (little-endian throughout):
     *   [bodyLen(2)] [id(4)] [crud(1)] [TLV…]
     *     bodyLen = length of everything after these 2 bytes (= id+crud+TLVs)
     *   each TLV = [valueLen+1(2)] [tag(1)] [value…]   (the +1 counts the tag byte)
     *     tag 0x01 = messageType (1-byte value)   0x02 = title   0x03 = content
     *     (0x04 = dateTime, 0x05 = phoneNumber — omitted for plain app notifications)
     * Title is capped at 90 bytes, content at 240 (ParseUtil.getContentAddDot 0x5A / 0xF0).
     */
    /**
     * The raw SocialNewPush MessageBT body (the SET payload, without the 0x6F frame). Prefer this
     * over [buildAppNotification] when routing through the write queue, so the frame is built once
     * in PacketBuilder and the queue handles MTU chunking — a real notification's title+content
     * easily exceeds one MTU and must be chunked, not sent as a single (truncated) write.
     */
    fun appNotificationPayload(
        messageType: Byte,
        id: Int,
        title: String,
        content: String,
        crud: Byte = CRUD_ADD,
    ): ByteArray {
        // Reorder RTL (Hebrew/Arabic) text to visual order before encoding — the watch
        // renders bytes left-to-right as received and does no bidi of its own. See BidiUtil.
        // truncateWithDots = ParseUtil.getContentAddDot (0x5A / 0xF0): caps at the byte limit
        // on a UTF-8 char boundary (never splits a multi-byte char), matching the original.
        val titleBytes = CommonProtocolCodec.truncateWithDots(BidiUtil.formatRtlString(title), 0x5A)
        val contentBytes = CommonProtocolCodec.truncateWithDots(BidiUtil.formatRtlString(content), 0xF0)

        val body = ArrayList<Byte>(32)
        body.addLE4(id)
        body.add(crud)
        body.addTlv(0x01, byteArrayOf(messageType))
        if (titleBytes.isNotEmpty()) body.addTlv(0x02, titleBytes)
        if (contentBytes.isNotEmpty()) body.addTlv(0x03, contentBytes)

        val messageBt = ByteArray(2 + body.size)
        messageBt[0] = (body.size and 0xFF).toByte()
        messageBt[1] = ((body.size shr 8) and 0xFF).toByte()
        for (i in body.indices) messageBt[2 + i] = body[i]
        return messageBt
    }

    fun buildAppNotification(
        messageType: Byte,
        id: Int,
        title: String,
        content: String,
        crud: Byte = CRUD_ADD,
    ): ByteArray = PacketBuilder.build(
        CommandCode.SOCIAL_NEW_PUSH, Action.SET,
        appNotificationPayload(messageType, id, title, content, crud),
    )

    /** MsgCountPush payload: silent unread-badge update [count byte]. */
    fun msgCountPayload(count: Byte): ByteArray = byteArrayOf(count)

    fun buildMsgCountPush(count: Byte): ByteArray =
        PacketBuilder.build(CommandCode.MSG_COUNT_PUSH, Action.SET, msgCountPayload(count))

    // SMS push: [type][content bytes]
    fun buildSmsPush(type: Byte, content: ByteArray): ByteArray {
        val payload = ByteArray(1 + content.size)
        payload[0] = type
        content.copyInto(payload, 1)
        return PacketBuilder.build(CommandCode.SMS_PUSH, Action.SET, payload)
    }

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

    private fun MutableList<Byte>.addLE4(v: Int) {
        add((v and 0xFF).toByte())
        add(((v shr 8) and 0xFF).toByte())
        add(((v shr 16) and 0xFF).toByte())
        add(((v shr 24) and 0xFF).toByte())
    }

    // TLV = [valueLen+1 (2, LE)] [tag] [value]
    private fun MutableList<Byte>.addTlv(tag: Int, value: ByteArray) {
        val len = value.size + 1
        add((len and 0xFF).toByte())
        add(((len shr 8) and 0xFF).toByte())
        add(tag.toByte())
        for (b in value) add(b)
    }
}

// ── Notification push (new "commonprotocol" generation, MessageNewBT cmd 0x76) ──
//
// Our watch (Norm 2) maps to the `New` push generation: BlueToothDevice.getMessagePushConfig
// returns ofNew() (it's not in the PerfectSocial OEM list, not W04D), so the official app pushes
// notifications via sendMessageNew(MessageNewBT) — cmd 0x76 (SOCIAL_EX_PUSH byte), SET, with NO
// switch-enable / preamble (SwitchSettingBT is dead code in the APK). The legacy MessageBT (0x79)
// in NotificationPushCommand above is acked-but-ignored by this firmware.
//
// Body = MessageNewBT @Order fields encoded by CommonProtocolCodec (FieldHandler rules):
//   [type][countOrVersion][titleLen][contentLen][titleBytes][contentBytes][dateBytes][shockType][needReply]
// Source: MessageNewBT.smali (@Order + @BLEField), MessageNewBTFactory.smali (field values/date).
object MessageNewCommand {

    /** Icon category for a package — same PackageTypeData map as the legacy command. */
    fun socialTypeForPackage(pkg: String): Byte = NotificationPushCommand.socialTypeForPackage(pkg)

    // MessageNewBTFactory uses TimeFormatter("yyyyMMdd'T'HHmmss") in local time.
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun formatDate(instant: Instant = Instant.now()): String =
        DATE_FMT.format(instant.atZone(ZoneId.systemDefault()))

    /**
     * MessageNewBT body (the SET payload, without the 0x6F frame). Prefer this when routing
     * through the write queue so the frame is built once and MTU-chunked. Defaults match the
     * MessageNewBT ctor: countOrVersion=1, shockType=0xFF, needReply=false.
     */
    fun appNotificationPayload(
        type: Byte,
        title: String,
        content: String,
        date: String = formatDate(),
        countOrVersion: Int = 1,
        shockType: Int = 0xFF,
        needReply: Boolean = false,
    ): ByteArray = CommonProtocolCodec.encodeBody(
        listOf(
            CommonProtocolCodec.Scalar(type.toInt() and 0xFF),
            CommonProtocolCodec.Scalar(countOrVersion),
            // Reorder RTL (Hebrew/Arabic) to visual order first — the watch draws bytes
            // left-to-right and does no bidi of its own (matches the original app). See BidiUtil.
            CommonProtocolCodec.LenText(BidiUtil.formatRtlString(title), 0x5A),    // @BLEField maxLength 90
            CommonProtocolCodec.LenText(BidiUtil.formatRtlString(content), 0x80),  // @BLEField maxLength 128
            CommonProtocolCodec.RawText(date),
            CommonProtocolCodec.Scalar(shockType),
            CommonProtocolCodec.Scalar(if (needReply) 1 else 0),
        )
    )

    fun buildAppNotification(
        type: Byte,
        title: String,
        content: String,
        date: String = formatDate(),
    ): ByteArray = PacketBuilder.build(
        CommandCode.SOCIAL_EX_PUSH, Action.SET,
        appNotificationPayload(type, title, content, date),
    )
}

// ── Byte helpers ──────────────────────────────────────────────────────────────

fun ByteArray.readInt32LE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
