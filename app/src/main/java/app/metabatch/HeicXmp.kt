package app.metabatch

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * HEIC / HEIF metadata (ISO BMFF, ISO/IEC 23008-12) — pure Kotlin, no new
 * dependencies, fully unit-tested on the JVM.
 *
 * A HEIC file is a box tree: `ftyp` (brands heic/mif1/...) then a `meta`
 * box holding the item index (`iinf` — one `infe` entry per item, with
 * item type + content type) and the item locations (`iloc` — absolute
 * file offsets + lengths per item). The photo's EXIF lives in an item
 * of type `Exif` (data starts with "Exif\0\0" + a TIFF header) and the
 * XMP packet in an item of type `mime` with content type
 * `application/rdf+xml`. Both items' bytes live in `mdat` (or wherever
 * `iloc` points).
 *
 * Read: XMP packet bytes (decoded by the caller with
 * [XmpPacketReader.decode]), EXIF TIFF fields, image dimensions from
 * `ispe` in `meta/iprp/ipco`.
 *
 * Write: XMP only — the seven MetaBatch fields already round-trip
 * through XMP (the reader prefers XMP), and rewriting the TIFF IFD
 * inside the Exif item would be a second, riskier surgery. EXIF tags
 * inside the Exif item are therefore NOT updated by a HEIC write;
 * other apps that read EXIF only will keep showing the old values.
 * The write never moves existing bytes except when a new item has to
 * be registered: then `meta` grows and every `iloc` extent offset is
 * patched by the same delta (the same trick [Mp4Xmp] uses for `stco`).
 * New packet data is always appended at end of file — either by
 * extending a trailing `mdat` box or by appending a `free` box — so
 * existing item bytes are never overwritten.
 *
 * Refused with a clear message instead of a silent corrupt write:
 * no `meta`/`iinf`/`iloc` box, item data stored in `idat`
 * (construction_method 1), or a packet that would overflow the
 * 32-bit box sizes we write.
 */
object HeicXmp {

    /** EXIF fields parsed out of the Exif item's TIFF header. */
    data class HeicExifFields(
        val description: String = "",
        val make: String = "",
        val model: String = "",
        val software: String = "",
        val dateTime: String = "",
        val artist: String = "",
        val copyright: String = "",
        /** EXIF orientation value (1 = normal); 0 when absent. */
        val orientation: Int = 0
    )

    /** Everything [readInfo] knows about one HEIC file. */
    data class HeicInfo(
        val width: Int,
        val height: Int,
        val exif: HeicExifFields,
        /** Decoded XMP packet string, or null when the file has no XMP item. */
        val xmp: String?
    )

    private val HEIC_BRANDS = setOf(
        "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
        "mif1", "mif2", "msf1"
    )
    private val XMP_CONTENT_TYPE = "application/rdf+xml"

    // ---- sniffing ----

    fun isHeic(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (ascii(bytes, 4, 4) != "ftyp") return false
        val major = ascii(bytes, 8, 4)
        if (major in HEIC_BRANDS) return true
        // Generic BMFF with a meta box: check the compatible brands.
        var pos = 16
        while (pos + 4 <= bytes.size && pos + 4 <= 16 + 64) {
            if (ascii(bytes, pos, 4) in HEIC_BRANDS) return true
            pos += 4
        }
        return false
    }

    // ---- reading ----

    /** XMP packet bytes, or null when the file has no XMP item. */
    fun extractXmp(bytes: ByteArray): ByteArray? {
        val meta = parseMeta(bytes) ?: return null
        if (meta.iloc.unsupportedIdat) return null
        val item = meta.items.firstOrNull { it.isXmp } ?: return null
        return readItemBytes(bytes, meta.iloc, item)
    }

