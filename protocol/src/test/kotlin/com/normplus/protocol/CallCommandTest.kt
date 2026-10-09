package com.normplus.protocol

import com.normplus.protocol.commands.MessageNewCommand
import com.normplus.protocol.commands.NotificationPushCommand
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Calls reuse MessageNewBT (cmd 0x76); only the leading type byte distinguishes the call state:
 * incoming 0x05, missed 0x00, ended 0x06. Verify the framed packet carries the right type.
 */
class CallCommandTest {

    private fun typeByteOf(frame: ByteArray): Byte {
        val pkt = PacketDeframer().feed(frame).single()
        assertEquals(CommandCode.SOCIAL_EX_PUSH, pkt.cmdCode)
        assertEquals(Action.SET, pkt.action)
        return pkt.payload[0] // MessageNewBT body starts with the type byte
    }

    @Test
    fun `incoming call frame carries type 0x05`() {
        val frame = MessageNewCommand.buildAppNotification(
            type = NotificationPushCommand.TYPE_INCOMING_CALL, title = "Alice", content = "",
            date = "20260101T000000",
        )
        assertEquals(0x05.toByte(), typeByteOf(frame))
    }

    @Test
    fun `missed call frame carries type 0x00`() {
        val frame = MessageNewCommand.buildAppNotification(
            type = NotificationPushCommand.TYPE_MISSED_CALL, title = "Alice", content = "5551234",
            date = "20260101T000000",
        )
        assertEquals(0x00.toByte(), typeByteOf(frame))
    }

    @Test
    fun `call-ended frame carries type 0x06`() {
        val frame = MessageNewCommand.buildAppNotification(
            type = NotificationPushCommand.TYPE_CALL_ENDED, title = "", content = "",
            date = "20260101T000000",
        )
        assertEquals(0x06.toByte(), typeByteOf(frame))
    }
}
