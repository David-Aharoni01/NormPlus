package com.norm2hacked.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        val pkt = deframer.feed(frame)
        assertNotNull(pkt, "Should parse packet containing 0x8F in payload")
        assertEquals(payload.toList(), pkt.payload.toList())
    }

    // ── PacketDeframer ────────────────────────────────────────────────────────

    @Test
    fun `deframer round-trips a simple packet`() {
        val frame = PacketBuilder.build(CommandCode.BATTERY_POWER, Action.CHECK_RESPONSE, byteArrayOf(0x50))
        val pkt = PacketDeframer().feed(frame)
        assertNotNull(pkt)
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
        var result: Packet? = null
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + 20, frame.size)
            result = deframer.feed(frame.copyOfRange(offset, end))
            if (result != null) break
            offset = end
        }
        assertNotNull(result, "Should reassemble multi-chunk frame")
        assertEquals(payload.toList(), result.payload.toList())
    }

    @Test
    fun `deframer returns null on corrupt frame start`() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02)
        val result = PacketDeframer().feed(garbage)
        assertNull(result, "Should not parse garbage without FLAG_START")
    }

    @Test
    fun `deframer resyncs after corrupt frame`() {
        val bad = byteArrayOf(0x6F.toByte(), 0xFF.toByte(), 0x70, 0x01, 0x00, 0xFF.toByte(), 0x8F.toByte()) // unknown cmd
        val good = PacketBuilder.build(CommandCode.BATTERY_POWER, Action.CHECK, byteArrayOf(0x00))
        val deframer = PacketDeframer()
        deframer.feed(bad)  // should not crash; logs a warning
        val result = deframer.feed(good)
        assertNotNull(result, "Deframer should recover and parse next valid frame")
    }
}
