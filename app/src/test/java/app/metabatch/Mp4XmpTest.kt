package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Mp4Xmp against synthetic MP4 files built box by box — no real video
 * needed. Layouts: moov-first (faststart) and moov-last (phone
 * recordings), with a trak/stco pointing into mdat.
 */
class Mp4XmpTest {

    private fun intBytes(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun longBytes(v: Long): ByteArray = ByteArray(8) { i ->
        ((v ushr (56 - 8 * i)) and 0xFF).toByte()
    }

    private fun u32(bytes: ByteArray, pos: Int): Int =
        ((bytes[pos].toInt() and 0xFF) shl 24) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
            (bytes[pos + 3].toInt() and 0xFF)

    private fun u64(bytes: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
        return v
    }

    private fun ascii(bytes: ByteArray, start: Int, len: Int): String =
        String(bytes, start, len, Charsets.US_ASCII)

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(intBytes(8 + payload.size))
            write(type.toByteArray(Charsets.US_ASCII))
            write(payload)
        }.toByteArray()

    private fun boxLarge(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(intBytes(1))
            write(type.toByteArray(Charsets.US_ASCII))
            write(longBytes((16 + payload.size).toLong()))
            write(payload)
        }.toByteArray()

    private fun concat(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { for (p in parts) write(p) }.toByteArray()

    private fun stco(entries: List<Int>): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(intBytes(0)) // version + flags
            write(intBytes(entries.size))
            for (e in entries) write(intBytes(e))
        }.toByteArray()
        return box("stco", payload)
    }

    private fun moov(entries: List<Int>, withUdta: Boolean = false, large: Boolean = false): ByteArray {
        val stbl = box("stbl", stco(entries))
        val minf = box("minf", stbl)
        val mdia = box("mdia", minf)
        val trak = box("trak", mdia)
        val mvhd = box("mvhd", ByteArray(100))
        val payload = if (withUdta) concat(mvhd, trak, box("udta", ByteArray(0))) else concat(mvhd, trak)
        return if (large) boxLarge("moov", payload) else box("moov", payload)
    }

    private fun ftyp(): ByteArray =
        box("ftyp", concat("isom".toByteArray(Charsets.US_ASCII), intBytes(0), "isom".toByteArray(Charsets.US_ASCII)))

    /** Two-pass build: first measure with zero entries, then fill real mdat offsets. */
    private fun synthetic(moovFirst: Boolean, withUdta: Boolean = false, largeMoov: Boolean = false): ByteArray {
        val mdatPayload = ByteArray(64) { 0x5A.toByte() }
        val mdat = box("mdat", mdatPayload)
        val probe = if (moovFirst) {
            concat(ftyp(), moov(emptyList(), withUdta, largeMoov), mdat)
        } else {
            concat(ftyp(), mdat, moov(emptyList(), withUdta, largeMoov))
        }
        val mdatStart = findBox(probe, "mdat")!!.first
        val entries = listOf(mdatStart + 8, mdatStart + 40)
        return if (moovFirst) {
            concat(ftyp(), moov(entries, withUdta, largeMoov), mdat)
        } else {
            concat(ftyp(), mdat, moov(entries, withUdta, largeMoov))
        }
    }

    /** Finds a top-level box: returns (contentStart, totalSize) or null. */
    private fun findBox(bytes: ByteArray, want: String): Pair<Int, Int>? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size = u32(bytes, pos)
            val type = ascii(bytes, pos + 4, 4)
            val (header, total) = if (size == 1) {
                16 to u64(bytes, pos + 8).toInt()
            } else {
                8 to size
            }
            if (type == want) return (pos + header) to total
            if (total <= 0) return null
            pos += total
        }
        return null
    }

    /** Test-side stco reader: walks moov/trak/mdia/minf/stbl. */
    private fun readStcoEntries(bytes: ByteArray): List<Long> {
        fun walk(start: Int, end: Int): List<Long>? {
            var pos = start
            while (pos + 8 <= end) {
                val size = u32(bytes, pos)
                val type = ascii(bytes, pos + 4, 4)
                val (header, total) = if (size == 1) 16 to u64(bytes, pos + 8).toInt() else 8 to size
                if (type == "stco") {
                    val count = u32(bytes, pos + header + 4)
                    return (0 until count).map {
                        u32(bytes, pos + header + 8 + it * 4).toLong() and 0xFFFFFFFFL
                    }
                }
                if (type in setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "meta")) {
                    val childStart = pos + header + if (type == "meta") 4 else 0
                    walk(childStart, pos + total)?.let { return it }
                }
                if (total <= 0) return null
                pos += total
            }
            return null
        }
        return walk(0, bytes.size) ?: emptyList()
    }

    private val packet = "<x:xmpmeta>test packet</x:xmpmeta>".toByteArray(Charsets.UTF_8)

    @Test
    fun extract_noXmp_returnsNull() {
        assertNull(Mp4Xmp.extractXmp(synthetic(moovFirst = true)))
        assertNull(Mp4Xmp.extractXmp(synthetic(moovFirst = false)))
    }

    @Test
    fun write_thenExtract_roundTrip_moovLast() {
        val out = Mp4Xmp.writeXmp(synthetic(moovFirst = false), packet)
        assertTrue(Mp4Xmp.extractXmp(out)!!.contentEquals(packet))
        assertTrue(Mp4Xmp.isMp4OrMov(out))
    }

    @Test
    fun write_thenExtract_roundTrip_moovFirst() {
        val out = Mp4Xmp.writeXmp(synthetic(moovFirst = true), packet)
        assertTrue(Mp4Xmp.extractXmp(out)!!.contentEquals(packet))
    }

    @Test
    fun write_replaceExisting_roundTrip() {
        val first = Mp4Xmp.writeXmp(synthetic(moovFirst = false), packet)
        val secondPacket = "<x:xmpmeta>second</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val second = Mp4Xmp.writeXmp(first, secondPacket)
        assertTrue(Mp4Xmp.extractXmp(second)!!.contentEquals(secondPacket))
    }

    @Test
    fun write_moovFirst_patchesStco() {
        val original = synthetic(moovFirst = true)
        val before = readStcoEntries(original)
        assertEquals(2, before.size)
        val out = Mp4Xmp.writeXmp(original, packet)
        val after = readStcoEntries(out)
        val delta = (out.size - original.size).toLong()
        assertTrue(delta > 0)
        assertEquals(before[0] + delta, after[0])
        assertEquals(before[1] + delta, after[1])
        // mdat payload itself is byte-identical, just shifted.
        val mdatBefore = findBox(original, "mdat")!!
        val mdatAfter = findBox(out, "mdat")!!
        val payloadBefore = original.copyOfRange(mdatBefore.first, mdatBefore.first + 64)
        val payloadAfter = out.copyOfRange(mdatAfter.first, mdatAfter.first + 64)
        assertTrue(payloadBefore.contentEquals(payloadAfter))
    }

    @Test
    fun write_moovLast_stcoUnchanged() {
        val original = synthetic(moovFirst = false)
        val before = readStcoEntries(original)
        val out = Mp4Xmp.writeXmp(original, packet)
        assertEquals(before, readStcoEntries(out))
    }

    @Test
    fun write_udtaExists_appendsUuid() {
        val out = Mp4Xmp.writeXmp(synthetic(moovFirst = false, withUdta = true), packet)
        assertTrue(Mp4Xmp.extractXmp(out)!!.contentEquals(packet))
    }

    @Test
    fun write_fragmented_throws() {
        val file = concat(ftyp(), box("moov", ByteArray(0)), box("moof", ByteArray(0)), box("mdat", ByteArray(8)))
        try {
            Mp4Xmp.writeXmp(file, packet)
            throw AssertionError("expected IOException for fragmented MP4")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Fragmented"))
        }
    }

    @Test
    fun write_noMoov_throws() {
        val file = concat(ftyp(), box("mdat", ByteArray(8)))
        try {
            Mp4Xmp.writeXmp(file, packet)
            throw AssertionError("expected IOException for missing moov")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("moov"))
        }
    }

    @Test
    fun write_largesizeMoov_roundTrip() {
        val original = synthetic(moovFirst = false, largeMoov = true)
        val out = Mp4Xmp.writeXmp(original, packet)
        assertTrue(Mp4Xmp.extractXmp(out)!!.contentEquals(packet))
        // The moov largesize field (offset 8) equals the new moov total.
        val (moovContent, _) = findBox(out, "moov")!!
        val moovStart = moovContent - 16
        assertEquals((out.size - moovStart).toLong(), u64(out, moovStart + 8))
    }

    @Test
    fun sniff() {
        assertTrue(Mp4Xmp.isMp4OrMov(synthetic(moovFirst = true)))
        assertFalse(Mp4Xmp.isMp4OrMov(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)))
        assertFalse(Mp4Xmp.isMp4OrMov(ByteArray(0)))
    }

    @Test
    fun extract_findsXmpUnderMetaToo() {
        // moov/meta/uuid: some encoders nest the XMP uuid under meta.
        val uuidBox = ByteArrayOutputStream().apply {
            write(intBytes(24 + packet.size))
            write("uuid".toByteArray(Charsets.US_ASCII))
            write(Mp4Xmp.XMP_USERTYPE)
            write(packet)
        }.toByteArray()
        val metaPayload = concat(intBytes(0), uuidBox)
        val meta = box("meta", metaPayload)
        val file = concat(ftyp(), box("moov", meta), box("mdat", ByteArray(8)))
        assertTrue(Mp4Xmp.extractXmp(file)!!.contentEquals(packet))
    }
}
