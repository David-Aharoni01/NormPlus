package com.norm2hacked.protocol.commands

import com.norm2hacked.protocol.Action
import com.norm2hacked.protocol.CommandCode
import com.norm2hacked.protocol.Packet
import com.norm2hacked.protocol.PacketBuilder

// ── First-run binding ──────────────────────────────────────────────────────────
//
// A fresh (or factory-reset) watch sits in first-run setup — language, then a QR pairing
// screen — until a phone *binds* it. Bonding alone does not do it: the watch waits for the
// bind handshake the original app runs right after connecting. The sequence is
// `BindDevice.start6F` (presenter/logic/BindDevice.smali, lines 298-306 of the Java), and
// for the Norm 2 (`LeMovtP03BDevice`, a `MovementDevice` whose `getQRBindType()` includes
// `SUPPORT_QR_CODE_PAIR` but not `SUPPORT_PROTOCOL_SET`) it is three separate commands:
//
//   bindStart   (0x93 SET) [mode]        mode 2 = BIND_START_QR_CODE
//   setDateTime (0x04 SET) [12 bytes]    DateTimeCommand.setPayload, reHome = false
//   bindEnd     (0x94 SET) [0x01]
//
// `MBluetooth.bindStart` gives the first one a 30 s reply timeout (`setSendDataTimeOut(0x1e)`,
// `setHandleChangeTimeOut(true)` so BluetoothSend does not clamp it to the usual 15 s) — the
// watch is expected to take its time answering, i.e. it asks the wearer first.
//
// Wire encodings (BindStart.smali / BindEnd.smali constructors; every other 0x6F command the
// app sends is built the same way):
//
//   bindStart(mode)  mode != 0 → content [mode], len 1
//   setUID(uid)      mode == 0 → content [0x00][uid LE32], len 5      (BIND_START_SET_UID)
//   setUID(string)   content [0x00] + bytes, len = bytes + 1
//   getUID()         0x93 CHECK [0x00]  → response: the body is the UID, LE
//   bindEnd()        0x94 SET   [0x01]
//   checkInit()      0x94 CHECK [0x00]  → response byte[0] == 1: the watch is initialised

object BindStartCommand {
    val CMD = CommandCode.BIND_START

    // Source: BluetoothCommandConstant.smali BIND_START_SET_UID = 0, BIND_START_NO_UID = 1,
    // BIND_START_QR_CODE = 2. start6F picks QR_CODE when the device type has SUPPORT_QR_CODE_PAIR
    // (the Norm 2 does), NO_UID otherwise.
    const val MODE_SET_UID: Int = 0
    const val MODE_NO_UID: Int = 1
    const val MODE_QR_CODE: Int = 2

    /** Reply timeout the original app allows for bindStart — it waits on the wearer. */
    const val REPLY_TIMEOUT_MS: Long = 30_000

    /** `[mode]` — the one-byte body for any mode but SET_UID. */
    fun setPayload(mode: Int = MODE_QR_CODE): ByteArray {
        require(mode != MODE_SET_UID) { "MODE_SET_UID carries a UID; use setUidPayload" }
        return byteArrayOf(mode.toByte())
    }

    /** `[0x00][uid LE32]` — BIND_START_SET_UID with the app's user id (`MBluetooth.setUID(int)`). */
    fun setUidPayload(uid: Int): ByteArray = byteArrayOf(
        MODE_SET_UID.toByte(),
        (uid and 0xFF).toByte(),
        ((uid shr 8) and 0xFF).toByte(),
        ((uid shr 16) and 0xFF).toByte(),
        ((uid shr 24) and 0xFF).toByte(),
    )

    /** 0x93 CHECK `[0x00]` — `MBluetooth.getUID`. */
    fun queryPayload(): ByteArray = byteArrayOf(0x00)

    fun buildSet(mode: Int = MODE_QR_CODE): ByteArray =
        PacketBuilder.build(CMD, Action.SET, setPayload(mode))

    fun buildSetUid(uid: Int): ByteArray =
        PacketBuilder.build(CMD, Action.SET, setUidPayload(uid))

    fun buildQuery(): ByteArray = PacketBuilder.build(CMD, Action.CHECK, queryPayload())

    /**
     * The UID the watch reports: `BindStart.parse80BytesArray` takes `bytesToLong(data, 0, len-1)`
     * — start and *inclusive* end index, so the whole body, little-endian — and stores it as an
     * int. An empty body is 0.
     */
    fun parseUid(p: Packet): Int {
        var uid = 0L
        for (i in p.payload.indices.reversed()) uid = (uid shl 8) or (p.payload[i].toLong() and 0xFF)
        return uid.toInt()
    }
}

object BindEndCommand {
    val CMD = CommandCode.BIND_END

    /** `[0x01]` — `MBluetooth.bindEnd` builds `BindEnd(callback, 1, 1)`. */
    fun setPayload(): ByteArray = byteArrayOf(0x01)

    /** 0x94 CHECK `[0x00]` — `MBluetooth.checkInit`. */
    fun queryPayload(): ByteArray = byteArrayOf(0x00)

    fun buildSet(): ByteArray = PacketBuilder.build(CMD, Action.SET, setPayload())

    fun buildQuery(): ByteArray = PacketBuilder.build(CMD, Action.CHECK, queryPayload())

    /** `BindEnd.parse80BytesArray`: `initFlag = (data[0] & 0xFF) == 1`. */
    fun parseInitialised(p: Packet): Boolean =
        (p.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0) == 1
}
