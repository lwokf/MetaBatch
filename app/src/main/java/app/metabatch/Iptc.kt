package app.metabatch

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * IPTC-IIM (record 2) inside a JPEG APP13 "Photoshop 3.0" segment —
 * pure Kotlin, no new dependencies, fully unit-tested on the JVM.
 *
 * Layout: APP13 segment = "Photoshop 3.0\0" + 8BIM resource blocks.
 * The IPTC block is resource 0x0404; its data is a run of datasets:
 * 0x1C, record number (2), dataset number, length (2 bytes big-endian,
 * or the extended form), value bytes.
 *
 * Field mapping (record 2):
 * - 2:05 Object Name ......... title
 * - 2:120 Caption/Abstract ... description
 * - 2:25 Keywords ............ one dataset per keyword (repeatable)
 * - 2:80 By-line ............. creator
 * - 2:116 Copyright Notice ... rights
 * - 2:115 Source ............. source
 * - 2:00 Record Version ...... always written as 2
 * - 2:90 Coded Character Set . ESC % G (UTF-8) — written when any value
 *   is non-ASCII, so readers decode the Chinese correctly.
 *
 * There is no IPTC dataset for software: IPTC-IIM has no software
 * field, so software is NOT written to IPTC (it still goes to XMP).
 * PNG and WebP have no standard IPTC container, so dual-write is
 * JPEG-only.
 *
 * Blank-clears rule, same as XMP: a blank field's dataset is omitted,
 * so writing with all fields blank removes the whole IPTC block.
 * Other 8BIM blocks in the APP13 segment (thumbnails, etc.) are
 * preserved untouched.
 */
object Iptc {

    /** The six IPTC-carried fields (no software — see above). */
    data class IptcFields(
        val title: String = "",
        val description: String = "",
        val creator: String = "",
        val rights: String = "",
        val keywords: List<String> = emptyList(),
        val source: String = ""
    )

    private val PHOTOSHOP_HEADER = "Photoshop 3.0\u0000".toByteArray(Charsets.US_ASCII)
    private const val IPTC_RESOURCE_ID = 0x0404

    // ---- building / parsing datasets ----

    /** Datasets for [values]; empty when every field is blank. */
    fun buildDatasets(values: MetadataValues): ByteArray {
        val out = ByteArrayOutputStream()
        fun dataset(number: Int, value: String) {
            val data = value.toByteArray(Charsets.UTF_8)
            require(data.size < 32768) { "IPTC value too long." }
            out.write(0x1C)
            out.write(2)
            out.write(number)
            out.write((data.size ushr 8) and 0xFF)
            out.write(data.size and 0xFF)
            out.write(data)
        }
        val anyNonAscii = listOf(values.title, values.description, values.creator, values.copyright, values.source)
            .any { it.any { c -> c.code >= 128 } } ||
            values.keywords.any { kw -> kw.any { c -> c.code >= 128 } }
        // Record version first.
        out.write(0x1C); out.write(2); out.write(0)
        out.write(0); out.write(2); out.write(0); out.write(2)
        if (anyNonAscii) {
            // Coded Character Set: ESC % G = UTF-8.
            dataset(90, "\u001B%G")
        }
        if (values.title.isNotBlank()) dataset(5, values.title.trim())
        if (values.description.isNotBlank()) dataset(120, values.description.trim())
        for (kw in values.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            dataset(25, kw)
        }
        if (values.creator.isNotBlank()) dataset(80, values.creator.trim())
        if (values.copyright.isNotBlank()) dataset(116, values.copyright.trim())
        if (values.source.isNotBlank()) dataset(115, values.source.trim())
        val hasField = values.title.isNotBlank() || values.description.isNotBlank() ||
            values.keywords.any { it.isNotBlank() } || values.creator.isNotBlank() ||
            values.copyright.isNotBlank() || values.source.isNotBlank()
        return if (hasField) out.toByteArray() else ByteArray(0)
    }

