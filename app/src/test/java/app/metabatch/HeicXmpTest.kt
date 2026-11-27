package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * HeicXmp against synthetic HEIC files built box by box — no real
 * iPhone photo needed. Layout: ftyp(heic) + meta(hdlr, pitm, iinf,
 * iloc, iprp/ipco/ispe) + mdat(Exif item + XMP item bytes).
 */
class HeicXmpTest {

    private fun be16(v: Int): ByteArray = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
    private fun be32(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(), ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )
    private fun le16(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    private fun le32(v: Long): ByteArray = ByteArray(4) { i -> ((v ushr (8 * i)) and 0xFF).toByte() }

    private fun u32(bytes: ByteArray, pos: Int): Int =
        ((bytes[pos].toInt() and 0xFF) shl 24) or
            ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
            ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
            (bytes[pos + 3].toInt() and 0xFF)

    private fun ascii(bytes: ByteArray, start: Int, len: Int): String =
        String(bytes, start, len, Charsets.US_ASCII)

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(be32(8 + payload.size))
            write(type.toByteArray(Charsets.US_ASCII))
            write(payload)
        }.toByteArray()

    private fun fullBox(type: String, payload: ByteArray): ByteArray =
        box(type, concat(be32(0), payload)) // version 0, flags 0

    private fun concat(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { for (p in parts) write(p) }.toByteArray()

    private fun cstr(s: String): ByteArray =
        s.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    /** infe v2 entry: id, type, content type (empty strings allowed). */
    private fun infe(id: Int, type: String, contentType: String): ByteArray {
        val payload = ByteArrayOutputStream().apply {
            write(2); write(0); write(0); write(0) // version 2, flags 0
            write(be16(id))
            write(be16(0)) // item_protection_index
            write(type.toByteArray(Charsets.US_ASCII))
            write(cstr(""))
            write(cstr(contentType))
            write(cstr(""))
        }.toByteArray()
        return box("infe", payload)
    }

    /** Minimal TIFF (little-endian) with the tags HeicXmp.parseTiff reads. */
    private fun tiff(): ByteArray {
        // Entries: (tag, type, count, value bytes). Values > 4 bytes go
        // after the IFD; offsets are patched in a second pass.
        data class Entry(val tag: Int, val type: Int, val count: Int, val value: ByteArray)
        fun ascii(tag: Int, s: String): Entry {
            val v = s.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
            return Entry(tag, 2, v.size, v)
        }
        val entries = listOf(
            ascii(0x010E, "Test photo"),
            ascii(0x010F, "Apple"),
            ascii(0x0110, "iPhone 15"),
            Entry(0x0112, 3, 1, le16(6)),
            ascii(0x0131, "17.0"),
            ascii(0x0132, "2026:11:27 07:00:00"),
            ascii(0x013B, "bo"),
            // Copyright with a Latin-1 © byte (0xA9), not UTF-8.
            // Value is 10 bytes: 0xA9 + " 2026 bo" (8) + NUL.
            Entry(0x8298, 2, 10, byteArrayOf(0xA9.toByte()) + " 2026 bo".toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
        )
        val fixed = entries
        val ifdSize = 2 + fixed.size * 12 + 4
        var valueArea = 8 + ifdSize
        val ifd = ByteArrayOutputStream().apply {
            write(le16(fixed.size))
            for ((tag, type, count, value) in fixed) {
                write(le16(tag))
                write(le16(type))
                write(le32(count.toLong()))
                if (value.size <= 4) {
                    write(value)
                    repeat(4 - value.size) { write(0) }
                } else {
                    write(le32(valueArea.toLong()))
                    valueArea += value.size
                }
            }
            write(le32(0)) // next IFD offset
        }.toByteArray()
        val out = ByteArrayOutputStream().apply {
            write("II".toByteArray(Charsets.US_ASCII))
            write(le16(42))
            write(le32(8))
            write(ifd)
            for ((_, _, _, value) in fixed) {
                if (value.size > 4) write(value)
            }
        }.toByteArray()
        return out
    }

    private fun ftyp(): ByteArray = box(
        "ftyp",
        concat(
            "heic".toByteArray(Charsets.US_ASCII), be32(0),
            "heic".toByteArray(Charsets.US_ASCII),
            "mif1".toByteArray(Charsets.US_ASCII)
        )
    )

    private fun ispe(width: Int, height: Int): ByteArray =
        fullBox("ispe", concat(be32(width), be32(height)))

    /** Builds a synthetic HEIC. Two-pass so iloc holds real mdat offsets. */
    private fun synthetic(withXmp: Boolean = true, withExif: Boolean = true): ByteArray {
        val xmpPacket = "<x:xmpmeta>test packet</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val exifBytes = concat("Exif\u0000\u0000".toByteArray(Charsets.US_ASCII), tiff())
        // Item layout inside mdat: [Exif][XMP] (in that order).
        val mdatPayload = ByteArrayOutputStream().apply {
            if (withExif) write(exifBytes)
            if (withXmp) write(xmpPacket)
        }.toByteArray()
        fun build(exifOff: Int, xmpOff: Int): ByteArray {
            val entries = mutableListOf<Triple<Int, String, String>>()
            entries += Triple(1, "hvc1", "")
            if (withExif) entries += Triple(2, "Exif", "")
            if (withXmp) entries += Triple(3, "mime", "application/rdf+xml")
            val iinfPayload = ByteArrayOutputStream().apply {
                write(be16(entries.size))
                for ((id, type, ct) in entries) write(infe(id, type, ct))
            }.toByteArray()
            val iinf = fullBox("iinf", iinfPayload)
            // iloc v0: offset_size 4, length_size 4, base_offset_size 0.
            val ilocPayload = ByteArrayOutputStream().apply {
                write(0x44); write(0x00)
                write(be16(entries.size))
                for ((id, _, _) in entries) {
                    write(be16(id))
                    write(be16(0)) // data_reference_index
                    write(be16(1)) // extent_count
                    val (off, len) = when (id) {
                        2 -> exifOff to exifBytes.size
                        3 -> xmpOff to xmpPacket.size
                        else -> 0 to 0
                    }
                    write(be32(off))
                    write(be32(len))
                }
            }.toByteArray()
            val iloc = fullBox("iloc", ilocPayload)
            val hdlr = fullBox(
                "hdlr",
                concat(be32(0), "pict".toByteArray(Charsets.US_ASCII), ByteArray(12), cstr("handler"))
            )
            val pitm = fullBox("pitm", be16(1))
            val ipco = box("ipco", ispe(4032, 3024))
            val iprp = box("iprp", ipco)
            val metaPayload = concat(be32(0), hdlr, pitm, iinf, iloc, iprp)
            val meta = box("meta", metaPayload)
            return concat(ftyp(), meta, box("mdat", mdatPayload))
        }
        val probe = build(0, 0)
        // mdat content starts after its 8-byte header.
        val mdatStart = probe.size - mdatPayload.size - 8
        val mdatContent = mdatStart + 8
        val exifOff = mdatContent
        val xmpOff = mdatContent + if (withExif) exifBytes.size else 0
        return build(exifOff, xmpOff)
    }

    private fun findBox(bytes: ByteArray, want: String): Pair<Int, Int>? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val size = u32(bytes, pos)
            val type = ascii(bytes, pos + 4, 4)
            if (type == want) return (pos + 8) to size
            if (size <= 0) return null
            pos += size
        }
        return null
    }

