package app.metabatch

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class BatchItemResult(val name: String, val success: Boolean, val detail: String)

enum class NamingMode { SUFFIX, ORIGINAL }
enum class ExportFormat { ORIGINAL, JPEG, PNG, WEBP }

data class ExportOptions(
    val namingSuffix: Boolean = true,
    val overwrite: Boolean = false,
    val format: ExportFormat = ExportFormat.ORIGINAL,
    val keepOriginal: Boolean = true
)

object BatchProcessor {
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp")

    suspend fun run(
        context: Context,
        sources: List<Uri>,
        outputTree: Uri,
        values: MetadataValues,
        options: ExportOptions = ExportOptions(),
        onProgress: (Int, Int, String) -> Unit,
        onFileResult: (BatchItemResult) -> Unit = {}
    ): List<BatchItemResult> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val outputDir = DocumentFile.fromTreeUri(context, outputTree)
            ?: throw IOException("Cannot open output folder.")
        val existingNames = outputDir.listFiles().mapNotNull { it.name }.toMutableSet()

        val results = mutableListOf<BatchItemResult>()
        val workDir = File(context.cacheDir, "metabatch-work").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            sources.forEachIndexed { index, source ->
                val name = queryName(resolver, source)
                onProgress(index, sources.size, name)
                var temp: File? = null
                var converted: File? = null
                try {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (!isSupportedImageName(name)) {
                        throw IOException("Unsupported format. This build writes JPG, PNG and WebP; HEIC, TIFF, AVIF and RAW are skipped without changing the source.")
                    }
                    val targetExt = targetExtension(ext, options.format)
                    temp = File.createTempFile("mb_${index}_", ".$ext", workDir)
                    val inputStream = resolver.openInputStream(source)
                        ?: throw IOException("Cannot read source file.")
                    inputStream.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (temp.length() == 0L) throw IOException("The selected file is empty.")

                    // Same format: lossless byte copy (existing behaviour).
                    // Different format: decode, apply EXIF rotation, re-encode.
                    val fileToWrite = if (targetExt == ext) {
                        temp
                    } else {
                        converted = convertImage(temp, targetExt, workDir, index)
                        converted
                    }

                    MetadataWriter.write(fileToWrite, values)

                    val mime = mimeForExt(targetExt)
                    val outName = buildOutputName(
                        original = name,
                        suffixMode = options.namingSuffix,
                        existingNames = existingNames,
                        targetExt = targetExt
                    )
                    if (options.overwrite) {
                        outputDir.findFile(outName)?.delete()
                    }
                    val out = outputDir.createFile(mime, outName)
                        ?: throw IOException("Cannot create output file.")
                    resolver.openOutputStream(out.uri, "w")?.use { output ->
                        fileToWrite.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IOException("Cannot write output file.")

                    existingNames += outName
                    val result = BatchItemResult(name, true, "Saved as $outName")
                    results += result
                    onFileResult(result)
                } catch (e: Exception) {
                    val result = BatchItemResult(name, false, e.message ?: "Unknown error")
                    results += result
                    onFileResult(result)
                } finally {
                    temp?.delete()
                    converted?.delete()
                }
            }
        } finally {
            workDir.deleteRecursively()
        }
        results
    }

    /**
     * Decode [source] (a private working copy), bake in its EXIF rotation
     * so the converted image is not sideways, and re-encode it as
     * [targetExt]. JPEG/WebP use quality 95; PNG is lossless.
     */
    private fun convertImage(source: File, targetExt: String, workDir: File, index: Int): File {
        val orientation = runCatching {
            ExifInterface(source.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val bitmap = BitmapFactory.decodeFile(source.absolutePath)
            ?: throw IOException("Cannot decode image for format conversion.")
        try {
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val rotated = if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            try {
                val out = File.createTempFile("mb_conv_${index}_", ".$targetExt", workDir)
                val format = when (targetExt) {
                    "png" -> Bitmap.CompressFormat.PNG
                    "webp" -> Bitmap.CompressFormat.WEBP
                    else -> Bitmap.CompressFormat.JPEG
                }
                FileOutputStream(out).use { stream ->
                    if (!rotated.compress(format, 95, stream)) {
                        throw IOException("Image re-encode failed.")
                    }
                }
                return out
            } finally {
                if (rotated !== bitmap) rotated.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun queryName(resolver: ContentResolver, uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i) ?: "image"
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "image"
    }

    /** Pure file-name check (unit-tested on the JVM). Used by folder import too. */
    fun isSupportedImageName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in supportedExtensions
    }

    /**
     * Pure output-name builder (unit-tested on the JVM).
     * suffixMode: "base_metabatch.ext", else "base.ext". On collision the
     * name is numbered ("base_metabatch_1.ext" / "base_1.ext") unless the
     * caller deletes the existing file first (overwrite mode).
     * [targetExt] replaces the original extension when the format changes.
     */
    fun buildOutputName(
        original: String,
        suffixMode: Boolean,
        existingNames: Set<String>,
        targetExt: String? = null
    ): String {
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = targetExt ?: if (dot > 0) original.substring(dot + 1) else ""
        val extPart = if (ext.isNotEmpty()) ".$ext" else ""
        var candidate = if (suffixMode) "${base}_metabatch$extPart" else "$base$extPart"
        var n = 1
        while (candidate in existingNames) {
            candidate = if (suffixMode) "${base}_metabatch_${n++}$extPart" else "${base}_${n++}$extPart"
        }
        return candidate
    }

    /** Pure format mapping (unit-tested on the JVM). */
    fun targetExtension(sourceExt: String, format: ExportFormat): String = when (format) {
        ExportFormat.ORIGINAL -> sourceExt
        ExportFormat.JPEG -> "jpg"
        ExportFormat.PNG -> "png"
        ExportFormat.WEBP -> "webp"
    }

    fun mimeForExt(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        else -> "image/webp"
    }
}