    /** Parses raw IPTC dataset bytes into fields. Visible for testing. */
    fun parseDatasets(bytes: ByteArray): IptcFields {
        var title = ""
        var description = ""
        var creator = ""
        var rights = ""
        var source = ""
        val keywords = mutableListOf<String>()
        var pos = 0
        while (pos + 5 <= bytes.size) {
            if (bytes[pos].toInt() and 0xFF != 0x1C) {
                pos++
                continue
            }
            val record = bytes[pos + 1].toInt() and 0xFF
            val number = bytes[pos + 2].toInt() and 0xFF
            var len = ((bytes[pos + 3].toInt() and 0xFF) shl 8) or (bytes[pos + 4].toInt() and 0xFF)
            var headerSize = 5
            if (len and 0x8000 != 0) {
                // Extended dataset length: low 15 bits = byte count of the length.
                val lenLen = len and 0x7FFF
                if (lenLen > 4 || pos + 5 + lenLen > bytes.size) break
                len = 0
                for (i in 0 until lenLen) len = (len shl 8) or (bytes[pos + 5 + i].toInt() and 0xFF)
                headerSize = 5 + lenLen
            }
            if (pos + headerSize + len > bytes.size) break
            val raw = bytes.copyOfRange(pos + headerSize, pos + headerSize + len)
            val value = decodeValue(raw)
            if (record == 2) {
                when (number) {
                    5 -> title = value
                    120 -> description = value
                    25 -> if (value.isNotEmpty()) keywords += value
                    80 -> creator = value
                    116 -> rights = value
                    115 -> source = value
                }
            }
            pos += headerSize + len
        }
        return IptcFields(title, description, creator, rights, keywords.distinct(), source)
    }

    /** UTF-8 when it fits, ISO-8859-1 otherwise (old Latin-1 files). */
    private fun decodeValue(raw: ByteArray): String {
        return runCatching { String(raw, Charsets.UTF_8).also { require(!it.contains('\uFFFD')) } }
            .getOrElse { String(raw, Charsets.ISO_8859_1) }
    }

    // ---- reading from a JPEG stream ----

