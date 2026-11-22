package app.metabatch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface

/** Fields carried inside an XMP packet (Dublin Core / Photoshop / TIFF namespaces). */
data class XmpFields(
    val title: String = "",
    val description: String = "",
    val creator: String = "",
    val rights: String = "",
    val keywords: List<String> = emptyList(),
    val source: String = "",
    val software: String = ""
)

/** Everything MetaBatch can show about one photo before the user edits it. */
data class PhotoMetadata(
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val dateTime: String,
    val make: String,
    val model: String,
    val software: String,
    val orientation: String,
    val gps: String,
    val description: String,
    val artist: String,
    val copyright: String,
    val xmp: XmpFields,
    val durationMs: Long = 0,
    val isVideo: Boolean = false
)

/**
 * Reads a photo's existing metadata (EXIF + XMP) without modifying the file.
 * The XMP string parser is pure Kotlin so its rules are unit-tested on the JVM.
 */
object MetadataReader {

    fun parseXmp(xmp: String?): XmpFields {
        if (xmp.isNullOrBlank()) return XmpFields()
        fun unesc(s: String) = s
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
        fun alt(tag: String): String {
            val m = Regex("<$tag>.*?<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)
                .find(xmp) ?: return ""
            return unesc(m.groupValues[1].trim())
        }
        fun seq(tag: String): String {
            val m = Regex("<$tag>.*?<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)
                .find(xmp) ?: return ""
            return unesc(m.groupValues[1].trim())
        }
        val bag = Regex("<dc:subject>.*?<rdf:Bag>(.*?)</rdf:Bag>", RegexOption.DOT_MATCHES_ALL)
            .find(xmp)?.groupValues?.get(1).orEmpty()
        val keywords = Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)
            .findAll(bag).map { unesc(it.groupValues[1].trim()) }
            .filter { it.isNotEmpty() }.distinct().toList()
        val source = Regex("<photoshop:Source>(.*?)</photoshop:Source>", RegexOption.DOT_MATCHES_ALL)
            .find(xmp)?.groupValues?.get(1)?.let { unesc(it.trim()) }.orEmpty()
        val software = Regex("<tiff:Software>(.*?)</tiff:Software>", RegexOption.DOT_MATCHES_ALL)
            .find(xmp)?.groupValues?.get(1)?.let { unesc(it.trim()) }.orEmpty()
        return XmpFields(
            title = alt("dc:title"),
            description = alt("dc:description"),
            creator = seq("dc:creator"),
            rights = alt("dc:rights"),
            keywords = keywords,
            source = source,
            software = software
        )
    }

    fun read(context: Context, uri: Uri): PhotoMetadata {
        val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        val fileName = doc?.name ?: uri.lastPathSegment.orEmpty()
        val mimeType = doc?.type ?: ""

        // Videos go through the video reader: ExifInterface has nothing
        // to say about MP4/MOV.
        if (BatchProcessor.isVideoName(fileName) || mimeType.startsWith("video/")) {
            return VideoMetadataReader.read(context, uri)
        }

        val fileSize = doc?.length() ?: -1L

        var width = 0
        var height = 0
        var dateTime = ""
        var make = ""
        var model = ""
        var software = ""
        var orientation = ""
        var gps = ""
        var description = ""
        var artist = ""
        var copyright = ""
        var xmp = XmpFields()

        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME).orEmpty()
                make = exif.getAttribute(ExifInterface.TAG_MAKE).orEmpty()
                model = exif.getAttribute(ExifInterface.TAG_MODEL).orEmpty()
                val exifSoftware = exif.getAttribute(ExifInterface.TAG_SOFTWARE).orEmpty()
                orientation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> "旋转 90°"
                    ExifInterface.ORIENTATION_ROTATE_180 -> "旋转 180°"
                    ExifInterface.ORIENTATION_ROTATE_270 -> "旋转 270°"
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> "水平翻转"
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> "垂直翻转"
                    ExifInterface.ORIENTATION_NORMAL -> "正常"
                    else -> ""
                }
                val latLong = exif.latLong
                if (latLong != null) {
                    gps = "%.6f, %.6f".format(latLong[0], latLong[1])
                }
                description = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION).orEmpty()
                artist = exif.getAttribute(ExifInterface.TAG_ARTIST).orEmpty()
                copyright = exif.getAttribute(ExifInterface.TAG_COPYRIGHT).orEmpty()
                // Raw bytes first: ExifInterface decodes XMP as UTF-8 and mangles
                // packets written in GBK/UTF-16 into replacement characters.
                val rawXmp = XmpPacketReader.readPacket(context, uri, mimeType)
                xmp = parseXmp(rawXmp ?: exif.getAttribute(ExifInterface.TAG_XMP))
                // Software: XMP (tiff:Software, UTF-8) wins; EXIF TAG_SOFTWARE
                // is the fallback for files other apps wrote.
                software = xmp.software.ifBlank { exifSoftware }
            }
        }

        if (width == 0 || height == 0) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, opts)
                    width = opts.outWidth
                    height = opts.outHeight
                }
            }
        }

        return PhotoMetadata(
            fileName, mimeType, fileSize, width, height, dateTime, make, model,
            software, orientation, gps, description, artist, copyright, xmp
        )
    }
}

/** Decodes a downsampled bitmap for thumbnails and the viewer; no new dependencies. */
object ImageLoader {
    /** One entry point for thumbnails: video URIs get a frame grab, images a sampled decode. */
    fun decodeThumb(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        val name = doc?.name.orEmpty()
        val mime = doc?.type.orEmpty()
        return if (BatchProcessor.isVideoName(name) || mime.startsWith("video/")) {
            decodeVideoFrame(context, uri, maxDim)
        } else {
            decodeSampled(context, uri, maxDim)
        }
    }

    /** First frame of a video, scaled to fit maxDim; null when the file has no readable frame. */
    fun decodeVideoFrame(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        return runCatching {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val frame = retriever.getFrameAtTime(
                    0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                ) ?: return@runCatching null
                val largest = maxOf(frame.width, frame.height)
                if (largest <= maxDim) {
                    frame
                } else {
                    val scale = maxDim.toFloat() / largest
                    Bitmap.createScaledBitmap(
                        frame,
                        (frame.width * scale).toInt().coerceAtLeast(1),
                        (frame.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                }
            } finally {
                retriever.release()
            }
        }.getOrNull()
    }

    fun decodeSampled(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
        }
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return null
        var sample = 1
        while (largest / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, opts)
            }
        }.getOrNull()
    }
}
