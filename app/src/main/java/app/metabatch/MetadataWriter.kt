package app.metabatch

import androidx.exifinterface.media.ExifInterface
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

data class MetadataValues(
    val title: String,
    val description: String,
    val keywords: List<String>,
    val creator: String,
    val copyright: String,
    val source: String,
    val software: String = ""
)

data class WriteResult(val success: Boolean, val message: String)

/**
 * v0.4.4 writer.
 *
 * Field semantics, decided by bo 2026-11-22: blank means CLEAR. A form
 * field left empty is written as blank, which removes that field's
 * original value from the photo. (v0.4.2 did the opposite — blank kept
 * the original — that was wrong and is reverted here.)
 *
 * XMP is written as raw UTF-8 bytes by XmpPacketWriter, never through
 * ExifInterface: ExifInterface encodes TAG_XMP as US-ASCII, so every
 * non-ASCII character became '?' — proven 2026-11-22 against
 * exifinterface 1.4.2: title "VAM日落" / keywords 风景、旅行 were
 * stored in the file as "VAM??" / "??" / "??".
 * The same ASCII limit applies to the EXIF Description/Artist/Copyright/
 * Software tags: a pure-ASCII value is written to EXIF; a blank value removes the
 * tag (setAttribute(tag, null), verified on the JVM); a non-ASCII value
 * is written to XMP only and the EXIF tag is removed so no stale value
 * survives.
 *
 * ExifInterface is still used for the EXIF ASCII tags only. The source
 * file passed to this class must already be a private working copy.
 */
object MetadataWriter {
    private val writableExtensions = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
    private val videoExtensions = setOf("mp4", "mov")

    fun supports(file: File): Boolean =
        file.extension.lowercase() in writableExtensions + videoExtensions

    @Throws(IOException::class)
    fun write(file: File, values: MetadataValues) {
        val ext = file.extension.lowercase()
        if (ext in videoExtensions) {
            writeVideo(file, values)
            return
        }
        // HEIC/HEIF: XMP item only (see HeicXmp). ExifInterface is not
        // used here at all — on API 26/27 it cannot read HEIC, and it
        // would mangle the XMP packet to ASCII on every level.
        if (ext == "heic" || ext == "heif") {
            writeHeic(file, values)
            return
        }
        if (!supports(file)) {
            throw IOException("This build can write JPEG, PNG, WebP, HEIC, MP4 and MOV only.")
        }

        // 1. EXIF ASCII tags: ASCII value -> write it; blank or non-ASCII
        //    -> remove the tag so the original value does not survive.
        val exif = ExifInterface(file.absolutePath)
        writeExifField(exif, ExifInterface.TAG_IMAGE_DESCRIPTION, values.description)
        writeExifField(exif, ExifInterface.TAG_ARTIST, values.creator)
        writeExifField(exif, ExifInterface.TAG_COPYRIGHT, values.copyright)
        writeExifField(exif, ExifInterface.TAG_SOFTWARE, values.software)
        exif.saveAttributes()

        // 2. XMP packet as raw UTF-8 bytes, bypassing ExifInterface.
        //    The builder omits blank fields, so blank clears them.
        val packet = MetadataXmpBuilder.build(values).toByteArray(Charsets.UTF_8)
        XmpPacketWriter.write(file, packet)

        // 3. IPTC dual-write, JPEG only: same seven fields except
        //    software (IPTC has no software dataset). Blank clears:
        //    buildDatasets omits blank fields, and an all-blank write
        //    removes the IPTC block. PNG/WebP have no IPTC container.
        if (ext == "jpg" || ext == "jpeg") {
            val datasets = Iptc.buildDatasets(values)
            file.writeBytes(Iptc.writeJpeg(file.readBytes(), datasets))
            val iptcCheck = file.inputStream().use { stream ->
                Iptc.extractFromStream(stream)?.let { Iptc.parseDatasets(it) }
            }
            if (datasets.isNotEmpty()) {
                val iptc = iptcCheck
                    ?: throw IOException("Metadata write could not be verified (IPTC missing).")
                if (iptc.title != values.title.trim()) {
                    throw IOException("Metadata write could not be verified (IPTC title mismatch).")
                }
                val normalizedKeywords = values.keywords
                    .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (iptc.keywords != normalizedKeywords) {
                    throw IOException("Metadata write could not be verified (IPTC keywords mismatch).")
                }
            } else if (iptcCheck != null && (iptcCheck.title.isNotEmpty() || iptcCheck.keywords.isNotEmpty())) {
                throw IOException("Metadata write could not be verified (IPTC not cleared).")
            }
        }

        // 4. Verify with our own raw reader, not ExifInterface.
        //    Compare against the normalized form values: the builder trims
        //    and dedupes, so " 标题 " reads back as "标题".
        val check = readExistingXmp(file)
            ?: throw IOException("Metadata write could not be verified (XMP missing).")
        if (check.title != values.title.trim()) {
            throw IOException("Metadata write could not be verified (title mismatch).")
        }
        if (check.software != values.software.trim()) {
            throw IOException("Metadata write could not be verified (software mismatch).")
        }
        val normalizedKeywords = values.keywords
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (check.keywords != normalizedKeywords) {
            throw IOException("Metadata write could not be verified (keywords mismatch).")
        }
    }

