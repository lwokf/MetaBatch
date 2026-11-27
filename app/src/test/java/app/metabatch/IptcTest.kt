package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Iptc against synthetic datasets and a minimal JPEG built segment by
 * segment — no real photo needed.
 */
class IptcTest {

    private val values = MetadataValues(
        title = "日落",
        description = "傍晚的海边",
        keywords = listOf("风景", "旅行", "风景"),
        creator = "bo",
        copyright = "© 2026 bo",
        source = "手机相册",
        software = "SomeApp"
    )

    private fun segment(marker: Int, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(0xFF); write(marker)
            write(((payload.size + 2) ushr 8) and 0xFF)
            write((payload.size + 2) and 0xFF)
            write(payload)
        }.toByteArray()

    /** SOI + APP0 (JFIF) + SOS + 4 scan bytes + EOI. */
    private fun minimalJpeg(): ByteArray =
        ByteArrayOutputStream().apply {
            write(0xFF); write(0xD8)
            write(segment(0xE0, "JFIF\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0)))
            write(segment(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0)))
            write(byteArrayOf(0x11, 0x22, 0x33, 0x44))
            write(0xFF); write(0xD9)
        }.toByteArray()

    private fun contains(bytes: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..bytes.size - needle.size) {
            for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun buildParse_roundTrip_chinese() {
        val parsed = Iptc.parseDatasets(Iptc.buildDatasets(values))
        assertEquals("日落", parsed.title)
        assertEquals("傍晚的海边", parsed.description)
        assertEquals(listOf("风景", "旅行"), parsed.keywords)
        assertEquals("bo", parsed.creator)
        assertEquals("© 2026 bo", parsed.rights)
        assertEquals("手机相册", parsed.source)
    }

    @Test
    fun buildDatasets_allBlank_isEmpty() {
        val blank = MetadataValues("", "", emptyList(), "", "", "", "")
        assertEquals(0, Iptc.buildDatasets(blank).size)
    }

    @Test
    fun buildDatasets_blankField_omitted() {
        val v = values.copy(title = "", keywords = emptyList())
        val parsed = Iptc.parseDatasets(Iptc.buildDatasets(v))
        assertEquals("", parsed.title)
        assertEquals("傍晚的海边", parsed.description)
        assertTrue(parsed.keywords.isEmpty())
        assertEquals("bo", parsed.creator)
    }

    @Test
    fun jpeg_writeExtract_roundTrip() {
        val out = Iptc.writeJpeg(minimalJpeg(), Iptc.buildDatasets(values))
        val parsed = Iptc.parseDatasets(Iptc.extractFromStream(out.inputStream())!!)
        assertEquals("日落", parsed.title)
        assertEquals(listOf("风景", "旅行"), parsed.keywords)
        assertEquals("手机相册", parsed.source)
        // The JPEG is still a JPEG: SOI first, SOS still present.
        assertEquals(0xFF.toByte(), out[0])
        assertEquals(0xD8.toByte(), out[1])
        assertTrue(contains(out, byteArrayOf(0xFF.toByte(), 0xDA.toByte())))
    }

    @Test
    fun jpeg_writeTwice_replacesNotDuplicates() {
        val once = Iptc.writeJpeg(minimalJpeg(), Iptc.buildDatasets(values))
        val twice = Iptc.writeJpeg(once, Iptc.buildDatasets(values.copy(title = "第二标题")))
        val parsed = Iptc.parseDatasets(Iptc.extractFromStream(twice.inputStream())!!)
        assertEquals("第二标题", parsed.title)
        // Only one IPTC block: the title bytes of the first write are gone.
        assertFalse(contains(twice, "日落".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun jpeg_writeBlank_removesIptcBlock() {
        val withIptc = Iptc.writeJpeg(minimalJpeg(), Iptc.buildDatasets(values))
        assertTrue(Iptc.extractFromStream(withIptc.inputStream()) != null)
        val cleared = Iptc.writeJpeg(withIptc, Iptc.buildDatasets(values.copy("", "", emptyList(), "", "", "")))
        assertNull(Iptc.extractFromStream(cleared.inputStream()))
    }

    @Test
    fun jpeg_preservesOther8BimBlocks() {
        // APP13 with a dummy 8BIM block (id 0x0406) plus IPTC.
        val dummy = ByteArrayOutputStream().apply {
            write("8BIM".toByteArray(Charsets.US_ASCII))
            write(0x04); write(0x06)
            write(0); write(0) // empty name, padded
            write(0); write(0); write(0); write(4)
            write("DUMM".toByteArray(Charsets.US_ASCII))
        }.toByteArray()
        val app13 = ByteArrayOutputStream().apply {
            write(0xFF); write(0xED)
            val payload = ByteArrayOutputStream().apply {
                write("Photoshop 3.0\u0000".toByteArray(Charsets.US_ASCII))
                write(dummy)
            }.toByteArray()
            write(((payload.size + 2) ushr 8) and 0xFF)
            write((payload.size + 2) and 0xFF)
            write(payload)
        }.toByteArray()
        val jpeg = ByteArrayOutputStream().apply {
            write(minimalJpeg(), 0, 2)
            write(app13)
            write(minimalJpeg(), 2, minimalJpeg().size - 2)
        }.toByteArray()
        val out = Iptc.writeJpeg(jpeg, Iptc.buildDatasets(values))
        assertTrue(contains(out, "DUMM".toByteArray(Charsets.US_ASCII)))
        assertEquals("日落", Iptc.parseDatasets(Iptc.extractFromStream(out.inputStream())!!).title)
    }

    @Test
    fun extract_noApp13_returnsNull() {
        assertNull(Iptc.extractFromStream(minimalJpeg().inputStream()))
    }

    @Test
    fun parseDatasets_extendedLength() {
        // 1C 02 05, extended length: 0x8001 (1 length byte), length 5, "hello".
        val bytes = byteArrayOf(
            0x1C, 0x02, 0x05, 0x80.toByte(), 0x01, 0x05,
            'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte()
        )
        assertEquals("hello", Iptc.parseDatasets(bytes).title)
    }

    @Test
    fun parseDatasets_latin1Fallback() {
        // 0xE9 alone is not valid UTF-8: falls back to ISO-8859-1 ("é").
        val bytes = byteArrayOf(0x1C, 0x02, 0x05, 0x00, 0x01, 0xE9.toByte())
        assertEquals("é", Iptc.parseDatasets(bytes).title)
    }

    @Test
    fun parseDatasets_ignoresNonRecord2() {
        // Record 1, dataset 5 must not become the title.
        val bytes = byteArrayOf(
            0x1C, 0x01, 0x05, 0x00, 0x03, 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()
        )
        assertEquals("", Iptc.parseDatasets(bytes).title)
    }
}
