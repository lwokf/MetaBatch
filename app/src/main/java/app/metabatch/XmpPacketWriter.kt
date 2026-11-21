package app.metabatch

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Writes an XMP packet into a file as raw UTF-8 bytes.
 *
 * Do NOT route XMP through ExifInterface: it encodes TAG_XMP as US-ASCII,
 * so every non-ASCII character becomes '?'. Proven 2026-11-22 against
 * exifinterface 1.4.2 on the JVM: a packet with title "VAM日落" and
 * keywords 风景/旅行 came back from the file as "VAM??" / "??" / "??".
 * This writer does plain segment/chunk surgery instead, so the bytes we
 * write are exactly the bytes that end up in the file.
 *
 * Pure byte code, no Android APIs: fully unit-tested on the JVM.
 */
object XmpPacketWriter {

    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )
    private val PNG_XMP_KEYWORD = "XML:com.adobe.xmp".toByteArray(Charsets.US_ASCII)

    @Throws(IOException::class)
    fun write(file: File, packet: ByteArray) {
        val bytes = file.readBytes()
        val out = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
                writeJpeg(bytes, packet)
            bytes.size >= 8 && bytes.copyOf(8).contentEquals(PNG_SIGNATURE) ->
                writePng(bytes, packet)
            bytes.size >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 4) == "WEBP" ->
                writeWebP(bytes, packet)
            else -> throw IOException("Unsupported image format for XMP write.")
        }
        file.writeBytes(out)
    }

    // ---- JPEG: replace or insert an APP1 segment carrying the XMP header ----

    private fun writeJpeg(bytes: ByteArray, packet: ByteArray): ByteArray {
        val segmentPayload = XMP_HEADER + packet
        // Segment length field counts itself (2 bytes); max segment is 65535.
        if (segmentPayload.size + 2 > 65535) {
            throw IOException("XMP packet too large for a JPEG APP1 segment.")
        }
        val newSegment = ByteArrayOutputStream().apply {
            write(0xFF); write(0xE1)
            writeShort(segmentPayload.size + 2)
            write(segmentPayload)
        }.toByteArray()

        val out = ByteArrayOutputStream(bytes.size + newSegment.size)
        out.write(bytes, 0, 2) // SOI
        var pos = 2
        var inserted = false
        while (pos < bytes.size - 1) {
            if (bytes[pos] != 0xFF.toByte()) break
            var markerPos = pos + 1
            while (markerPos < bytes.size && bytes[markerPos] == 0xFF.toByte()) markerPos++
            if (markerPos >= bytes.size) break
            val marker = bytes[markerPos].toInt() and 0xFF
            if (marker == 0xDA) { // SOS: image data starts; XMP must come before it
                if (!inserted) out.write(newSegment)
                out.write(bytes, pos, bytes.size - pos)
                return out.toByteArray()
            }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                out.write(bytes, pos, markerPos + 1 - pos)
                pos = markerPos + 1
                continue
            }
            if (markerPos + 2 >= bytes.size) break
            val len = u16(bytes, markerPos + 1)
            if (len < 2 || markerPos + 1 + len > bytes.size) break
            val dataStart = markerPos + 3
            val dataLen = len - 2
            if (marker == 0xE1 && dataLen >= XMP_HEADER.size &&
                bytes.copyOfRange(dataStart, dataStart + XMP_HEADER.size).contentEquals(XMP_HEADER)
            ) {
                // Found the old XMP segment: replace it in place.
                out.write(newSegment)
                inserted = true
            } else {
                out.write(bytes, pos, markerPos + 1 + len - pos)
            }
            pos = markerPos + 1 + len
        }
        if (!inserted) out.write(newSegment)
        out.write(bytes, pos, bytes.size - pos)
        return out.toByteArray()
    }

    // ---- PNG: replace or insert an iTXt chunk with the XMP keyword ----

    private fun writePng(bytes: ByteArray, packet: ByteArray): ByteArray {
        // iTXt data: keyword NUL, compression flag 0, method 0, language NUL,
        // translated keyword NUL, then the packet bytes (uncompressed).
        val data = ByteArrayOutputStream().apply {
            write(PNG_XMP_KEYWORD); write(0)
            write(0) // compression flag: uncompressed
            write(0) // compression method
            write(0) // empty language tag
            write(0) // empty translated keyword
            write(packet)
        }.toByteArray()
        val newChunk = pngChunk("iTXt".toByteArray(Charsets.US_ASCII), data)

        val out = ByteArrayOutputStream(bytes.size + newChunk.size)
        out.write(bytes, 0, 8) // signature
        var pos = 8
        var inserted = false
        while (pos + 8 <= bytes.size) {
            val len = u32(bytes, pos)
            if (len < 0 || pos + 12 + len > bytes.size) break
            val type = ascii(bytes, pos + 4, 4)
            if (type == "IEND") {
                if (!inserted) out.write(newChunk)
                out.write(bytes, pos, bytes.size - pos)
                return out.toByteArray()
            }
            if (type == "iTXt" && isXmpItxt(bytes, pos + 8, len)) {
                if (!inserted) {
                    out.write(newChunk)
                    inserted = true
                }
                // If we already inserted one earlier, drop this duplicate.
            } else {
                if (type == "IDAT" && !inserted) out.write(newChunk).also { inserted = true }
                out.write(bytes, pos, 12 + len)
            }
            pos += 12 + len
        }
        if (!inserted) out.write(newChunk)
        out.write(bytes, pos, bytes.size - pos)
        return out.toByteArray()
    }

    private fun isXmpItxt(bytes: ByteArray, dataStart: Int, len: Int): Boolean {
        var end = dataStart
        while (end < dataStart + len && bytes[end] != 0.toByte()) end++
        val keyword = bytes.copyOfRange(dataStart, end)
        return keyword.contentEquals(PNG_XMP_KEYWORD)
    }

    private fun pngChunk(type: ByteArray, data: ByteArray): ByteArray {
        val crc = CRC32().apply { update(type); update(data) }.value
        return ByteArrayOutputStream().apply {
            writeInt(data.size)
            write(type)
            write(data)
            writeInt(crc.toInt())
        }.toByteArray()
    }

    // ---- WebP: replace or insert an "XMP " chunk inside the RIFF file ----

    private fun writeWebP(bytes: ByteArray, packet: ByteArray): ByteArray {
        val pad = if (packet.size % 2 == 1) 1 else 0
        val newChunk = ByteArrayOutputStream().apply {
            write("XMP ".toByteArray(Charsets.US_ASCII))
            writeIntLe(packet.size)
            write(packet)
            if (pad == 1) write(0)
        }.toByteArray()

        val out = ByteArrayOutputStream(bytes.size + newChunk.size)
        out.write(bytes, 0, 12) // RIFF header + WEBP
        var pos = 12
        var inserted = false
        var sizeDelta = 0
        while (pos + 8 <= bytes.size) {
            val fourcc = ascii(bytes, pos, 4)
            val size = u32le(bytes, pos + 4)
            if (size < 0 || pos + 8 + size > bytes.size) break
            val total = 8 + size + if (size % 2 == 1) 1 else 0
            if (fourcc == "XMP ") {
                out.write(newChunk)
                sizeDelta += newChunk.size - total
                inserted = true
            } else {
                out.write(bytes, pos, total)
            }
            pos += total
        }
        if (!inserted) {
            // Insert right after the RIFF/WEBP header; readers scan in order.
            val rest = ByteArrayOutputStream().apply {
                write(bytes, 0, 12)
                write(newChunk)
                write(bytes, 12, bytes.size - 12)
            }.toByteArray()
            return patchRiffSize(rest, newChunk.size)
        }
        out.write(bytes, pos, bytes.size - pos)
        return patchRiffSize(out.toByteArray(), sizeDelta)
    }

    /** RIFF size field (offset 4, little-endian) counts everything after itself. */
    private fun patchRiffSize(bytes: ByteArray, delta: Int): ByteArray {
        val oldSize = u32le(bytes, 4)
        val patched = bytes.copyOf()
        writeIntLe(patched, 4, oldSize + delta)
        return patched
    }

    // ---- byte helpers ----

    private fun ascii(bytes: ByteArray, start: Int, len: Int): String =
        String(bytes, start, len, Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, pos: Int): Int =
        ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)

    private fun u32(bytes: ByteArray, pos: Int): Int =
        ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun u32le(bytes: ByteArray, pos: Int): Int =
        ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun ByteArrayOutputStream.writeShort(v: Int) {
        write((v shr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt(v: Int) {
        write((v ushr 24) and 0xFF); write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF); write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeIntLe(v: Int) {
        write(v and 0xFF); write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF); write((v ushr 24) and 0xFF)
    }

    private fun writeIntLe(bytes: ByteArray, pos: Int, v: Int) {
        bytes[pos] = (v and 0xFF).toByte()
        bytes[pos + 1] = ((v ushr 8) and 0xFF).toByte()
        bytes[pos + 2] = ((v ushr 16) and 0xFF).toByte()
        bytes[pos + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
