package app.metabatch

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream

/**
 * Reads a video's existing metadata (MP4/MOV) without modifying the file.
 *
 * Two sources, XMP first: the six fields live in the XMP packet
 * (moov/udta/uuid, same builder as images), so a video MetaBatch wrote
 * reads back exactly. Standard atoms are only a fallback for videos
 * other apps wrote: title from ©nam, artist from ©art. There is no
 * standard atom for description/keywords/copyright/source that
 * MediaMetadataRetriever exposes, so those come from XMP only.
 */
object VideoMetadataReader {

    fun read(context: Context, uri: Uri): PhotoMetadata {
        val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        val fileName = doc?.name ?: uri.lastPathSegment.orEmpty()
        val mimeType = doc?.type ?: ""
        val fileSize = doc?.length() ?: -1L

        var width = 0
        var height = 0
        var durationMs = 0L
        var dateTime = ""
        var orientation = ""
        var gps = ""
        var retrieverTitle = ""
        var retrieverArtist = ""

        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                orientation = when (rotation) {
                    90 -> "旋转 90°"
                    180 -> "旋转 180°"
                    270 -> "旋转 270°"
                    0 -> "正常"
                    else -> ""
                }
                dateTime = formatMp4Date(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
                gps = parseIso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
                retrieverTitle = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
                retrieverArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            } finally {
                r.release()
            }
        }

        var xmp = XmpFields()
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = Mp4Xmp.extractXmp(BufferedInputStream(stream))
                if (bytes != null) xmp = MetadataReader.parseXmp(XmpPacketReader.decode(bytes))
            }
        }

        return PhotoMetadata(
            fileName = fileName,
            mimeType = mimeType,
            fileSize = fileSize,
            width = width,
            height = height,
            dateTime = dateTime,
            make = "",
            model = "",
            software = "",
            orientation = orientation,
            gps = gps,
            description = "",
            artist = retrieverArtist,
            copyright = "",
            xmp = xmp,
            durationMs = durationMs,
            isVideo = true
        )
    }

    /** "20240101T120000.000Z" -> "2024:01:01 12:00:00"; anything else passes through. */
    fun formatMp4Date(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val m = Regex("(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})").find(raw)
            ?: return raw
        val (y, mo, d, h, mi, s) = m.destructured
        return "$y:$mo:$d $h:$mi:$s"
    }

    /** ISO 6709 "+37.7749-122.4194/" -> "37.7749, -122.4194"; anything else passes through. */
    fun parseIso6709(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val m = Regex("([+-]\\d+\\.\\d+)([+-]\\d+\\.\\d+)").find(raw)
            ?: return raw
        fun clean(s: String) = s.removePrefix("+")
        return "${clean(m.groupValues[1])}, ${clean(m.groupValues[2])}"
    }
}
