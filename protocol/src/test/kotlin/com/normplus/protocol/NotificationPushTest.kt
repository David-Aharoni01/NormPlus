package com.normplus.protocol

import com.normplus.protocol.commands.CommonProtocolCodec
import com.normplus.protocol.commands.MessageNewCommand
import com.normplus.protocol.commands.NotificationPushCommand
import com.normplus.protocol.commands.readInt32LE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `MessageNewBT (cmd 0x76) matches the commonprotocol Body encoding`() {
        // type=WhatsApp(0x0A), title="WA", content="Hi", date fixed, defaults cv=1/shock=0xFF/reply=0.
        // Body layout (FieldHandler): [type][cv][titleLen][contentLen][title][content][date][shock][reply]
        // length-prefixed title/content emit their length inline, data deferred + flushed after content.
        val frame = MessageNewCommand.buildAppNotification(
            type = MessageNewCommand.socialTypeForPackage("com.whatsapp"),
            title = "WA",
            content = "Hi",
            date = "20260101T000000",
        )
        val expected = byteArrayOf(
            0x6F, 0x76, 0x71,                  // start, SOCIAL_EX_PUSH (0x76), SET
            0x19, 0x00,                        // Leaf contentLen = 25
            0x0A,                              // type = WhatsApp
            0x01,                              // countOrVersion = 1
            0x02,                              // titleLen = 2
            0x02,                              // contentLen = 2
            0x57, 0x41,                        // "WA" (deferred title bytes)
            0x48, 0x69,                        // "Hi" (deferred content bytes)
            0x32, 0x30, 0x32, 0x36, 0x30, 0x31, 0x30, 0x31,  // "20260101"
            0x54, 0x30, 0x30, 0x30, 0x30, 0x30, 0x30,        // "T000000"
            0xFF.toByte(),                     // shockType = 0xFF (default)
            0x00,                              // needReply = false
            0x8F.toByte(),                     // end
        )
        assertEquals(hex(expected), hex(frame))
    }

    @Test
    fun `MessageNewBT framed packet round-trips through the deframer`() {
        val frame = MessageNewCommand.buildAppNotification(
            type = MessageNewCommand.socialTypeForPackage("com.whatsapp"),
            title = "Alice",
            content = "Hey there!",
            date = "20260101T000000",
        )
        val pkt = PacketDeframer().feed(frame).single()
        assertEquals(CommandCode.SOCIAL_EX_PUSH, pkt.cmdCode)
        assertEquals(Action.SET, pkt.action)
        assertEquals(0x0A.toByte(), pkt.payload[0])  // type byte
    }

    @Test
    fun `truncateWithDots caps an over-long ASCII string at maxLen with trailing dots`() {
        val out = CommonProtocolCodec.truncateWithDots("A".repeat(100), 0x5A) // maxLen 90
        assertEquals(90, out.size)
        assertEquals("...", String(out, out.size - 3, 3, Charsets.UTF_8))
        // length byte the encoder would emit == actual byte count, and fits in a byte
        assertTrue(out.size <= 0xFF)
    }

    @Test
    fun `truncateWithDots drops a split multi-byte char, never emitting U+FFFD`() {
        // "é" = C3 A9 (2 bytes); 60 of them = 120 bytes > 90 → the maxLen-3=87 cut lands mid-char.
        val out = CommonProtocolCodec.truncateWithDots("é".repeat(60), 0x5A)
        assertEquals("é".repeat(43) + "...", String(out, Charsets.UTF_8)) // 43*2 + 3 = 89 bytes
        assertEquals(89, out.size)
        assertEquals(-1, out.indexOf(0xEF.toByte()))  // no U+FFFD (EF BF BD) in the output
    }

    @Test
    fun `truncateWithDots leaves an exact-maxLen string untouched (no dots)`() {
        val s = "A".repeat(90)
        assertEquals(s, String(CommonProtocolCodec.truncateWithDots(s, 0x5A), Charsets.UTF_8))
    }

    @Test
    fun `MessageNewBT with empty content emits contentLen=0 and no content bytes`() {
        // frame: 6F 76 71 [lenLo lenHi] [type][cv][titleLen][contentLen][title][content][date][shock][reply] 8F
        val frame = MessageNewCommand.buildAppNotification(
            type = 0x02, title = "Hi", content = "", date = "20260101T000000",
        )
        assertEquals(0x02.toByte(), frame[7])  // titleLen = 2
        assertEquals(0x00.toByte(), frame[8])  // contentLen = 0
        // contentLen is immediately followed by the title bytes (no content bytes in between)
        assertEquals("Hi", String(frame, 9, 2, Charsets.UTF_8))
    }

    @Test
    fun `known apps map to their icon type, unknown to generic`() {
        assertEquals(0x0A.toByte(), NotificationPushCommand.socialTypeForPackage("com.whatsapp"))
        assertEquals(NotificationPushCommand.TYPE_EMAIL, NotificationPushCommand.socialTypeForPackage("com.google.android.gm"))
        assertEquals(NotificationPushCommand.TYPE_GENERIC, NotificationPushCommand.socialTypeForPackage("com.some.unknown.app"))
    }
}
