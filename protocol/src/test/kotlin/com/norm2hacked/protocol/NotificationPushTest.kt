package com.norm2hacked.protocol

import com.norm2hacked.protocol.commands.NotificationPushCommand
import com.norm2hacked.protocol.commands.readInt32LE
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationPushTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `app notification matches the MessageBT wire format`() {
        // messageType=GENERIC(2), id=0, crud=ADD(0), title="WA", content="Hi"
        // Decoded from MessageBT.getBytes():
        //   packet = 6F 79 71 [len2] <messageBT> 8F
        //   messageBT = [bodyLen2] [id4] [crud] [TLV type][TLV title][TLV content]
        //   TLV = [valLen+1 (2,LE)] [tag] [value]
        val frame = NotificationPushCommand.buildAppNotification(
            messageType = NotificationPushCommand.TYPE_GENERIC,
            id = 0,
            title = "WA",
            content = "Hi",
        )
        val expected = byteArrayOf(
            0x6F, 0x79, 0x71,                  // start, SOCIAL_NEW_PUSH, SET
            0x15, 0x00,                        // Leaf contentLen = 21
            0x13, 0x00,                        // MessageBT bodyLen = 19
            0x00, 0x00, 0x00, 0x00,            // id (LE4) = 0
            0x00,                              // crud = ADD
            0x02, 0x00, 0x01, 0x02,            // TLV messageType: len=2, tag=0x01, value=2
            0x03, 0x00, 0x02, 0x57, 0x41,      // TLV title: len=3, tag=0x02, "WA"
            0x03, 0x00, 0x03, 0x48, 0x69,      // TLV content: len=3, tag=0x03, "Hi"
            0x8F.toByte(),                     // end
        )
        assertEquals(hex(expected), hex(frame))
    }

    @Test
    fun `framed packet round-trips through the deframer`() {
        val frame = NotificationPushCommand.buildAppNotification(
            messageType = NotificationPushCommand.socialTypeForPackage("com.whatsapp"),
            id = 12345,
            title = "Alice",
            content = "Hey there!",
        )
        val pkt = PacketDeframer().feed(frame).single()
        assertEquals(CommandCode.SOCIAL_NEW_PUSH, pkt.cmdCode)
        assertEquals(Action.SET, pkt.action)
        // payload[2..5] = id (LE) = 12345
        assertEquals(12345, pkt.payload.readInt32LE(2))
    }

    @Test
    fun `known apps map to their icon type, unknown to generic`() {
        assertEquals(0x0A.toByte(), NotificationPushCommand.socialTypeForPackage("com.whatsapp"))
        assertEquals(NotificationPushCommand.TYPE_EMAIL, NotificationPushCommand.socialTypeForPackage("com.google.android.gm"))
        assertEquals(NotificationPushCommand.TYPE_GENERIC, NotificationPushCommand.socialTypeForPackage("com.some.unknown.app"))
    }
}