    /** EXIF TIFF header bytes (the "Exif\0\0" prefix stripped), or null. */
    fun extractExif(bytes: ByteArray): ByteArray? {
        val meta = parseMeta(bytes) ?: return null
        if (meta.iloc.unsupportedIdat) return null
        val item = meta.items.firstOrNull { it.type == "Exif" } ?: return null
        val raw = readItemBytes(bytes, meta.iloc, item) ?: return null
        // Exif item data starts with the 6-byte "Exif\0\0" prefix.
        return if (raw.size >= 6 && raw[0] == 'E'.code.toByte() && raw[1] == 'x'.code.toByte() &&
            raw[2] == 'i'.code.toByte() && raw[3] == 'f'.code.toByte() &&
            raw[4] == 0.toByte() && raw[5] == 0.toByte()
        ) {
            raw.copyOfRange(6, raw.size)
        } else {
            raw
        }
    }

    /** Image dimensions from the first `ispe` box, or (0, 0). */
    fun extractDimensions(bytes: ByteArray): Pair<Int, Int> {
        val meta = parseMeta(bytes) ?: return 0 to 0
        var pos = meta.contentStart
        val end = meta.start + meta.totalSize.toInt()
        while (pos + 8 <= end) {
            val child = parseBox(bytes, pos, end) ?: break
            if (child.type == "iprp") {
                val dims = findIspe(bytes, pos + child.headerSize, pos + child.totalSize.toInt())
                if (dims != null) return dims
            }
            if (child.totalSize <= 0) break
            pos += child.totalSize.toInt()
        }
        return 0 to 0
    }

