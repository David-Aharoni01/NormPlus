package com.normplus.protocol

import com.normplus.protocol.commands.UpgradeModeCommand
import com.normplus.protocol.ota.ApolloOta
import com.normplus.protocol.ota.ApolloOtaSession
import com.normplus.protocol.ota.OtaException
import com.normplus.protocol.ota.OtaImage
import com.normplus.protocol.ota.OtaProgress
import com.normplus.protocol.ota.OtaStep
import com.normplus.protocol.ota.OtaTransport
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The Apollo DFU client against what the watch's firmware accepted.
 *
 * The byte-exact half checks the frames for the real resource image against the ones the
 * firmware answered `02 01` and `04 01` to in the watch emulator (tools/watchemu/tests/
 * test_ota.py). The session half runs whole updates against [FakeFirmware], which answers the
 * way the emulated watch's OTA responder did: every 200 bytes, or at a page boundary, with the
 * running count; SET refused for any type but 1-4.
 */
class ApolloOtaTest {

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    private fun asset(name: String): ByteArray {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !File(dir, "NORM/assets").isDirectory) dir = dir.parentFile
        return File(dir ?: error("NORM/assets not found above the working directory"),
            "NORM/assets/$name").readBytes()
    }

    private val resources: ByteArray by lazy { asset("Picture_P03B_NORM2_0.4.bin") }
    private val firmware: ByteArray by lazy { asset("Apollo3_P03B_NORM2_F0.2B01.bin") }

    // -- the frames -----------------------------------------------------------------

    @Test
    fun `the SET header for the resource image is the one the firmware accepted`() {
        val image = OtaImage.parse(resources)
        assertEquals("00 00 78 0c", hex(image.address))                     // 0x0C780000
        assertEquals("02 04 00 00 78 0c 02 22 06 00 a3 8c 00 00 0a",
            hex(ApolloOta.setHeader(ApolloOta.TYPE_PICTURE_LANGUAGE, image.address, image.content)))
        assertEquals("a3 8c 00 00", hex(ApolloOta.crc(image.content)))
        assertEquals("01 02 22 06 00", hex(ApolloOta.init(image.content)))
    }

    @Test
    fun `the frames for the main firmware are the ones the firmware accepted`() {
        // tools/tests/test_ota_mcu.py: SET 02 01, every piece, CRC 04 01, REBOOT 05 01,
        // the image staged at 0x0FC00000 unchanged.
        val image = OtaImage.parse(firmware)
        assertEquals(ApolloOta.TYPE_APOLLO, ApolloOta.updateTypeFor("Apollo3_P03B_NORM2_F0.2B01.bin"))
        assertEquals("00 00 c0 0f", hex(image.address))                     // 0x0FC00000
        assertEquals("02 01 00 00 c0 0f c0 66 0b 00 79 19 00 00 0a",
            hex(ApolloOta.setHeader(ApolloOta.TYPE_APOLLO, image.address, image.content)))
        assertEquals("01 c0 66 0b 00", hex(ApolloOta.init(image.content)))
        assertEquals(4013, ApolloOta.pieces(image.content).size)
        assertEquals(0x14, ApolloOta.writeSize(ApolloOta.TYPE_APOLLO))
    }

    @Test
    fun `the pieces are cut the way the app cuts them`() {
        val content = OtaImage.parse(resources).content
        val pieces = ApolloOta.pieces(content)
        assertEquals(2159, pieces.size)
        assertEquals(List(10) { 200 } + 48, pieces.take(11).map { it.size })   // one 2 KB page
        assertEquals(hex(content), hex(pieces.reduce { a, b -> a + b }))
        val tail = content.size % ApolloOta.PAGE_SIZE
        assertEquals(tail, pieces.takeLast((tail + 199) / 200).sumOf { it.size })
    }

    @Test
    fun `the update type comes from the file name as getUpdateType decides it`() {
        assertEquals(4, ApolloOta.updateTypeFor("Picture_P03B_NORM2_0.4.bin").toInt())
        assertEquals(1, ApolloOta.updateTypeFor("Apollo3_P03B_NORM2_F0.2B01.bin").toInt())
        assertEquals(2, ApolloOta.updateTypeFor("TouchPanel_x.bin").toInt())
        assertEquals(3, ApolloOta.updateTypeFor("HeartRate_x.bin").toInt())
        assertEquals(6, ApolloOta.updateTypeFor("GPS_x.bin").toInt())
        assertEquals(4, ApolloOta.updateTypeFor("Language_x.bin").toInt())
    }

    @Test
    fun `UPGRADE_MODE is SET with a zero byte, as enterUpdateMode sends it`() {
        assertEquals("6f 0e 71 01 00 00 8f", hex(UpgradeModeCommand.buildSet()))
    }

    @Test
    fun `a Telink image is refused before anything is sent`() {
        val telink = ByteArray(64).also { "KNLT".toByteArray().copyInto(it, 8) }
        assertContains(assertFailsWith<OtaException> { OtaImage.parse(telink) }.message!!, "Telink")
    }

    // -- the session against a firmware ----------------------------------------------------

    /** 2 pages and 904 bytes: two page boundaries and a short tail. */
    private val small = OtaImage(byteArrayOf(0, 0, 0x78, 0x0C), ByteArray(2 * 2048 + 904) { (it * 7).toByte() })

    private fun run(fw: FakeFirmware, type: Byte = 4, image: OtaImage = small, allowMcu: Boolean = false) =
        runBlocking { ApolloOtaSession(fw, replyTimeoutMs = 200, rebootTimeoutMs = 200).flash(image, type, allowMcu).toList() }

    @Test
    fun `a whole update goes through, every piece answered`() {
        val fw = FakeFirmware()
        val progress = run(fw)
        assertTrue(fw.upgradeMode)
        assertEquals(listOf("10 02", "01 ${hex(ApolloOta.le32(small.content.size))}"),
            fw.control.take(2).map(::hex))
        assertEquals(15, fw.control[2].size)
        assertEquals(listOf("04", "05"), fw.control.drop(3).map(::hex))
        assertEquals(hex(small.content), hex(fw.received.toByteArray()))
        assertTrue(fw.dataWrites.all { it <= 0x80 }, "a data write over 128 bytes")
        val pieces = ApolloOta.pieces(small.content).size
        assertEquals(pieces, fw.answers)
        assertEquals(OtaStep.DONE, progress.last().step)
        assertEquals(pieces, progress.count { it.step == OtaStep.DATA_STREAM })
        assertTrue(fw.closed)
    }

    @Test
    fun `type 8 is refused before anything is sent`() {
        val fw = FakeFirmware()
        assertContains(assertFailsWith<OtaException> { run(fw, type = 8) }.message!!, "not one this watch accepts")
        assertEquals(0, fw.control.size)
        assertTrue(!fw.upgradeMode)
    }

    @Test
    fun `a SET the watch refuses fails the update there`() {
        val fw = FakeFirmware(acceptedTypes = setOf(1, 2, 3))
        val e = assertFailsWith<OtaException> { run(fw) }
        assertContains(e.message!!, "SET (update type 4)")
        assertContains(e.message!!, "02 00")
        assertEquals(0, fw.received.size)
        assertTrue(fw.closed)
    }

    @Test
    fun `a main-firmware update is refused unless allowed`() {
        val fw = FakeFirmware()
        assertContains(assertFailsWith<OtaException> { run(fw, type = 1) }.message!!, "Main-firmware")
        assertEquals(0, fw.control.size)
        val allowed = FakeFirmware()
        assertEquals(OtaStep.DONE, run(allowed, type = 1, allowMcu = true).last().step)
        assertEquals(1, allowed.control[2][1].toInt())
        assertTrue(allowed.dataWrites.all { it <= 0x14 }, "a type-1 data write over 20 bytes")
    }

    @Test
    fun `a data answer with the wrong count fails the update`() {
        val fw = FakeFirmware(miscountAt = 3)
        assertContains(assertFailsWith<OtaException> { run(fw) }.message!!, "Data piece 3")
    }

    @Test
    fun `a link too small for 128-byte writes fails before BT_PARAM`() {
        val fw = FakeFirmware(maxWrite = 20)
        assertContains(assertFailsWith<OtaException> { run(fw) }.message!!, "20-byte writes")
        assertEquals(0, fw.control.size)
    }

    /** The watch's OTA responder, as the emulated firmware behaved. */
    private class FakeFirmware(
        val acceptedTypes: Set<Int> = setOf(1, 2, 3, 4),
        val maxWrite: Int = 244,
        val miscountAt: Int = -1,
    ) : OtaTransport {
        var upgradeMode = false
        var closed = false
        val control = mutableListOf<ByteArray>()
        val dataWrites = mutableListOf<Int>()
        val received = mutableListOf<Byte>()
        var answers = 0
        private var length = 0
        private var sinceAnswer = 0
        private val replies = ArrayDeque<ByteArray>()

        override suspend fun enterUpgradeMode() { upgradeMode = true }
        override suspend fun openDfu() = maxWrite
        override fun clearReplies() = replies.clear()
        override fun close() { closed = true }
        override suspend fun nextReply(timeoutMs: Long): ByteArray? = replies.removeFirstOrNull()

        override suspend fun writeControl(bytes: ByteArray) {
            control += bytes
            when (bytes[0].toInt()) {
                0x10 -> Unit                                              // BT_PARAM: no reply
                0x01 -> replies += byteArrayOf(0x01, if (bytes.size == 5) 0x01 else 0x00)
                0x02 -> {
                    val ok = bytes.size == 15 && bytes[1].toInt() in acceptedTypes
                    if (ok) length = ApolloOta.readLe32(bytes, 6)
                    replies += byteArrayOf(0x02, if (ok) 0x01 else 0x00)
                }
                0x04 -> replies += byteArrayOf(0x04, if (received.size == length) 0x01 else 0x00)
                0x05 -> replies += byteArrayOf(0x05, 0x01)
            }
        }

        override suspend fun writeData(bytes: ByteArray) {
            dataWrites += bytes.size
            received += bytes.toList()
            sinceAnswer += bytes.size
            val total = received.size
            if (sinceAnswer >= 200 || total % 2048 == 0 || total == length) {
                answers++
                sinceAnswer = 0
                val count = if (answers == miscountAt) total - 1 else total
                replies += byteArrayOf(0x03, 0x01, if (total % 2048 == 0) 0x02 else 0x01) +
                    ApolloOta.le32(count)
            }
        }
    }
}
