package com.normplus.protocol

import com.normplus.protocol.commands.MessageNewCommand
import com.normplus.protocol.util.BidiUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for the RTL visual-reordering transform ported from `cn.appscomm.util.BidiUtil`.
 * The watch renders bytes left-to-right as received, so we must pre-order RTL text visually.
 */
class BidiUtilTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it) }

    @Test
    fun `empty and pure-ASCII strings are returned unchanged`() {
        assertEquals("", BidiUtil.formatRtlString(""))
        assertEquals("Hello, world!", BidiUtil.formatRtlString("Hello, world!"))
        assertEquals("WA", BidiUtil.formatRtlString("WA"))
    }

    @Test
    fun `a pure Hebrew string is reversed into visual order`() {
        // "שלום" is a single RTL run → the whole run is reversed (one level-1 run).
        val hebrew = "שלום"
        assertEquals(hebrew.reversed(), BidiUtil.formatRtlString(hebrew))
    }

    @Test
    fun `mixed LTR + Hebrew reverses only the RTL run, not the whole string`() {
        val mixed = "hello שלום"
        val out = BidiUtil.formatRtlString(mixed)
        assertTrue(out.contains("hello"), "LTR run must keep its order")
        assertTrue(out.contains("שלום".reversed()), "RTL run must be reversed")
        assertNotEquals(mixed.reversed(), out, "must not be a naive whole-string reverse")
    }

    @Test
    fun `Arabic text is reshaped to contextual presentation forms then reversed`() {
        // input = beh (U+0628) + teh (U+062A). beh connects to the next → initial form U+FE91;
        // teh connects to the prev → final form U+FE96. Reshaped logical [FE91, FE96], then the
        // single RTL run is reversed → visual [FE96, FE91].
        val input = "${0x628.toChar()}${0x62A.toChar()}"          // beh + teh
        val expected = "${0xFE96.toChar()}${0xFE91.toChar()}"     // teh-final, beh-initial
        assertEquals(expected, BidiUtil.formatRtlString(input))
    }

    @Test
    fun `MessageNewBT encodes Hebrew title in visual (reversed) byte order`() {
        val hebrew = "שלום"
        val frame = MessageNewCommand.buildAppNotification(
            type = 0x02, title = hebrew, content = "Hi", date = "20260101T000000",
        )
        val p = PacketDeframer().feed(frame).single().payload
        // payload = [type][cv][titleLen][contentLen][title...][content...][date][shock][reply]
        val titleLen = p[2].toInt() and 0xFF
        val titleBytes = p.copyOfRange(4, 4 + titleLen)

        // The title bytes are the *visually reversed* Hebrew, not the raw logical-order Hebrew.
        assertEquals(hex(hebrew.reversed().toByteArray(Charsets.UTF_8)), hex(titleBytes))
        assertFalse(
            hex(titleBytes) == hex(hebrew.toByteArray(Charsets.UTF_8)),
            "title must not be sent in logical order",
        )
    }
}
