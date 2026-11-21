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
    val source: String
)

data class WriteResult(val success: Boolean, val message: String)

/**
 * v0.4.2 writer.
 *
 * Two fixes over v0.4.1:
 *
 * 1. Merge, don't wipe. The old writer built a fresh XMP packet from the
 *    form alone, so any field left blank erased that field's original
 *    value from the photo. Now blank form fields inherit the photo's
 *    existing values; only fields the user actually filled are overwritten.
 *    Blank means keep — there is no way to clear a field yet.
 *
 * 2. XMP is written as raw UTF-8 bytes by XmpPacketWriter, never through
 *    ExifInterface. ExifInterface encodes TAG_XMP as US-ASCII, so every
 *    non-ASCII character became '?' — proven 2026-11-22 against
 *    exifinterface 1.4.2: title "VAM日落" / keywords 风景、旅行 were
 *    stored in the file as "VAM??" / "??" / "??".
 *    The same ASCII limit applies to the EXIF Description/Artist/Copyright
 *    tags, so non-ASCII values are written to XMP only, never to EXIF.
 *
 * ExifInterface is still used for the EXIF ASCII tags (pure-ASCII values
 * only). The source file passed to this class must already be a private
 * working copy.
 */
object MetadataWriter {
    private val writableExtensions = setOf("jpg", "jpeg", "png", "webp")

    fun supports(file: File): Boolean =
        file.extension.lowercase() in writableExtensions

    @Throws(IOException::class)
    fun write(file: File, values: MetadataValues) {
        if (!supports(file)) {
            throw IOException("This build can write JPEG, PNG and WebP only.")
        }

        // 1. Existing XMP first: blank form fields keep the original values.
        val existing = readExistingXmp(file)
        val merged = merge(values, existing)

        // 2. EXIF ASCII tags, pure-ASCII values only.
        val exif = ExifInterface(file.absolutePath)
        if (merged.description.isNotBlank() && isAscii(merged.description)) {
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, merged.description)
        }
        if (merged.creator.isNotBlank() && isAscii(merged.creator)) {
            exif.setAttribute(ExifInterface.TAG_ARTIST, merged.creator)
        }
        if (merged.copyright.isNotBlank() && isAscii(merged.copyright)) {
            exif.setAttribute(ExifInterface.TAG_COPYRIGHT, merged.copyright)
        }
        exif.saveAttributes()

        // 3. XMP packet as raw UTF-8 bytes, bypassing ExifInterface.
        val packet = MetadataXmpBuilder.build(merged).toByteArray(Charsets.UTF_8)
        XmpPacketWriter.write(file, packet)

        // 4. Verify with our own raw reader, not ExifInterface.
        val check = readExistingXmp(file)
            ?: throw IOException("Metadata write could not be verified (XMP missing).")
        if (merged.title.isNotBlank() && check.title != merged.title) {
            throw IOException("Metadata write could not be verified (title mismatch).")
        }
        if (merged.keywords.isNotEmpty() && check.keywords != merged.keywords) {
            throw IOException("Metadata write could not be verified (keywords mismatch).")
        }
    }

    /** Pure function, unit-tested: blank fields inherit existing values. */
    fun merge(values: MetadataValues, existing: XmpFields?): MetadataValues {
        if (existing == null) return values
        return MetadataValues(
            title = values.title.ifBlank { existing.title },
            description = values.description.ifBlank { existing.description },
            keywords = if (values.keywords.isEmpty()) existing.keywords else values.keywords,
            creator = values.creator.ifBlank { existing.creator },
            copyright = values.copyright.ifBlank { existing.rights },
            source = values.source.ifBlank { existing.source }
        )
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
