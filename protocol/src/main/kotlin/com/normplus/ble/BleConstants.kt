package com.normplus.ble

import java.util.UUID

// All UUIDs verified from NORM/smali_classes2/cn/appscomm/bluetooth/BluetoothCommandConstant.smali
// and NORM/smali_classes2/cn/appscomm/ota/OtaService.smali
object BleConstants {
    // Main communication service
    val SERVICE_MAIN: UUID = UUID.fromString("00006006-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE_8001: UUID = UUID.fromString("00008001-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY_8002: UUID = UUID.fromString("00008002-0000-1000-8000-00805f9b34fb")
    val CHAR_WRITE_8003: UUID = UUID.fromString("00008003-0000-1000-8000-00805f9b34fb")
    val CHAR_NOTIFY_8004: UUID = UUID.fromString("00008004-0000-1000-8000-00805f9b34fb")
    val CHAR_8005: UUID = UUID.fromString("00008005-0000-1000-8000-00805f9b34fb")

    // Extended service
    val SERVICE_EXTEND: UUID = UUID.fromString("00007006-0000-1000-8000-00805f9b34fb")

    // Apollo DFU service (firmware update)
    val SERVICE_APOLLO_DFU: UUID = UUID.fromString("00001530-0000-1000-8000-00805f9b34fb")
    val CHAR_APOLLO_1531: UUID = UUID.fromString("00001531-0000-1000-8000-00805f9b34fb")
    val CHAR_APOLLO_1532: UUID = UUID.fromString("00001532-0000-1000-8000-00805f9b34fb")

    // Standard CCCD descriptor — needed to enable notifications
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Standard HR service (read-only)
    val SERVICE_HEART_RATE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val CHAR_HEART_RATE: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    const val FLAG_START: Byte = 0x6F.toByte()
    const val FLAG_END: Byte = 0x8F.toByte()

    const val MTU_DEFAULT = 20
    const val SCAN_TIMEOUT_MS = 30_000L
    const val MIN_CONNECT_INTERVAL_MS = 200L
    const val WRITE_TIMEOUT_MS = 10_000L
    const val SYNC_TIMEOUT_MS = 15_000L
    const val DEDUP_WINDOW_MS = 30_000L

    // Echo packet to drop on 8002 (from BluetoothLeService.smali:array_0)
    // Format: [0x6F][RESPONSE=0x01][SET_RESPONSE=0x81][len=0x02][0x00][0x01][0x00][0x8F]
    val COMMAND_REPEAT_ECHO = byteArrayOf(0x6F, 0x01, 0x81.toByte(), 0x02, 0x00, 0x01, 0x00, 0x8F.toByte())
}