    private val packet = "<x:xmpmeta>test packet</x:xmpmeta>".toByteArray(Charsets.UTF_8)

    @Test
    fun sniff() {
        assertTrue(HeicXmp.isHeic(synthetic()))
        assertFalse(HeicXmp.isHeic(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)))
        assertFalse(HeicXmp.isHeic(ByteArray(0)))
    }

    @Test
    fun readInfo_parsesDimensionsExifAndXmp() {
        val info = HeicXmp.readInfo(synthetic())
        assertEquals(4032, info.width)
        assertEquals(3024, info.height)
        assertEquals("Test photo", info.exif.description)
        assertEquals("Apple", info.exif.make)
        assertEquals("iPhone 15", info.exif.model)
        assertEquals("17.0", info.exif.software)
        assertEquals("2026:11:27 07:00:00", info.exif.dateTime)
        assertEquals("bo", info.exif.artist)
        assertEquals("© 2026 bo", info.exif.copyright)
        assertEquals(6, info.exif.orientation)
        assertEquals("<x:xmpmeta>test packet</x:xmpmeta>", info.xmp)
    }

    @Test
    fun extract_noXmp_returnsNull() {
        assertNull(HeicXmp.extractXmp(synthetic(withXmp = false)))
    }

    @Test
    fun write_firstXmp_roundTrip_exifStillReadable() {
        val original = synthetic(withXmp = false)
        val out = HeicXmp.writeXmp(original, packet)
        assertTrue(HeicXmp.extractXmp(out)!!.contentEquals(packet))
        // Meta grew, so iloc offsets were patched: Exif + dimensions survive.
        val info = HeicXmp.readInfo(out)
        assertEquals("Apple", info.exif.make)
        assertEquals("iPhone 15", info.exif.model)
        assertEquals(4032, info.width)
        assertEquals(3024, info.height)
        assertTrue(HeicXmp.isHeic(out))
    }

    @Test
    fun write_replaceExistingSmaller_inPlace_sizeUnchanged() {
        val original = synthetic(withXmp = true)
        val smaller = "<x:xmpmeta>s</x:xmpmeta>".toByteArray(Charsets.UTF_8)
        val out = HeicXmp.writeXmp(original, smaller)
        assertEquals(original.size, out.size)
        assertTrue(HeicXmp.extractXmp(out)!!.contentEquals(smaller))
        assertEquals("Apple", HeicXmp.readInfo(out).exif.make)
    }

    @Test
    fun write_replaceExistingBigger_appends_exifStillReadable() {
        val original = synthetic(withXmp = true)
        val bigger = ("<x:xmpmeta>" + "x".repeat(200) + "</x:xmpmeta>").toByteArray(Charsets.UTF_8)
        val out = HeicXmp.writeXmp(original, bigger)
        assertTrue(HeicXmp.extractXmp(out)!!.contentEquals(bigger))
        assertTrue(out.size > original.size)
        val info = HeicXmp.readInfo(out)
        assertEquals("Apple", info.exif.make)
        assertEquals(4032, info.width)
    }

    @Test
    fun write_noMeta_throws() {
        val file = concat(ftyp(), box("mdat", ByteArray(8)))
        try {
            HeicXmp.writeXmp(file, packet)
            throw AssertionError("expected IOException for missing meta")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("meta"))
        }
    }

    @Test
    fun write_noIloc_throws() {
        // meta with iinf but no iloc: parseMeta fails, same refusal.
        val meta = box("meta", concat(be32(0), fullBox("iinf", be16(0))))
        val file = concat(ftyp(), meta, box("mdat", ByteArray(8)))
        try {
            HeicXmp.writeXmp(file, packet)
            throw AssertionError("expected IOException for missing iloc")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("meta"))
        }
    }

    @Test
    fun parseTiff_bigEndian() {
        // MM header, one ASCII tag (Make = "Nikon"), one SHORT (Orientation = 1).
        val valueOff = 8 + 2 + 2 * 12 + 4
        val tiff = ByteArrayOutputStream().apply {
            write("MM".toByteArray(Charsets.US_ASCII))
            write(be16(42))
            write(be32(8))
            write(be16(2))
            // Make, ASCII, count 6, offset -> valueArea
            write(be16(0x010F)); write(be16(2)); write(be32(6)); write(be32(valueOff))
            // Orientation, SHORT, count 1, value inline (first 2 bytes)
            write(be16(0x0112)); write(be16(3)); write(be32(1)); write(be16(1)); write(be16(0))
            write(be32(0))
            write("Nikon".toByteArray(Charsets.US_ASCII)); write(0)
        }.toByteArray()
        val f = HeicXmp.parseTiff(tiff)
        assertEquals("Nikon", f.make)
        assertEquals(1, f.orientation)
    }

    @Test
    fun parseTiff_garbage_returnsEmpty() {
        val f = HeicXmp.parseTiff("not a tiff".toByteArray(Charsets.US_ASCII))
        assertEquals("", f.make)
        assertEquals(0, f.orientation)
    }
}