    /**
     * HEIC/HEIF write: XMP item only, via HeicXmp box surgery. The
     * EXIF tags inside the file's Exif item are NOT updated (see
     * HeicXmp) — the seven fields round-trip through XMP, which the
     * reader prefers, and the verification below reads them back.
     */
    @Throws(IOException::class)
    private fun writeHeic(file: File, values: MetadataValues) {
        val packet = MetadataXmpBuilder.build(values).toByteArray(Charsets.UTF_8)
        file.writeBytes(HeicXmp.writeXmp(file.readBytes(), packet))

        val check = HeicXmp.extractXmp(file.readBytes())
            ?.let { MetadataReader.parseXmp(XmpPacketReader.decode(it)) }
            ?: throw IOException("Metadata write could not be verified (XMP missing).")
        if (check.title != values.title.trim()) {
            throw IOException("Metadata write could not be verified (title mismatch).")
        }
        if (check.software != values.software.trim()) {
            throw IOException("Metadata write could not be verified (software mismatch).")
        }
        val normalizedKeywords = values.keywords
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (check.keywords != normalizedKeywords) {
            throw IOException("Metadata write could not be verified (keywords mismatch).")
        }
    }

    /**
     * Video write: XMP uuid box only, same seven fields, same blank-clears
     * rule. No EXIF step — ExifInterface has nothing to say about MP4.
     * Fragmented files are refused by Mp4Xmp (their offsets live in
     * per-fragment boxes we don't rewrite).
     */
    @Throws(IOException::class)
    private fun writeVideo(file: File, values: MetadataValues) {
        val bytes = file.readBytes()
        val packet = MetadataXmpBuilder.build(values).toByteArray(Charsets.UTF_8)
        file.writeBytes(Mp4Xmp.writeXmp(bytes, packet))

        val check = Mp4Xmp.extractXmp(file.readBytes())
            ?.let { MetadataReader.parseXmp(XmpPacketReader.decode(it)) }
            ?: throw IOException("Metadata write could not be verified (XMP missing).")
        if (check.title != values.title.trim()) {
            throw IOException("Metadata write could not be verified (title mismatch).")
        }
        if (check.software != values.software.trim()) {
            throw IOException("Metadata write could not be verified (software mismatch).")
        }
        val normalizedKeywords = values.keywords
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (check.keywords != normalizedKeywords) {
            throw IOException("Metadata write could not be verified (keywords mismatch).")
        }
    }

    private fun writeExifField(exif: ExifInterface, tag: String, value: String) {
        if (value.isNotBlank() && isAscii(value)) {
            exif.setAttribute(tag, value)
        } else {
            exif.setAttribute(tag, null)
        }
    }

    /** EXIF ASCII tags hold 7-bit ASCII only; anything else becomes '?'. */
    fun isAscii(s: String): Boolean = s.all { it.code < 128 }

    private fun readExistingXmp(file: File): XmpFields? {
        val bytes = runCatching {
            file.inputStream().use { stream ->
                XmpPacketReader.extractBytes(BufferedInputStream(stream), file.extension.lowercase())
            }
        }.getOrNull() ?: return null
        return MetadataReader.parseXmp(XmpPacketReader.decode(bytes))
    }
}
