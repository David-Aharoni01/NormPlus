package com.normplus.protocol

import kotlin.test.Test
import kotlin.test.assertEquals

class PacketTest {

    // ── PacketBuilder ─────────────────────────────────────────────────────────

    @Test
    fun `build frames battery CHECK correctly`() {
        // Source: BatteryPower.smali — wire: [6F 08 70 01 00 00 8F]
        val frame = PacketBuilder.build(CommandCode.BATTERY_POWER, Action.CHECK, byteArrayOf(0x00))
        val expected = byteArrayOf(0x6F, 0x08, 0x70, 0x01, 0x00, 0x00, 0x8F.toByte())
        assertEquals(expected.toList(), frame.toList(), "Battery CHECK frame mismatch")
    }

    @Test
    fun `build frames empty payload correctly`() {
        // No payload → lenLo=0, lenHi=0
        val frame = PacketBuilder.build(CommandCode.WATCH_ID, Action.SET)
        assertEquals(6, frame.size)
        assertEquals(0x6F.toByte(), frame[0])
        assertEquals(CommandCode.WATCH_ID.byte, frame[1])
        assertEquals(Action.SET.byte, frame[2])
        assertEquals(0x00.toByte(), frame[3]) // lenLo
        assertEquals(0x00.toByte(), frame[4]) // lenHi
        assertEquals(0x8F.toByte(), frame[5])
    }

    @Test
    fun `build encodes payload length little-endian`() {
        val payload = ByteArray(0x0102) { 0xAB.toByte() }
        val frame = PacketBuilder.build(CommandCode.DEVICE_VERSION, Action.CHECK, payload)
        assertEquals(0x02.toByte(), frame[3], "lenLo should be 0x02")
        assertEquals(0x01.toByte(), frame[4], "lenHi should be 0x01")
        assertEquals(0x8F.toByte(), frame.last())
    }

    @Test
    fun `0x8F inside payload does not end frame early`() {
        // Payload containing FLAG_END (0x8F) — deframer must not split on it
        val payload = byteArrayOf(0x00, 0x8F.toByte(), 0x01)
        val frame = PacketBuilder.build(CommandCode.BATTERY_POWER, Action.CHECK_RESPONSE, payload)
        val deframer = PacketDeframer()
        val pkt = deframer.feed(frame).single()
        assertEquals(payload.toList(), pkt.payload.toList(), "Should parse packet containing 0x8F in payload")
    }

    // ── PacketDeframer ────────────────────────────────────────────────────────

    @Test
    fun `deframer round-trips a simple packet`() {
        val frame = PacketBuilder.build(CommandCode.BATTERY_POWER, Action.CHECK_RESPONSE, byteArrayOf(0x50))
        val pkt = PacketDeframer().feed(frame).single()
        assertEquals(CommandCode.BATTERY_POWER, pkt.cmdCode)
        assertEquals(Action.CHECK_RESPONSE, pkt.action)
        assertEquals(byteArrayOf(0x50).toList(), pkt.payload.toList())
    }

    @Test
    fun `deframer reassembles MTU-chunked frame`() {
        val payload = ByteArray(35) { it.toByte() }
        val frame = PacketBuilder.build(CommandCode.GET_SPORT_DATA, Action.CHECK_RESPONSE, payload)
        val deframer = PacketDeframer()
        // Feed 20-byte chunks (simulating MTU=20)
        val result = mutableListOf<Packet>()
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + 20, frame.size)
            result += deframer.feed(frame.copyOfRange(offset, end))
            offset = end
        }
        assertEquals(1, result.size, "Should reassemble exactly one multi-chunk frame")
        assertEquals(payload.toList(), result.single().payload.toList())
    }

    @Test
    fun `deframer returns nothing on garbage without a start byte`() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02)
        assertEquals(emptyList(), PacketDeframer().feed(garbage), "Should not parse garbage without FLAG_START")
    }

    @Test
    fun `deframer resyncs after unparseable frame`() {
        val bad = byteArrayOf(0x6F.toByte(), 0xFF.toByte(), 0x70, 0x01, 0x00, 0xFF.toByte(), 0x8F.toByte()) // unknown cmd
        val good = PacketBuilder.build(CommandCode.BATTERY_POWER, Action.CHECK, byteArrayOf(0x00))
        val deframer = PacketDeframer()
        deframer.feed(bad)  // should not crash; logs a warning
        val result = deframer.feed(good)
        assertEquals(1, result.size, "Deframer should recover and parse next valid frame")
    }

    @Test
    fun `deframer recovers a valid frame trapped behind a truncated one`() {
        // Regression for the sport-sync desync seen on-device: the watch sent a 28-byte sport
        // record header but DROPPED its 14-byte continuation, sending the DELETE_SPORT_DATA ack
        // instead. The old length-guided deframer swallowed the ack as "payload" and corrupted
        // the channel. The self-synchronising deframer must abandon the truncated record and
        // recover the ack (and the following HR-count response).
        val deframer = PacketDeframer()

        // Real bytes captured from logcat (cmd 0x54 record header, declared payload len 0x1C=28).
        val truncatedRecordHead = hex("6F54801C00030044EA1B6A00000000E880000000") // 20 bytes, only 15 of 28 payload
        val deleteAck = hex("6F0181020053008F")                                     // generic RESPONSE/SET_RESPONSE for DELETE_SPORT_DATA (0x53)
        val hrCount = hex("6F59800400000000008F")                                   // TOTAL_HEART_RATE_COUNT CHECK_RESPONSE, count=0

        // Header alone: incomplete, nothing emitted yet.
        assertEquals(emptyList(), deframer.feed(truncatedRecordHead))

        // The unrelated ack arrives instead of the 14-byte continuation. The record is now provably
        // truncated (a fully-present, valid frame sits where its payload should continue), so the
        // deframer abandons it and recovers the delete-ack immediately — no waiting, no corruption.
        val afterAck = deframer.feed(deleteAck)
        assertEquals(1, afterAck.size, "delete-ack must be recovered as soon as it is fully buffered")
        assertEquals(CommandCode.RESPONSE, afterAck.single().cmdCode)
        assertEquals(Action.SET_RESPONSE, afterAck.single().action)
        assertEquals(CommandCode.DELETE_SPORT_DATA.byte, afterAck.single().payload[0])

        // The channel is fully resynced: the next response parses cleanly.
        val afterHr = deframer.feed(hrCount)
        assertEquals(1, afterHr.size, "HR-count response must parse on a resynced channel")
        assertEquals(CommandCode.TOTAL_HEART_RATE_COUNT, afterHr.single().cmdCode)
        assertEquals(Action.CHECK_RESPONSE, afterHr.single().action)
    }

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
