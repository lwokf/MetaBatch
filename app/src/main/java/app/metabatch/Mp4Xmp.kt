package app.metabatch

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * XMP packets inside MP4 / MOV files (ISO BMFF / QuickTime boxes).
 *
 * Same idea as XmpPacketWriter for images: plain box surgery, no new
 * dependencies, fully unit-tested on the JVM. The XMP packet lives in a
 * `uuid` box whose 16-byte usertype is the Adobe XMP UUID, inside the
 * movie's `udta` box (`moov/udta/uuid`). Readers that know XMP (exiftool,
 * Adobe apps) look there; our own reader scans the same place.
 *
 * One subtlety images don't have: MP4 chunk offsets (`stco`/`co64`) are
 * absolute file offsets into `mdat`. Growing `moov` shifts every byte
 * after it, so any chunk offset at or past the old end of `moov` must
 * grow by the same delta or playback breaks. moov-after-mdat files
 * (typical phone recordings) need no patching; moov-first files
 * (faststart) do. Both are handled; fragmented files (`moof`) are
 * refused — their offsets live in per-fragment boxes we don't rewrite.
 */
object Mp4Xmp {

    /** Adobe XMP UUID: BE7ACFCB-97A9-42E8-9C71-999A94E9C5A6. */
    val XMP_USERTYPE: ByteArray = byteArrayOf(
        0xBE.toByte(), 0x7A.toByte(), 0xCF.toByte(), 0xCB.toByte(),
        0x97.toByte(), 0xA9.toByte(), 0x42.toByte(), 0xE8.toByte(),
        0x9C.toByte(), 0x71.toByte(), 0x99.toByte(), 0x9A.toByte(),
        0x94.toByte(), 0xE9.toByte(), 0xC5.toByte(), 0xA6.toByte()
    )

    private class Box(
        val type: String,
        val start: Int,
        val headerSize: Int,
        val contentSize: Long,
        val totalSize: Long,
        val large: Boolean
    )

    // ---- sniffing ----

    /** ftyp at offset 4, or a classic QuickTime start (moov/mdat/wide/free). */
    fun isMp4OrMov(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        if (ascii(bytes, 4, 4) == "ftyp") return true
        return ascii(bytes, 4, 4) in setOf("moov", "mdat", "wide", "free")
    }

