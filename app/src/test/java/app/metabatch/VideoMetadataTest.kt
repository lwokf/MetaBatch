package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * Video write path on the JVM: MetadataWriter.write on a synthetic MP4
 * file, verified by reading the XMP packet back. No Android APIs here —
 * MediaMetadataRetriever playback is device-only and not covered.
 */
class VideoMetadataTest {

    private fun intBytes(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(intBytes(8 + payload.size))
            write(type.toByteArray(Charsets.US_ASCII))
            write(payload)
        }.toByteArray()

    private fun concat(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { for (p in parts) write(p) }.toByteArray()

    /** Minimal MP4: ftyp + moov(mvhd) + mdat. No stco — chunk patching is covered in Mp4XmpTest. */
    private fun syntheticMp4(): ByteArray {
        val ftyp = box("ftyp", concat("isom".toByteArray(Charsets.US_ASCII), intBytes(0), "isom".toByteArray(Charsets.US_ASCII)))
        val moov = box("moov", box("mvhd", ByteArray(100)))
        val mdat = box("mdat", ByteArray(64) { 0x5A.toByte() })
        return concat(ftyp, mdat, moov)
    }

    private fun tempMp4(): File {
        val f = File.createTempFile("mb_video_", ".mp4")
        f.writeBytes(syntheticMp4())
        return f
    }

    private val fullValues = MetadataValues(
        title = "测试标题",
        description = "一段描述",
        keywords = listOf("风景", "旅行"),
        creator = "作者",
        copyright = "版权所有",
        source = "来源",
        software = "测试软件"
    )

    private fun readBack(file: File): XmpFields {
        val bytes = Mp4Xmp.extractXmp(file.readBytes())
            ?: throw AssertionError("no XMP packet after write")
        return MetadataReader.parseXmp(XmpPacketReader.decode(bytes))
    }

    @Test
    fun writer_roundTrip_chineseFields() {
        val file = tempMp4()
        try {
            MetadataWriter.write(file, fullValues)
            val back = readBack(file)
            assertEquals("测试标题", back.title)
            assertEquals("一段描述", back.description)
            assertEquals(listOf("风景", "旅行"), back.keywords)
            assertEquals("作者", back.creator)
            assertEquals("版权所有", back.rights)
            assertEquals("来源", back.source)
            assertEquals("测试软件", back.software)
        } finally {
            file.delete()
        }
    }

    @Test
    fun writer_blankClears() {
        val file = tempMp4()
        try {
            MetadataWriter.write(file, fullValues)
            assertEquals("测试标题", readBack(file).title)
            MetadataWriter.write(
                file,
                MetadataValues("", "", emptyList(), "", "", "")
            )
            val back = readBack(file)
            assertEquals("", back.title)
            assertEquals(emptyList<String>(), back.keywords)
            assertEquals("", back.source)
            assertEquals("", back.software)
        } finally {
            file.delete()
        }
    }

    @Test
    fun writer_fragmented_throws() {
        val ftyp = box("ftyp", ByteArray(12))
        val moov = box("moov", ByteArray(0))
        val moof = box("moof", ByteArray(0))
        val mdat = box("mdat", ByteArray(8))
        val file = File.createTempFile("mb_video_frag_", ".mp4")
        try {
            file.writeBytes(concat(ftyp, moov, moof, mdat))
            try {
                MetadataWriter.write(file, fullValues)
                throw AssertionError("expected IOException for fragmented MP4")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("Fragmented"))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun formatMp4Date_mapping() {
        assertEquals("2024:01:01 12:00:00", VideoMetadataReader.formatMp4Date("20240101T120000.000Z"))
        assertEquals("", VideoMetadataReader.formatMp4Date(null))
        assertEquals("", VideoMetadataReader.formatMp4Date(""))
        assertEquals("garbage", VideoMetadataReader.formatMp4Date("garbage"))
    }

    @Test
    fun parseIso6709_mapping() {
        assertEquals("37.7749, -122.4194", VideoMetadataReader.parseIso6709("+37.7749-122.4194/"))
        assertEquals("", VideoMetadataReader.parseIso6709(null))
        assertEquals("garbage", VideoMetadataReader.parseIso6709("garbage"))
    }
}
