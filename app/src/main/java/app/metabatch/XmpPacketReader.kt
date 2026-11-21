package app.metabatch

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.InflaterInputStream

/**
 * Reads the raw XMP packet bytes straight out of the file and decodes them.
 *
 * ExifInterface only hands back the XMP as a String that was already decoded
 * as UTF-8. Packets written in another encoding (GBK is common for Chinese
 * titles written by some apps, UTF-16 by others) come back full of U+FFFD
 * replacement characters, and that damage cannot be undone from the String.
 * Reading the raw bytes and picking the decoding that actually fits fixes it.
 */
object XmpPacketReader {

    private val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun readPacket(context: Context, uri: Uri, mimeType: String): String? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                extractBytes(BufferedInputStream(stream), mimeType)
            }
        }.getOrNull() ?: return null
        return decode(bytes)
    }

    /** Visible for testing: pick the decoding that fits the bytes. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, Charsets.UTF_16BE)
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        runCatching { Charset.forName("GBK") }.getOrNull()?.let { gbk ->
            strictDecode(bytes, gbk)?.let { return it }
        }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? {
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    /** Visible for testing: pull the raw packet bytes out of a file stream. */
    fun extractBytes(stream: InputStream, mimeType: String): ByteArray? {
        val s = BufferedInputStream(stream)
        s.mark(16)
        val sniff = ByteArray(12)
        val sniffed = readFully(s, sniff)
        s.reset()
        if (sniffed >= 3 && sniff[0] == 0xFF.toByte() && sniff[1] == 0xD8.toByte()) {
            return extractFromJpeg(s)
        }
        if (sniffed >= 8 && sniff.copyOf(8).contentEquals(PNG_SIGNATURE)) {
            return extractFromPng(s)
        }
        if (sniffed >= 12 && String(sniff, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(sniff, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) {
            return extractFromWebP(s)
        }
        return when {
            mimeType.contains("png") -> extractFromPng(s)
            mimeType.contains("webp") -> extractFromWebP(s)
            else -> extractFromJpeg(s)
        }
    }

    private fun extractFromJpeg(s: InputStream): ByteArray? {
        if (s.read() != 0xFF || s.read() != 0xD8) return null // not a JPEG
        while (true) {
            var b = s.read()
            while (b == 0xFF) b = s.read() // skip padding
            if (b == -1) return null
            val marker = b
            if (marker == 0xDA) return null // SOS: image data, XMP must come before it
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) continue // no length field
            val lenBytes = ByteArray(2)
            if (readFully(s, lenBytes) < 2) return null
            val len = ByteBuffer.wrap(lenBytes).short.toInt() and 0xFFFF
            if (len < 2) return null
            val dataLen = len - 2
            if (marker == 0xE1) { // APP1: EXIF or XMP
                val data = ByteArray(dataLen)
                if (readFully(s, data) < dataLen) return null
                if (data.size >= XMP_HEADER.size &&
                    data.copyOf(XMP_HEADER.size).contentEquals(XMP_HEADER)
                ) {
                    return data.copyOfRange(XMP_HEADER.size, data.size)
                }
            } else {
                if (!skipFully(s, dataLen.toLong())) return null
            }
        }
    }

    private fun extractFromPng(s: InputStream): ByteArray? {
        val sig = ByteArray(8)
        if (readFully(s, sig) < 8 || !sig.contentEquals(PNG_SIGNATURE)) return null
        while (true) {
            val lenBytes = ByteArray(4)
            if (readFully(s, lenBytes) < 4) return null
            val len = ByteBuffer.wrap(lenBytes).int
            if (len < 0) return null
            val typeBytes = ByteArray(4)
            if (readFully(s, typeBytes) < 4) return null
            val type = String(typeBytes, Charsets.US_ASCII)
            if (type == "IEND") return null
            if (type == "iTXt") {
                val data = ByteArray(len)
                if (readFully(s, data) < len) return null
                skipFully(s, 4) // CRC
                parseItxt(data)?.let { return it }
            } else {
                if (!skipFully(s, len.toLong() + 4)) return null // data + CRC
            }
        }
    }

    /** Parses a PNG iTXt chunk; returns the packet bytes only for the XMP keyword. */
    private fun parseItxt(data: ByteArray): ByteArray? {
        var pos = 0
        fun readCString(): String? {
            val start = pos
            while (pos < data.size && data[pos] != 0.toByte()) pos++
            if (pos >= data.size) return null
            val text = String(data, start, pos - start, Charsets.US_ASCII)
            pos++ // skip NUL
            return text
        }
        val keyword = readCString() ?: return null
        if (keyword != "XML:com.adobe.xmp") return null
        if (pos + 2 > data.size) return null
        val compressed = data[pos++] == 1.toByte()
        pos++ // compression method
        readCString() // language tag
        readCString() // translated keyword
        if (pos > data.size) return null
        val text = data.copyOfRange(pos, data.size)
        return if (compressed) {
            runCatching { InflaterInputStream(text.inputStream()).readBytes() }.getOrNull()
        } else {
            text
        }
    }

    private fun extractFromWebP(s: InputStream): ByteArray? {
        val header = ByteArray(12)
        if (readFully(s, header) < 12) return null
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(header, 8, 4, Charsets.US_ASCII) != "WEBP"
        ) return null
        while (true) {
            val chunkHeader = ByteArray(8)
            if (readFully(s, chunkHeader) < 8) return null
            val fourcc = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN).int
            if (size < 0) return null
            if (fourcc == "XMP ") {
                val data = ByteArray(size)
                if (readFully(s, data) < size) return null
                return data
            }
            if (!skipFully(s, size.toLong() + if (size % 2 == 1) 1 else 0)) return null
        }
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