    /** Fragmented MP4/MOV (moof boxes): offsets live per fragment, we don't rewrite those. */
    fun hasMoof(bytes: ByteArray): Boolean {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val box = parseBox(bytes, pos) ?: return false
            if (box.type == "moof") return true
            if (box.totalSize <= 0 || box.totalSize > bytes.size - pos) return false
            pos += box.totalSize.toInt()
        }
        return false
    }

    // ---- reading (streaming: only the udta content is buffered) ----

    fun extractXmp(bytes: ByteArray): ByteArray? =
        runCatching { extractXmp(bytes.inputStream()) }.getOrNull()

    fun extractXmp(stream: InputStream): ByteArray? {
        val s = BufferedInputStream(stream)
        while (true) {
            val header = ByteArray(8)
            if (readFully(s, header) < 8) return null
            val size32 = u32(header, 0)
            val type = ascii(header, 4, 4)
            val contentSize = when (size32) {
                1 -> {
                    val lb = ByteArray(8)
                    if (readFully(s, lb) < 8) return null
                    val sz = u64(lb, 0)
                    if (sz < 16) return null
                    sz - 16
                }
                0 -> return null // extends to EOF; no moov can follow
                else -> {
                    if (size32 < 8) return null
                    (size32 - 8).toLong()
                }
            }
            if (type == "moov") return extractFromContainer(s, contentSize)
            if (!skipFully(s, contentSize)) return null
        }
    }

    /** Walks a container box's children; descends into udta/meta, returns an XMP packet. */
    private fun extractFromContainer(s: InputStream, size: Long): ByteArray? {
        var remaining = size
        while (remaining >= 8) {
            val header = ByteArray(8)
            if (readFully(s, header) < 8) return null
            remaining -= 8
            val size32 = u32(header, 0)
            val type = ascii(header, 4, 4)
            val (headerExtra, contentSize) = when (size32) {
                1 -> {
                    val lb = ByteArray(8)
                    if (readFully(s, lb) < 8) return null
                    remaining -= 8
                    val sz = u64(lb, 0)
                    if (sz < 16) return null
                    8L to (sz - 16)
                }
                0 -> return null
                else -> {
                    if (size32 < 8) return null
                    0L to (size32 - 8).toLong()
                }
            }
            if (remaining < contentSize) return null
            when (type) {
                "udta", "meta" -> {
                    var inner = contentSize
                    if (type == "meta") { // version + flags
                        val vf = ByteArray(4)
                        if (readFully(s, vf) < 4) return null
                        inner -= 4
                    }
                    extractFromContainer(s, inner)?.let { return it }
                    remaining -= contentSize
                }
                "uuid" -> {
                    if (contentSize >= 16) {
                        val usertype = ByteArray(16)
                        if (readFully(s, usertype) < 16) return null
                        if (usertype.contentEquals(XMP_USERTYPE)) {
                            val packet = ByteArray((contentSize - 16).toInt())
                            if (readFully(s, packet) < packet.size) return null
                            return packet
                        }
                        if (!skipFully(s, contentSize - 16)) return null
                    } else {
                        if (!skipFully(s, contentSize)) return null
                    }
                    remaining -= contentSize
                }
                else -> {
                    if (!skipFully(s, contentSize)) return null
                    remaining -= contentSize
                }
            }
        }
        // Trailing bytes (< 8, no whole box left): consume them so the
        // caller stays aligned with the container boundary.
        if (remaining > 0) skipFully(s, remaining)
        return null
    }

    // ---- writing (byte array: insert/replace moov/udta/uuid, patch sizes + chunk offsets) ----

    @Throws(IOException::class)
    fun writeXmp(bytes: ByteArray, packet: ByteArray): ByteArray {
        if (hasMoof(bytes)) {
            throw IOException("Fragmented MP4/MOV files are not supported yet.")
        }
        var pos = 0
        var moov: Box? = null
        while (pos + 8 <= bytes.size) {
            val box = parseBox(bytes, pos) ?: break
            if (box.type == "moov") {
                moov = box
                break
            }
            if (box.totalSize <= 0 || box.totalSize > bytes.size - pos) break
            pos += box.totalSize.toInt()
        }
        val m = moov ?: throw IOException("No moov box found in this MP4/MOV file.")
        if (m.totalSize < 8 || m.start + m.totalSize > bytes.size) {
            throw IOException("Truncated moov box.")
        }
        val moovEnd = m.start + m.totalSize
        val oldMoov = bytes.copyOfRange(m.start, moovEnd.toInt())

        val uuidBox = buildUuidBox(packet)
        var newMoov = applyUdtaChange(oldMoov, uuidBox)
        val delta = (newMoov.size - oldMoov.size).toLong()
        if (delta != 0L) {
            newMoov = patchChunkOffsets(newMoov, threshold = moovEnd, delta = delta)
        }

        val out = ByteArrayOutputStream(bytes.size + newMoov.size)
        out.write(bytes, 0, m.start)
        out.write(newMoov)
        out.write(bytes, moovEnd.toInt(), bytes.size - moovEnd.toInt())
        return out.toByteArray()
    }

    private fun buildUuidBox(packet: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            writeInt(24 + packet.size)
            write("uuid".toByteArray(Charsets.US_ASCII))
            write(XMP_USERTYPE)
            write(packet)
        }.toByteArray()

    private fun buildUdta(uuidBox: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            writeInt(8 + uuidBox.size)
            write("udta".toByteArray(Charsets.US_ASCII))
            write(uuidBox)
        }.toByteArray()

    /**
     * Inserts or replaces the XMP uuid box inside moov's udta (creating
     * udta if needed), patching the udta and moov size fields. Works on
     * a standalone copy of the moov box; positions are moov-relative.
     */
    private fun applyUdtaChange(moovBytes: ByteArray, uuidBox: ByteArray): ByteArray {
        val moovBox = parseBox(moovBytes, 0)
            ?: throw IOException("Cannot parse moov box.")
        var pos = moovBox.headerSize
        val end = moovBytes.size
        while (pos + 8 <= end) {
            val child = parseBox(moovBytes, pos) ?: break
            if (child.type == "udta") {
                val udtaStart = pos
                val udtaEnd = pos + child.totalSize.toInt()
                var upos = pos + child.headerSize
                while (upos + 8 <= udtaEnd) {
                    val u = parseBox(moovBytes, upos) ?: break
                    if (u.type == "uuid" && u.contentSize >= 16 &&
                        moovBytes.copyOfRange(upos + u.headerSize, upos + u.headerSize + 16)
                            .contentEquals(XMP_USERTYPE)
                    ) {
                        // Replace the old XMP uuid box in place.
                        val out = ByteArrayOutputStream(moovBytes.size + uuidBox.size)
                        out.write(moovBytes, 0, upos)
                        out.write(uuidBox)
                        out.write(moovBytes, upos + u.totalSize.toInt(), moovBytes.size - (upos + u.totalSize.toInt()))
                        val replaced = out.toByteArray()
                        val delta = uuidBox.size - u.totalSize.toInt()
                        val patched = patchBoxSize(replaced, udtaStart, child.totalSize.toInt() + delta)
                        return patchBoxSize(patched, 0, patched.size)
                    }
                    if (u.totalSize <= 0) break
                    upos += u.totalSize.toInt()
                }
                // No XMP uuid in udta: append at the end of its content.
                val out = ByteArrayOutputStream(moovBytes.size + uuidBox.size)
                out.write(moovBytes, 0, udtaEnd)
                out.write(uuidBox)
                out.write(moovBytes, udtaEnd, moovBytes.size - udtaEnd)
                val appended = out.toByteArray()
                val patched = patchBoxSize(appended, udtaStart, child.totalSize.toInt() + uuidBox.size)
                return patchBoxSize(patched, 0, patched.size)
            }
            if (child.totalSize <= 0) break
            pos += child.totalSize.toInt()
        }
        // No udta at all: create one at the end of moov.
        val newUdta = buildUdta(uuidBox)
        val out = ByteArrayOutputStream(moovBytes.size + newUdta.size)
        out.write(moovBytes)
        out.write(newUdta)
        return patchBoxSize(out.toByteArray(), 0, moovBytes.size + newUdta.size)
    }

    /** Adds [delta] to every stco/co64 chunk offset >= [threshold]. */
    private fun patchChunkOffsets(moovBytes: ByteArray, threshold: Long, delta: Long): ByteArray {
        val out = moovBytes.copyOf()
        val moovBox = parseBox(out, 0) ?: return out
        fun walk(start: Int, end: Int) {
            var pos = start
            while (pos + 8 <= end) {
                val box = parseBox(out, pos) ?: break
                val contentStart = pos + box.headerSize
                val contentEnd = pos + box.totalSize.toInt()
                when (box.type) {
                    "stco" -> {
                        val count = u32(out, contentStart + 4).toLong() and 0xFFFFFFFFL
                        val maxEntries = ((contentEnd - (contentStart + 8)) / 4).coerceAtLeast(0)
                        var epos = contentStart + 8
                        repeat(minOf(count, maxEntries.toLong()).toInt()) {
                            val v = u32(out, epos).toLong() and 0xFFFFFFFFL
                            if (v >= threshold) writeU32(out, epos, (v + delta).toInt())
                            epos += 4
                        }
                    }
                    "co64" -> {
                        val count = u32(out, contentStart + 4).toLong() and 0xFFFFFFFFL
                        val maxEntries = ((contentEnd - (contentStart + 8)) / 8).coerceAtLeast(0)
                        var epos = contentStart + 8
                        repeat(minOf(count, maxEntries.toLong()).toInt()) {
                            val v = u64(out, epos)
                            if (v >= threshold) writeU64(out, epos, v + delta)
                            epos += 8
                        }
                    }
                    "trak", "mdia", "minf", "stbl" -> walk(contentStart, contentEnd)
                }
                if (box.totalSize <= 0) break
                pos += box.totalSize.toInt()
            }
        }
        walk(moovBox.headerSize, out.size)
        return out
    }

    /** Rewrites the size field of the box at [boxStart] (32-bit or 64-bit largesize). */
    private fun patchBoxSize(bytes: ByteArray, boxStart: Int, newTotal: Int): ByteArray {
        val box = parseBox(bytes, boxStart) ?: return bytes
        val out = bytes.copyOf()
        if (box.large) {
            writeU64(out, boxStart + 8, newTotal.toLong())
        } else {
            writeU32(out, boxStart, newTotal)
        }
        return out
    }

    // ---- box parsing helpers ----

    private fun parseBox(bytes: ByteArray, pos: Int): Box? {
        if (pos + 8 > bytes.size) return null
        val size32 = u32(bytes, pos).toLong() and 0xFFFFFFFFL
        val type = ascii(bytes, pos + 4, 4)
        if (size32 == 1L) {
            if (pos + 16 > bytes.size) return null
            val total = u64(bytes, pos + 8)
            if (total < 16) return null
            return Box(type, pos, 16, total - 16, total, true)
        }
        if (size32 == 0L) {
            val total = (bytes.size - pos).toLong()
            return Box(type, pos, 8, total - 8, total, false)
        }
        if (size32 < 8) return null
        return Box(type, pos, 8, size32 - 8, size32, false)
    }

    private fun ascii(bytes: ByteArray, start: Int, len: Int): String =
        String(bytes, start, len, Charsets.US_ASCII)

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

    private fun writeU32(bytes: ByteArray, pos: Int, v: Int) {
        bytes[pos] = ((v ushr 24) and 0xFF).toByte()
        bytes[pos + 1] = ((v ushr 16) and 0xFF).toByte()
        bytes[pos + 2] = ((v ushr 8) and 0xFF).toByte()
        bytes[pos + 3] = (v and 0xFF).toByte()
    }

    private fun writeU64(bytes: ByteArray, pos: Int, v: Long) {
        for (i in 0 until 8) bytes[pos + i] = ((v ushr (56 - 8 * i)) and 0xFF).toByte()
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

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
