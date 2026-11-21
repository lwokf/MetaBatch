package app.metabatch

import androidx.exifinterface.media.ExifInterface
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
 * v0.3 writer.
 *
 * Uses AndroidX ExifInterface's documented save support for JPEG, PNG and WebP.
 * Writes common EXIF fields plus an XMP packet. Does not claim HEIC/TIFF/AVIF support.
 * The source file passed to this class must already be a private working copy.
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

        val exif = ExifInterface(file.absolutePath)

        // Common interoperable EXIF tags.
        if (values.description.isNotBlank()) {
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, values.description)
        }
        if (values.creator.isNotBlank()) {
            exif.setAttribute(ExifInterface.TAG_ARTIST, values.creator)
        }
        if (values.copyright.isNotBlank()) {
            exif.setAttribute(ExifInterface.TAG_COPYRIGHT, values.copyright)
        }

        // XMP packet carries the complete requested field set.
        // Keywords are represented as an RDF Bag (separate terms, not one string).
        exif.setAttribute(ExifInterface.TAG_XMP, MetadataXmpBuilder.build(values))
        exif.saveAttributes()

        // Re-open and check that an XMP packet is present before declaring success.
        val check = ExifInterface(file.absolutePath)
        val xmp = check.getAttribute(ExifInterface.TAG_XMP)
        if (xmp.isNullOrBlank()) {
            throw IOException("Metadata write could not be verified (XMP missing).")
        }
    }

}