    private fun findIspe(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int>? {
        var pos = start
        while (pos + 8 <= end) {
            val child = parseBox(bytes, pos, end) ?: break
            if (child.type == "ipco") {
                var p2 = pos + child.headerSize
                val end2 = pos + child.totalSize.toInt()
                while (p2 + 8 <= end2) {
                    val prop = parseBox(bytes, p2, end2) ?: break
                    if (prop.type == "ispe" && prop.contentSize >= 12) {
                        val w = u32(bytes, p2 + prop.headerSize + 4)
                        val h = u32(bytes, p2 + prop.headerSize + 8)
                        return w to h
                    }
                    if (prop.totalSize <= 0) break
                    p2 += prop.totalSize.toInt()
                }
            }
            if (child.totalSize <= 0) break
            pos += child.totalSize.toInt()
        }
        return null
    }

    /** One read: dimensions + EXIF fields + XMP packet string. */
    fun readInfo(bytes: ByteArray): HeicInfo {
        val (w, h) = extractDimensions(bytes)
        val exif = extractExif(bytes)?.let { parseTiff(it) } ?: HeicExifFields()
        val xmp = extractXmp(bytes)?.let { XmpPacketReader.decode(it) }
        return HeicInfo(w, h, exif, xmp)
    }

    /**
     * Parses a TIFF header (IFD0) for the ASCII tags MetaBatch shows.
     * Visible for testing. Unknown tags and the GPS IFD are ignored.
     */
    fun parseTiff(tiff: ByteArray): HeicExifFields {
        if (tiff.size < 8) return HeicExifFields()
        val little = when {
            tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte() -> true
            tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte() -> false
            else -> return HeicExifFields()
        }
        fun u16(p: Int): Int {
            val b0 = tiff[p].toInt() and 0xFF
            val b1 = tiff[p + 1].toInt() and 0xFF
            return if (little) b0 or (b1 shl 8) else (b0 shl 8) or b1
        }
        fun u32(p: Int): Long {
            var v = 0L
            for (i in 0 until 4) {
                val b = tiff[p + i].toLong() and 0xFF
                v = if (little) v or (b shl (8 * i)) else (v shl 8) or b
            }
            return v
        }
        if (u16(2) != 42) return HeicExifFields()
        val ifd0 = u32(4).toInt()
        if (ifd0 < 8 || ifd0 + 2 > tiff.size) return HeicExifFields()
        val count = u16(ifd0)
        var description = ""
        var make = ""
        var model = ""
        var software = ""
        var dateTime = ""
        var artist = ""
        var copyright = ""
        var orientation = 0
        fun asciiValue(entryPos: Int, type: Int, cnt: Int): String {
            if (type != 2 || cnt <= 0) return ""
            val valuePos = entryPos + 8
            val byteLen = cnt
            val bytes = if (byteLen <= 4) {
                tiff.copyOfRange(valuePos, minOf(valuePos + byteLen, tiff.size))
            } else {
                val off = u32(valuePos).toInt()
                if (off < 0 || off + byteLen > tiff.size) return ""
                tiff.copyOfRange(off, off + byteLen)
            }
            return String(bytes, Charsets.ISO_8859_1).trim { it == '\u0000' || it == ' ' }
        }
        for (i in 0 until count) {
            val e = ifd0 + 2 + i * 12
            if (e + 12 > tiff.size) break
            val tag = u16(e)
            val type = u16(e + 2)
            val cnt = u32(e + 4).toInt()
            when (tag) {
                0x010E -> description = asciiValue(e, type, cnt)
                0x010F -> make = asciiValue(e, type, cnt)
                0x0110 -> model = asciiValue(e, type, cnt)
                0x0112 -> if (type == 3 && cnt >= 1) {
                    // SHORT values live inline when they fit in 4 bytes.
                    orientation = if (cnt * 2 <= 4) u16(e + 8) else u16(u32(e + 8).toInt())
                }
                0x0131 -> software = asciiValue(e, type, cnt)
                0x0132 -> dateTime = asciiValue(e, type, cnt)
                0x013B -> artist = asciiValue(e, type, cnt)
                0x8298 -> copyright = asciiValue(e, type, cnt)
            }
        }
        return HeicExifFields(description, make, model, software, dateTime, artist, copyright, orientation)
    }

    // ---- writing ----

    /**
     * Returns a new file with [packet] as the XMP item. Never moves
     * existing item bytes: a fitting packet overwrites in place, a
     * bigger one (or a first XMP item) is appended at end of file.
     */
    @Throws(IOException::class)
    fun writeXmp(bytes: ByteArray, packet: ByteArray): ByteArray {
        if (packet.size > Int.MAX_VALUE - 1024) throw IOException("XMP packet too large.")
        val meta = parseMeta(bytes) ?: throw IOException("No meta box found in this HEIC file.")
        if (meta.iloc.unsupportedIdat) {
            throw IOException("HEIC item data stored in idat (construction_method 1) is not supported.")
        }
        val existing = meta.items.firstOrNull { it.isXmp }
        if (existing != null) {
            val loc = meta.iloc.items[existing.id]
                ?: throw IOException("XMP item has no iloc entry.")
            if (loc.extents.size != 1) throw IOException("XMP item with multiple extents is not supported.")
            val ext = loc.extents[0]
            if (packet.size <= ext.length) {
                // Fits: overwrite in place, shrink the extent length.
                val out = bytes.copyOf()
                packet.copyInto(out, ext.offset)
                for (i in packet.size until ext.length) out[ext.offset + i] = ' '.code.toByte()
                writeExtentLength(out, meta.iloc, existing.id, packet.size)
                return out
            }
            // Too big: append at EOF, point the iloc entry at it.
            val (grown, dataOffset) = appendPacketData(bytes, packet)
            val out = grown.copyOf()
            writeExtent(out, meta.iloc, existing.id, dataOffset, packet.size)
            return out
        }
        // First XMP item: register it in iinf + iloc (meta grows),
        // patch every existing iloc offset by the growth, then append data.
        return insertFirstXmp(bytes, meta, packet)
    }

    @Throws(IOException::class)
    private fun insertFirstXmp(bytes: ByteArray, meta: Meta, packet: ByteArray): ByteArray {
        val iinf = meta.iinf ?: throw IOException("No iinf box found in this HEIC file.")
        val iloc = meta.iloc
        if (meta.headerSize != 8 || iinf.headerSize != 8 || iloc.headerSize != 8) {
            throw IOException("HEIC meta/iinf/iloc boxes with 64-bit sizes are not supported.")
        }
        val newId = (meta.items.maxOfOrNull { it.id } ?: 0) + 1
        if (newId > 0xFFFF) throw IOException("Too many items in this HEIC file.")
        val infe = buildInfe(newId)
        val (grownMeta, delta, newEntryRel) = growMetaForNewItem(bytes, meta, iinf, iloc, infe)
        // After the meta growth, EOF moved by delta; append the packet data.
        val base = bytes.copyOfRange(0, meta.start) + grownMeta +
            bytes.copyOfRange(meta.start + meta.totalSize.toInt(), bytes.size)
        val (withData, dataOffset) = appendPacketData(base, packet)
        val out = withData.copyOf()
        // Patch every pre-existing iloc extent offset by delta.
        patchIlocOffsets(out, meta.start, delta)
        // Write the new iloc entry (inserted with offset 0) at its final
        // position in the grown meta; patchIlocOffsets already moved the
        // placeholder by delta, but this overwrites offset/length anyway.
        writeNewIlocEntry(out, iloc, meta.start + newEntryRel, newId, dataOffset, packet.size)
        return out
    }

    /**
     * Builds the grown meta box: the new `infe` entry appended to `iinf`
     * (count + 1) and a placeholder `iloc` entry appended to `iloc`
     * (count + 1, offset/length 0 — filled in by the caller once the
     * data offset is known). Returns the new meta bytes, the growth,
     * and the new iloc entry's position relative to the meta start.
     * Insertions are applied high-offset-first so positions stay valid;
     * the entry's final position accounts for the infe insertion when
     * it lands before it.
     */
    private fun growMetaForNewItem(
        bytes: ByteArray, meta: Meta, iinf: Box, iloc: Iloc, infe: ByteArray
    ): Triple<ByteArray, Int, Int> {
        val metaBytes = bytes.copyOfRange(meta.start, meta.start + meta.totalSize.toInt())
        val ilocEntrySize = ilocEntrySize(iloc)
        // iloc insertion point: end of iloc content, relative to meta start.
        val ilocInsertAt = (iloc.start - meta.start) + iloc.totalSize.toInt()
        val ilocEntry = ByteArray(ilocEntrySize) // zeros; caller fills it in
        // iinf insertion point: end of iinf content, relative to meta start.
        val iinfInsertAt = (iinf.start - meta.start) + iinf.totalSize.toInt()
        var grown: ByteArray
        if (ilocInsertAt >= iinfInsertAt) {
            grown = splice(metaBytes, ilocInsertAt, ilocEntry)
            grown = splice(grown, iinfInsertAt, infe)
        } else {
            grown = splice(metaBytes, iinfInsertAt, infe)
            grown = splice(grown, ilocInsertAt, ilocEntry)
        }
        val delta = grown.size - metaBytes.size
        // Box starts in the grown copy: each insertion shifts the boxes
        // after it. (In the usual layout iinf sits before iloc, so the
        // infe insertion pushes iloc right by infe.size.)
        val iinfRel = (iinf.start - meta.start) +
            if (ilocInsertAt < iinfInsertAt) ilocEntrySize else 0
        val ilocRel = (iloc.start - meta.start) +
            if (iinfInsertAt < ilocInsertAt) infe.size else 0
        // Final position of the new iloc entry (meta-relative): end of
        // the iloc box in the grown copy.
        val newEntryRel = ilocRel + iloc.totalSize.toInt()
        // Patch counts and box sizes inside the grown meta copy.
        // iinf count: version/flags (4) then count at +12 (u16 in v0, u32 in v1+).
        patchBoxCount(grown, iinfRel + 12, meta.iinfVersion != 0, +1)
        writeU32(grown, iinfRel, (iinf.totalSize + infe.size).toInt())
        // iloc count: version/flags (4) + size nibbles (2) then count at +14
        // (u16 in v0/v1, u32 in v2).
        patchBoxCount(grown, ilocRel + 14, iloc.version == 2, +1)
        writeU32(grown, ilocRel, (iloc.totalSize + ilocEntrySize).toInt())
        writeU32(grown, 0, grown.size)
        return Triple(grown, delta, newEntryRel)
    }

    /** Size of one iloc entry for a new item in this file's iloc format. */
    private fun ilocEntrySize(iloc: Iloc): Int {
        var n = if (iloc.version == 2) 4 else 2 // item_ID
        if (iloc.version >= 1) n += 2 // reserved + construction_method
        n += 2 // data_reference_index
        n += iloc.baseOffsetSize // base_offset
        n += 2 // extent_count
        n += iloc.offsetSize + iloc.lengthSize // one extent
        return n
    }

    /** Writes item_ID/offset/length into the placeholder iloc entry at [pos]. */
    private fun writeNewIlocEntry(out: ByteArray, iloc: Iloc, pos: Int, id: Int, offset: Int, length: Int) {
        var p = pos
        if (iloc.version == 2) { writeU32(out, p, id); p += 4 } else { writeU16(out, p, id); p += 2 }
        if (iloc.version >= 1) { writeU16(out, p, 0); p += 2 } // reserved + method 0
        writeU16(out, p, 0); p += 2 // data_reference_index
        writeSized(out, p, 0, iloc.baseOffsetSize); p += iloc.baseOffsetSize
        writeU16(out, p, 1); p += 2 // extent_count
        writeSized(out, p, offset, iloc.offsetSize); p += iloc.offsetSize
        writeSized(out, p, length, iloc.lengthSize)
    }

    /** Appends [packet] at EOF. Returns the grown file and the data offset. */
    private fun appendPacketData(bytes: ByteArray, packet: ByteArray): Pair<ByteArray, Int> {
        // If the last top-level box is mdat, extend it; otherwise append
        // a free box holding the packet.
        var pos = 0
        var lastStart = -1
        var lastType = ""
        var lastTotal = 0
        var lastHeader = 8
        while (pos + 8 <= bytes.size) {
            val box = parseBox(bytes, pos, bytes.size) ?: break
            lastStart = pos
            lastType = box.type
            lastTotal = box.totalSize.toInt()
            lastHeader = box.headerSize
            if (box.totalSize <= 0) break
            pos += box.totalSize.toInt()
        }
        // A 64-bit-size mdat can't be extended by rewriting its 32-bit
        // size field, so it takes the free-box path instead.
        return if (lastType == "mdat" && lastStart >= 0 && lastHeader == 8) {
            val out = ByteArrayOutputStream(bytes.size + packet.size).apply {
                write(bytes)
                write(packet)
            }.toByteArray()
            writeU32(out, lastStart, lastTotal + packet.size)
            out to (bytes.size)
        } else {
            val freeBox = ByteArrayOutputStream().apply {
                writeInt(8 + packet.size)
                write("free".toByteArray(Charsets.US_ASCII))
                write(packet)
            }.toByteArray()
            val out = bytes + freeBox
            out to (bytes.size + 8)
        }
    }

    /** Adds [delta] to every iloc extent offset in the meta at [metaStart]. */
    private fun patchIlocOffsets(bytes: ByteArray, metaStart: Int, delta: Int) {
        val meta = parseMeta(bytes, metaStart) ?: return
        for ((_, loc) in meta.iloc.items) {
            for (ext in loc.extents) {
                val v = readSized(bytes, ext.offsetPos, meta.iloc.offsetSize)
                writeSized(bytes, ext.offsetPos, (v + delta).toInt(), meta.iloc.offsetSize)
            }
        }
    }

    // ---- meta parsing ----

    private class Box(val type: String, val start: Int, val headerSize: Int, val contentSize: Long, val totalSize: Long)

    private class Item(val id: Int, val type: String?, val contentType: String?) {
        val isXmp: Boolean
            get() = type == "mime" && contentType == XMP_CONTENT_TYPE
    }

    private class Extent(val offset: Int, val length: Int, val offsetPos: Int, val lengthPos: Int)

    private class Loc(val extents: List<Extent>)

    private class Iloc(
        val version: Int,
        val start: Int,
        val totalSize: Long,
        val headerSize: Int,
        val offsetSize: Int,
        val lengthSize: Int,
        val baseOffsetSize: Int,
        val items: Map<Int, Loc>,
        /** True when some item uses construction_method 1 (idat offsets). */
        val unsupportedIdat: Boolean = false
    )

    private class Meta(
        val start: Int,
        val totalSize: Long,
        val headerSize: Int,
        val contentStart: Int,
        val iinf: Box?,
        /** iinf version (0 = u16 entry count, 1+ = u32); -1 when no iinf. */
        val iinfVersion: Int,
        val iloc: Iloc,
        val items: List<Item>
    )

    private fun parseMeta(bytes: ByteArray, from: Int = 0): Meta? {
        var pos = from
        var metaBox: Box? = null
        while (pos + 8 <= bytes.size) {
            val box = parseBox(bytes, pos, bytes.size) ?: return null
            if (box.type == "meta") {
                metaBox = box
                break
            }
            if (box.totalSize <= 0 || box.totalSize > bytes.size - pos) return null
            pos += box.totalSize.toInt()
        }
        val m = metaBox ?: return null
        val contentStart = m.start + m.headerSize + 4 // FullBox version+flags
        val end = m.start + m.totalSize.toInt()
        if (contentStart > end || end > bytes.size) return null
        var iinf: Box? = null
        var ilocBox: Box? = null
        var p = contentStart
        while (p + 8 <= end) {
            val child = parseBox(bytes, p, end) ?: break
            when (child.type) {
                "iinf" -> iinf = child
                "iloc" -> ilocBox = child
            }
            if (child.totalSize <= 0) break
            p += child.totalSize.toInt()
        }
        val iloc = ilocBox?.let { parseIloc(bytes, it) } ?: return null
        val items = iinf?.let { parseIinf(bytes, it) } ?: emptyList()
        val iinfVersion = iinf?.let { bytes[it.start + it.headerSize].toInt() and 0xFF } ?: -1
        return Meta(m.start, m.totalSize, m.headerSize, contentStart, iinf, iinfVersion, iloc, items)
    }

    private fun parseIinf(bytes: ByteArray, box: Box): List<Item> {
        val start = box.start + box.headerSize
        if (start + 4 > bytes.size) return emptyList()
        val version = bytes[start].toInt() and 0xFF
        val count: Int
        var p: Int
        if (version == 0) {
            if (start + 6 > bytes.size) return emptyList()
            count = u16(bytes, start + 4)
            p = start + 6
        } else {
            if (start + 8 > bytes.size) return emptyList()
            count = u32(bytes, start + 4)
            p = start + 8
        }
        val end = box.start + box.totalSize.toInt()
        val items = mutableListOf<Item>()
        repeat(count) {
            val entry = parseBox(bytes, p, end) ?: return items
            if (entry.type != "infe") return items
            parseInfe(bytes, entry)?.let { items += it }
            if (entry.totalSize <= 0) return items
            p += entry.totalSize.toInt()
        }
        return items
    }

    private fun parseInfe(bytes: ByteArray, box: Box): Item? {
        val start = box.start + box.headerSize
        val end = box.start + box.totalSize.toInt()
        if (start + 4 > end) return null
        val version = bytes[start].toInt() and 0xFF
        var p = start + 4
        val id: Int
        when (version) {
            2 -> {
                if (p + 4 > end) return null
                id = u16(bytes, p)
                p += 4 // item_ID + item_protection_index
            }
            3 -> {
                if (p + 6 > end) return null
                id = u32(bytes, p)
                p += 6 // item_ID + item_protection_index
            }
            else -> {
                if (p + 4 > end) return null
                id = u16(bytes, p)
                p += 4
                // v0/v1: only a name follows, no item type.
                return Item(id, null, null)
            }
        }
        if (p + 4 > end) return null
        val type = ascii(bytes, p, 4)
        p += 4
        val nameRes = readCString(bytes, p, end) ?: return Item(id, type, null)
        p = nameRes.second
        val ctRes = readCString(bytes, p, end)
        val contentType = ctRes?.first ?: ""
        return Item(id, type, contentType)
    }

    /** Reads a NUL-terminated string; returns (string, position after NUL) or null. */
    private fun readCString(bytes: ByteArray, start: Int, end: Int): Pair<String, Int>? {
        var p = start
        while (p < end && bytes[p] != 0.toByte()) p++
        if (p >= end) return null
        return String(bytes, start, p - start, Charsets.US_ASCII) to (p + 1)
    }

    private fun parseIloc(bytes: ByteArray, box: Box): Iloc? {
        val start = box.start + box.headerSize
        val end = box.start + box.totalSize.toInt()
        if (start + 4 > end) return null
        val version = bytes[start].toInt() and 0xFF
        var p = start + 4
        if (p + 1 > end) return null
        val offsetSize = (bytes[p].toInt() and 0xFF) ushr 4
        val lengthSize = (bytes[p].toInt() and 0xFF) and 0x0F
        p++
        val baseOffsetSize = (bytes[p].toInt() and 0xFF) ushr 4
        // Low nibble is index_size (v1+) or reserved (v0); not needed here.
        p++
        val count: Int
        if (version == 2) {
            if (p + 4 > end) return null
            count = u32(bytes, p)
            p += 4
        } else {
            if (p + 2 > end) return null
            count = u16(bytes, p)
            p += 2
        }
        val items = mutableMapOf<Int, Loc>()
        var unsupportedIdat = false
        repeat(count) {
            val id: Int
            if (version == 2) {
                if (p + 4 > end) return null
                id = u32(bytes, p)
                p += 4
            } else {
                if (p + 2 > end) return null
                id = u16(bytes, p)
                p += 2
            }
            if (version >= 1) {
                if (p + 2 > end) return null
                val method = bytes[p + 1].toInt() and 0x0F
                if (method != 0) unsupportedIdat = true
                p += 2
            }
            if (p + 2 > end) return null
            p += 2 // data_reference_index
            val base = readSized(bytes, p, baseOffsetSize)
            p += baseOffsetSize
            if (p + 2 > end) return null
            val extentCount = u16(bytes, p)
            p += 2
            val extents = mutableListOf<Extent>()
            repeat(extentCount) {
                if (version >= 1) {
                    // extent_index is present only for construction_method 1,
                    // which we already refused above.
                }
                val offsetPos = p
                val off = readSized(bytes, p, offsetSize)
                p += offsetSize
                val lengthPos = p
                val len = readSized(bytes, p, lengthSize)
                p += lengthSize
                extents += Extent((base + off).toInt(), len.toInt(), offsetPos, lengthPos)
            }
            items[id] = Loc(extents)
        }
        return Iloc(version, box.start, box.totalSize, box.headerSize, offsetSize, lengthSize, baseOffsetSize, items, unsupportedIdat)
    }

    private fun readItemBytes(bytes: ByteArray, iloc: Iloc, item: Item): ByteArray? {
        val loc = iloc.items[item.id] ?: return null
        val out = ByteArrayOutputStream()
        for (ext in loc.extents) {
            if (ext.offset < 0 || ext.length < 0 || ext.offset + ext.length > bytes.size) return null
            out.write(bytes, ext.offset, ext.length)
        }
        return out.toByteArray()
    }

    // ---- write helpers ----

    private fun buildInfe(itemId: Int): ByteArray {
        val name = byteArrayOf(0) // empty item_name
        val contentType = XMP_CONTENT_TYPE.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val encoding = byteArrayOf(0)
        val payloadSize = 4 + 2 + 2 + 4 + name.size + contentType.size + encoding.size
        return ByteArrayOutputStream().apply {
            writeInt(8 + payloadSize)
            write("infe".toByteArray(Charsets.US_ASCII))
            write(2); write(0); write(0); write(0) // version 2, flags 0
            writeShort(itemId)
            writeShort(0) // item_protection_index
            write("mime".toByteArray(Charsets.US_ASCII))
            write(name)
            write(contentType)
            write(encoding)
        }.toByteArray()
    }

    /** Inserts [insert] at [at] in [bytes] (offsets relative to the array). */
    private fun splice(bytes: ByteArray, at: Int, insert: ByteArray): ByteArray {
        val out = ByteArray(bytes.size + insert.size)
        bytes.copyInto(out, 0, 0, at)
        insert.copyInto(out, at)
        bytes.copyInto(out, at + insert.size, at, bytes.size)
        return out
    }

    /** Bumps the entry_count at [countPos] (relative offsets). */
    private fun patchBoxCount(bytes: ByteArray, countPos: Int, is32: Boolean, delta: Int) {
        if (is32) {
            val cur = u32(bytes, countPos)
            writeU32(bytes, countPos, cur + delta)
        } else {
            val cur = u16(bytes, countPos)
            writeU16(bytes, countPos, cur + delta)
        }
    }

    private fun writeExtentLength(bytes: ByteArray, iloc: Iloc, itemId: Int, newLength: Int) {
        val meta = parseMeta(bytes) ?: return
        val loc = meta.iloc.items[itemId] ?: return
        val ext = loc.extents.firstOrNull() ?: return
        writeSized(bytes, ext.lengthPos, newLength, meta.iloc.lengthSize)
    }

    private fun writeExtent(bytes: ByteArray, iloc: Iloc, itemId: Int, offset: Int, length: Int) {
        val meta = parseMeta(bytes) ?: return
        val loc = meta.iloc.items[itemId] ?: return
        val ext = loc.extents.firstOrNull() ?: return
        writeSized(bytes, ext.offsetPos, offset, meta.iloc.offsetSize)
        writeSized(bytes, ext.lengthPos, length, meta.iloc.lengthSize)
    }

    // ---- box parsing helpers ----

    private fun parseBox(bytes: ByteArray, pos: Int, end: Int): Box? {
        if (pos + 8 > end) return null
        val size32 = u32(bytes, pos).toLong() and 0xFFFFFFFFL
        val type = ascii(bytes, pos + 4, 4)
        if (size32 == 1L) {
            if (pos + 16 > end) return null
            val total = u64(bytes, pos + 8)
            if (total < 16 || pos + total > end) return null
            return Box(type, pos, 16, total - 16, total)
        }
        if (size32 == 0L) {
            val total = (end - pos).toLong()
            return Box(type, pos, 8, total - 8, total)
        }
        if (size32 < 8 || pos + size32 > end) return null
        return Box(type, pos, 8, size32 - 8, size32)
    }

    private fun ascii(bytes: ByteArray, start: Int, len: Int): String =
        String(bytes, start, len, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, pos: Int): Int =
        ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)

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

    /** Reads an unsigned integer of [size] bytes (0, 1, 2, 4 or 8). */
    private fun readSized(bytes: ByteArray, pos: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) {
            if (pos + i >= bytes.size) return v
            v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
        }
        return v
    }

    private fun writeSized(bytes: ByteArray, pos: Int, value: Int, size: Int) {
        for (i in 0 until size) {
            bytes[pos + i] = ((value ushr (8 * (size - 1 - i))) and 0xFF).toByte()
        }
    }

    private fun writeU16(bytes: ByteArray, pos: Int, v: Int) {
        bytes[pos] = ((v ushr 8) and 0xFF).toByte()
        bytes[pos + 1] = (v and 0xFF).toByte()
    }

    private fun writeU32(bytes: ByteArray, pos: Int, v: Int) {
        bytes[pos] = ((v ushr 24) and 0xFF).toByte()
        bytes[pos + 1] = ((v ushr 16) and 0xFF).toByte()
        bytes[pos + 2] = ((v ushr 8) and 0xFF).toByte()
        bytes[pos + 3] = (v and 0xFF).toByte()
    }

    private fun ByteArrayOutputStream.writeShort(v: Int) {
        write((v shr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }
}