    /** IPTC dataset bytes from a JPEG stream, or null when there is no IPTC block. */
    fun extractFromStream(stream: InputStream): ByteArray? {
        val s = stream
        if (s.read() != 0xFF || s.read() != 0xD8) return null // not a JPEG
        while (true) {
            var b = s.read()
            while (b == 0xFF) b = s.read() // skip padding
            if (b == -1) return null
            val marker = b
            if (marker == 0xDA) return null // SOS: APP13 must come before it
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) continue
            val lenBytes = ByteArray(2)
            if (readFully(s, lenBytes) < 2) return null
            val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
            if (len < 2) return null
            val dataLen = len - 2
            if (marker == 0xED) { // APP13
                val data = ByteArray(dataLen)
                if (readFully(s, data) < dataLen) return null
                return parseApp13(data)
            }
            if (!skipFully(s, dataLen.toLong())) return null
        }
    }

    /** Returns the 0x0404 block data of an APP13 segment, or null. */
    fun parseApp13(data: ByteArray): ByteArray? {
        if (data.size < PHOTOSHOP_HEADER.size ||
            !data.copyOf(PHOTOSHOP_HEADER.size).contentEquals(PHOTOSHOP_HEADER)
        ) return null
        var pos = PHOTOSHOP_HEADER.size
        while (pos + 8 <= data.size) {
            if (String(data, pos, 4, Charsets.US_ASCII) != "8BIM") return null
            val id = ((data[pos + 4].toInt() and 0xFF) shl 8) or (data[pos + 5].toInt() and 0xFF)
            var p = pos + 6
            if (p >= data.size) return null
            val nameLen = data[p].toInt() and 0xFF
            p += 1 + nameLen
            if ((1 + nameLen) % 2 == 1) p++ // Pascal string padded to even
            if (p + 4 > data.size) return null
            val size = ((data[p].toInt() and 0xFF) shl 24) or
                ((data[p + 1].toInt() and 0xFF) shl 16) or
                ((data[p + 2].toInt() and 0xFF) shl 8) or
                (data[p + 3].toInt() and 0xFF)
            p += 4
            if (size < 0 || p + size > data.size) return null
            if (id == IPTC_RESOURCE_ID) return data.copyOfRange(p, p + size)
            p += size + if (size % 2 == 1) 1 else 0
            pos = p
        }
        return null
    }

    // ---- writing into JPEG bytes ----

    /**
     * Returns new JPEG bytes with the IPTC block replaced by [datasets]
     * (empty datasets remove the block). Other 8BIM blocks survive.
     * Throws when the file is not a JPEG or the segment would overflow.
     */
    @Throws(IOException::class)
    fun writeJpeg(bytes: ByteArray, datasets: ByteArray): ByteArray {
        if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
            throw IOException("Not a JPEG file.")
        }
        val newApp13: ByteArray? = rebuildApp13(bytes, datasets)
        val out = ByteArrayOutputStream(bytes.size + (newApp13?.size ?: 0))
        out.write(bytes, 0, 2) // SOI
        var pos = 2
        var done = false
        while (pos < bytes.size - 1) {
            if (bytes[pos] != 0xFF.toByte()) break
            var markerPos = pos + 1
            while (markerPos < bytes.size && bytes[markerPos] == 0xFF.toByte()) markerPos++
            if (markerPos >= bytes.size) break
            val marker = bytes[markerPos].toInt() and 0xFF
            if (marker == 0xDA) { // SOS
                if (!done && newApp13 != null) out.write(newApp13)
                out.write(bytes, pos, bytes.size - pos)
                return out.toByteArray()
            }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                out.write(bytes, pos, markerPos + 1 - pos)
                pos = markerPos + 1
                continue
            }
            if (markerPos + 2 >= bytes.size) break
            val len = (((bytes[markerPos + 1].toInt() and 0xFF) shl 8) or
                (bytes[markerPos + 2].toInt() and 0xFF))
            if (len < 2 || markerPos + 1 + len > bytes.size) break
            val dataStart = markerPos + 3
            val dataLen = len - 2
            if (marker == 0xED && dataLen >= PHOTOSHOP_HEADER.size &&
                bytes.copyOfRange(dataStart, dataStart + PHOTOSHOP_HEADER.size)
                    .contentEquals(PHOTOSHOP_HEADER)
            ) {
                // Existing Photoshop APP13: replace it with the rebuilt one
                // (or drop it when the rebuild is null).
                if (newApp13 != null) out.write(newApp13)
                done = true
            } else {
                out.write(bytes, pos, markerPos + 1 + len - pos)
            }
            pos = markerPos + 1 + len
        }
        if (!done && newApp13 != null) out.write(newApp13)
        out.write(bytes, pos, bytes.size - pos)
        return out.toByteArray()
    }

    /**
     * Rebuilds the APP13 segment for [bytes]: existing 8BIM blocks minus
     * the old IPTC block, plus the new IPTC block when [datasets] is
     * non-empty. Returns null when nothing should remain (no blocks and
     * no new datasets). Throws when the segment would exceed 64 KiB.
     */
    private fun rebuildApp13(bytes: ByteArray, datasets: ByteArray): ByteArray? {
        val old = findApp13(bytes)
        val kept = ByteArrayOutputStream()
        var keptAny = false
        if (old != null) {
            var pos = PHOTOSHOP_HEADER.size
            while (pos + 8 <= old.size) {
                if (String(old, pos, 4, Charsets.US_ASCII) != "8BIM") break
                val id = ((old[pos + 4].toInt() and 0xFF) shl 8) or (old[pos + 5].toInt() and 0xFF)
                var p = pos + 6
                if (p >= old.size) break
                val nameLen = old[p].toInt() and 0xFF
                p += 1 + nameLen
                if ((1 + nameLen) % 2 == 1) p++
                if (p + 4 > old.size) break
                val size = ((old[p].toInt() and 0xFF) shl 24) or
                    ((old[p + 1].toInt() and 0xFF) shl 16) or
                    ((old[p + 2].toInt() and 0xFF) shl 8) or
                    (old[p + 3].toInt() and 0xFF)
                p += 4
                if (size < 0 || p + size > old.size) break
                val total = (p + size + if (size % 2 == 1) 1 else 0) - pos
                if (id != IPTC_RESOURCE_ID) {
                    kept.write(old, pos, total)
                    keptAny = true
                }
                pos += total
            }
        }
        if (!keptAny && datasets.isEmpty()) return null
        val payload = ByteArrayOutputStream().apply {
            write(PHOTOSHOP_HEADER)
            write(kept.toByteArray())
            if (datasets.isNotEmpty()) write(resourceBlock(IPTC_RESOURCE_ID, datasets))
        }.toByteArray()
        if (payload.size + 2 > 65535) throw IOException("IPTC data too large for a JPEG APP13 segment.")
        return ByteArrayOutputStream().apply {
            write(0xFF); write(0xED)
            write((payload.size + 2) ushr 8 and 0xFF)
            write((payload.size + 2) and 0xFF)
            write(payload)
        }.toByteArray()
    }

    /** Finds the Photoshop APP13 segment payload of a JPEG, or null. */
    private fun findApp13(bytes: ByteArray): ByteArray? {
        var pos = 2
        while (pos < bytes.size - 1) {
            if (bytes[pos] != 0xFF.toByte()) break
            var markerPos = pos + 1
            while (markerPos < bytes.size && bytes[markerPos] == 0xFF.toByte()) markerPos++
            if (markerPos >= bytes.size) break
            val marker = bytes[markerPos].toInt() and 0xFF
            if (marker == 0xDA) return null
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                pos = markerPos + 1
                continue
            }
            if (markerPos + 2 >= bytes.size) break
            val len = (((bytes[markerPos + 1].toInt() and 0xFF) shl 8) or
                (bytes[markerPos + 2].toInt() and 0xFF))
            if (len < 2 || markerPos + 1 + len > bytes.size) break
            val dataStart = markerPos + 3
            val dataLen = len - 2
            if (marker == 0xED && dataLen >= PHOTOSHOP_HEADER.size &&
                bytes.copyOfRange(dataStart, dataStart + PHOTOSHOP_HEADER.size)
                    .contentEquals(PHOTOSHOP_HEADER)
            ) {
                return bytes.copyOfRange(dataStart, dataStart + dataLen)
            }
            pos = markerPos + 1 + len
        }
        return null
    }

    /** One 8BIM resource block: "8BIM" + id + empty Pascal name + size + data (even-padded). */
    private fun resourceBlock(id: Int, data: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write("8BIM".toByteArray(Charsets.US_ASCII))
            write((id ushr 8) and 0xFF)
            write(id and 0xFF)
            write(0) // empty name: length 0...
            write(0) // ...padded to even
            write((data.size ushr 24) and 0xFF)
            write((data.size ushr 16) and 0xFF)
            write((data.size ushr 8) and 0xFF)
            write(data.size and 0xFF)
            write(data)
            if (data.size % 2 == 1) write(0)
        }.toByteArray()

    private fun readFully(s: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = s.read(buf, off, buf.size - off)
            if (n == -1) break
            off += n
        }
        return off
    }

    private fun skipFully(s: InputStream, n: Long): Boolean {
        var remaining = n
        while (remaining > 0) {
            val skipped = s.skip(remaining)
            if (skipped <= 0) {
                if (s.read() == -1) return false
                remaining--
            } else {
                remaining -= skipped
            }
        }
        return true
    }
}
