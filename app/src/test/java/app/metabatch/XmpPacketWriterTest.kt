package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class XmpPacketWriterTest {

    private val packetA = MetadataXmpBuilder.build(
        MetadataValues("VAM日落", "海边日落", listOf("风景", "旅行"), "bo", "bo", "ChatGPT")
    ).toByteArray(Charsets.UTF_8)
    private val packetB = MetadataXmpBuilder.build(
        MetadataValues("山顶星空", "", listOf("星空"), "", "", "")
    ).toByteArray(Charsets.UTF_8)

    // ---- JPEG ----

    @Test
    fun jpeg_write_roundTripsChineseWithoutQuestionMarks() {
        val file = tempFile("mb-a", ".jpg").apply { writeBytes(fakeJpeg()) }
        XmpPacketWriter.write(file, packetA)
        val fields = readBack(file, "image/jpeg")
        assertEquals("VAM日落", fields.title)
        assertEquals(listOf("风景", "旅行"), fields.keywords)
        assertEquals("海边日落", fields.description)
        // Raw bytes must hold real UTF-8 for 日落, not '?' (0x3F) substitution.
        val raw = file.readBytes()
        assertTrue(raw.containsUtf8("日落"))
        assertFalse(raw.containsUtf8("VAM??"))
    }

    @Test
    fun jpeg_writeTwice_replacesSingleSegment() {
        val file = tempFile("mb-b", ".jpg").apply { writeBytes(fakeJpeg()) }
        XmpPacketWriter.write(file, packetA)
        XmpPacketWriter.write(file, packetB)
        assertEquals("山顶星空", readBack(file, "image/jpeg").title)
        assertEquals(1, countJpegXmpSegments(file.readBytes()))
    }

    // ---- PNG ----

    @Test
    fun png_write_roundTripsChinese() {
        val file = tempFile("mb-c", ".png").apply { writeBytes(fakePng()) }
        XmpPacketWriter.write(file, packetA)
        val fields = readBack(file, "image/png")
        assertEquals("VAM日落", fields.title)
        assertEquals(listOf("风景", "旅行"), fields.keywords)
        assertTrue(file.readBytes().containsUtf8("日落"))
    }

    @Test
    fun png_writeTwice_replacesSingleChunk() {
        val file = tempFile("mb-d", ".png").apply { writeBytes(fakePng()) }
        XmpPacketWriter.write(file, packetA)
        XmpPacketWriter.write(file, packetB)
        assertEquals("山顶星空", readBack(file, "image/png").title)
        assertEquals(1, countPngXmpChunks(file.readBytes()))
    }

    // ---- WebP ----

    @Test
    fun webp_write_roundTripsChinese() {
        val file = tempFile("mb-e", ".webp").apply { writeBytes(fakeWebP()) }
        XmpPacketWriter.write(file, packetA)
        val fields = readBack(file, "image/webp")
        assertEquals("VAM日落", fields.title)
        assertEquals(listOf("风景", "旅行"), fields.keywords)
    }

    @Test
    fun webp_writeTwice_replacesSingleChunk() {
        val file = tempFile("mb-f", ".webp").apply { writeBytes(fakeWebP()) }
        XmpPacketWriter.write(file, packetA)
        XmpPacketWriter.write(file, packetB)
        assertEquals("山顶星空", readBack(file, "image/webp").title)
        assertEquals(1, countWebpXmpChunks(file.readBytes()))
        // VP8L image chunk must survive the rewrite.
        assertTrue(String(file.readBytes(), Charsets.US_ASCII).contains("VP8L"))
    }

    // ---- ASCII guard ----

    @Test
    fun isAscii_onlyAscii() {
        assertTrue(MetadataWriter.isAscii("VAM sunset 2026"))
        assertTrue(MetadataWriter.isAscii(""))
        assertFalse(MetadataWriter.isAscii("VAM日落"))
        assertFalse(MetadataWriter.isAscii("风景"))
    }

    // ---- helpers ----

    private fun readBack(file: File, mime: String): XmpFields {
        val bytes = XmpPacketReader.extractBytes(file.inputStream(), mime)
            ?: throw AssertionError("no XMP found after write")
        return MetadataReader.parseXmp(XmpPacketReader.decode(bytes))
    }

    private fun tempFile(prefix: String, suffix: String): File =
        File.createTempFile(prefix, suffix)

    private fun ByteArray.containsUtf8(s: String): Boolean {
        val ndl = s.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..size - ndl.size) {
            for (j in ndl.indices) if (this[i + j] != ndl[j]) continue@outer
            return true
        }
        return false
    }

    private fun countJpegXmpSegments(bytes: ByteArray): Int {
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        var count = 0
        var pos = 2
        while (pos + 4 <= bytes.size) {
            if (bytes[pos] != 0xFF.toByte()) break
            val marker = bytes[pos + 1].toInt() and 0xFF
            if (marker == 0xDA) break
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                pos += 2; continue
            }
            val len = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            if (len < 2) break
            if (marker == 0xE1 && len - 2 >= header.size) {
                val data = bytes.copyOfRange(pos + 4, pos + 4 + header.size)
                if (data.contentEquals(header)) count++
            }
            pos += 2 + len
        }
        return count
    }

    private fun countPngXmpChunks(bytes: ByteArray): Int {
        var count = 0
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            if (type == "IEND") break
            if (type == "iTXt" &&
                String(bytes, pos + 8, "XML:com.adobe.xmp".length, Charsets.US_ASCII) == "XML:com.adobe.xmp"
            ) count++
            pos += 12 + len
        }
        return count
    }

    private fun countWebpXmpChunks(bytes: ByteArray): Int {
        var count = 0
        var pos = 12
        while (pos + 8 <= bytes.size) {
            val fourcc = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = (bytes[pos + 4].toInt() and 0xFF) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 7].toInt() and 0xFF) shl 24)
            if (fourcc == "XMP ") count++
            pos += 8 + size + if (size % 2 == 1) 1 else 0
        }
        return count
    }

    /** Minimal JPEG: SOI, APP0, SOS, a few scan bytes. No EXIF/XMP. */
    private fun fakeJpeg(): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10))
        write("JFIF\u0000".toByteArray(Charsets.US_ASCII))
        write(ByteArray(9))
        write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00))
        write(ByteArray(8))
    }.toByteArray()

    /** Minimal PNG: signature, IHDR, IDAT, IEND. CRCs are dummy; reader ignores them. */
    private fun fakePng(): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writeChunk("IHDR", byteArrayOf(
            0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0
        ))
        writeChunk("IDAT", byteArrayOf(0x78, 0x9C.toByte(), 0x03, 0x00, 0x00, 0x00, 0x00, 0x01))
        writeChunk("IEND", ByteArray(0))
    }.toByteArray()

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        write((data.size ushr 24) and 0xFF); write((data.size ushr 16) and 0xFF)
        write((data.size ushr 8) and 0xFF); write(data.size and 0xFF)
        write(type.toByteArray(Charsets.US_ASCII))
        write(data)
        write(ByteArray(4)) // dummy CRC
    }

    /** Minimal WebP: RIFF header, one VP8L chunk. */
    private fun fakeWebP(): ByteArray = ByteArrayOutputStream().apply {
        write("RIFF".toByteArray(Charsets.US_ASCII))
        val size = 4 + 8 + 4
        write(size and 0xFF); write((size ushr 8) and 0xFF)
        write((size ushr 16) and 0xFF); write((size ushr 24) and 0xFF)
        write("WEBP".toByteArray(Charsets.US_ASCII))
        write("VP8L".toByteArray(Charsets.US_ASCII))
        write(4); write(0); write(0); write(0)
        write(byteArrayOf(0x2F, 0x00, 0x00, 0x00))
    }.toByteArray()
}
