package com.normplus.protocol

import com.normplus.protocol.commands.BindEndCommand
import com.normplus.protocol.commands.BindStartCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Byte-exact tests for the first-run bind handshake (BindStart 0x93 / BindEnd 0x94).
 * Frame = [0x6F][cmd][action][lenLo][lenHi][payload...][0x8F]; the payloads are what
 * BindStart.smali / BindEnd.smali build for each MBluetooth entry point.
 */
class BindCommandTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `bindStart in QR mode is 6F 93 71 01 00 02 8F`() {
        // MBluetooth.bindStart(cb, mode, uid): BindStart(cb, 1, mode, uid), mode != 0 → [mode]
        assertEquals("6F 93 71 01 00 02 8F", hex(BindStartCommand.buildSet()))
        assertEquals("6F 93 71 01 00 01 8F", hex(BindStartCommand.buildSet(BindStartCommand.MODE_NO_UID)))
    }

    @Test
    fun `bindStart SET_UID carries the uid little-endian after a zero mode byte`() {
        // MBluetooth.setUID(cb, int): BindStart(cb, 5, 0, uid) → [0][uid LE32]
        assertEquals(
            "6F 93 71 05 00 00 78 56 34 12 8F",
            hex(BindStartCommand.buildSetUid(0x12345678)),
        )
        // setPayload refuses SET_UID: that mode has a body this builder cannot supply
        assertFailsWith<IllegalArgumentException> { BindStartCommand.setPayload(BindStartCommand.MODE_SET_UID) }
    }

    @Test
    fun `getUID and checkInit are CHECKs with a zero byte`() {
        // MBluetooth.getUID: BindStart(cb, 1, 0) → CHECK [00]; checkInit: BindEnd(cb, 1, 0) → CHECK [00]
        assertEquals("6F 93 70 01 00 00 8F", hex(BindStartCommand.buildQuery()))
        assertEquals("6F 94 70 01 00 00 8F", hex(BindEndCommand.buildQuery()))
    }

    @Test
    fun `bindEnd is 6F 94 71 01 00 01 8F`() {
        // MBluetooth.bindEnd: BindEnd(cb, 1, (byte) 1) → [01]
        assertEquals("6F 94 71 01 00 01 8F", hex(BindEndCommand.buildSet()))
    }

    @Test
    fun `the uid reply is the whole body little-endian`() {
        // BindStart.parse80BytesArray: bytesToLong(data, 0, len - 1) — inclusive end, so every byte
        val four = PacketDeframer().feed(
            byteArrayOf(0x6F, 0x93.toByte(), 0x80.toByte(), 0x04, 0x00, 0x78, 0x56, 0x34, 0x12, 0x8F.toByte()),
        ).single()
        assertEquals(CommandCode.BIND_START, four.cmdCode)
        assertEquals(0x12345678, BindStartCommand.parseUid(four))

        val one = Packet(CommandCode.BIND_START, Action.SET_RESPONSE, byteArrayOf(0x07))
        assertEquals(7, BindStartCommand.parseUid(one))
        assertEquals(0, BindStartCommand.parseUid(Packet(CommandCode.BIND_START, Action.SET_RESPONSE, byteArrayOf())))
    }

    @Test
    fun `checkInit reply byte 1 means initialised`() {
        // BindEnd.parse80BytesArray: initFlag = (data[0] & 0xFF) == 1
        assertTrue(BindEndCommand.parseInitialised(Packet(CommandCode.BIND_END, Action.CHECK_RESPONSE, byteArrayOf(0x01))))
        assertFalse(BindEndCommand.parseInitialised(Packet(CommandCode.BIND_END, Action.CHECK_RESPONSE, byteArrayOf(0x00))))
        assertFalse(BindEndCommand.parseInitialised(Packet(CommandCode.BIND_END, Action.CHECK_RESPONSE, byteArrayOf())))
    }

    @Test
    fun `the bind frames round-trip through the deframer with the new codes`() {
        val start = PacketDeframer().feed(BindStartCommand.buildSet()).single()
        assertEquals(CommandCode.BIND_START, start.cmdCode)
        assertEquals(Action.SET, start.action)
        assertEquals("02", hex(start.payload))

        val end = PacketDeframer().feed(BindEndCommand.buildSet()).single()
        assertEquals(CommandCode.BIND_END, end.cmdCode)
        assertEquals(Action.SET, end.action)
        assertEquals("01", hex(end.payload))
    }
}
