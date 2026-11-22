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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

/**
 * Thread-safe output-name reservation for parallel export. Picking a free
 * name and marking it used is one atomic step, so two workers can never
 * get the same output name. The name logic itself lives in
 * [BatchProcessor.buildOutputName]; this only guards the shared set.
 *
 * [overwrite] on: files already in the output folder are about to be
 * deleted by the overwrite step, so they must NOT count as collisions —
 * otherwise every re-export comes out as "name_1" (bo, 2026-11-23).
 * Names allocated during this run still count, so two sources in one
 * batch that map to the same output name are still numbered.
 */
class NameAllocator(existing: Set<String>, private val overwrite: Boolean = false) {
    private val used = if (overwrite) mutableSetOf() else existing.toMutableSet()

    @Synchronized
    fun allocate(original: String, suffixMode: Boolean, targetExt: String?): String {
        val name = BatchProcessor.buildOutputName(original, suffixMode, used, targetExt)
        used += name
        return name
    }
}

object BatchProcessor {
    private val supportedExtensions = setOf("jpg", "jpeg", "png", "webp")
    private val videoExtensions = setOf("mp4", "mov")

    /**
     * Max files processed at once. Each file is independent (its own temp
     * file, its own output name), so the batch parallelises cleanly — but
     * the cap stays at 3: a full-res bitmap is ~30 MB in RAM and a video
     * is held whole in memory by Mp4Xmp, so unbounded parallelism would
     * OOM on big batches. Videos take all 3 permits and run alone.
     */
    private const val MAX_PARALLEL_FILES = 3

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
        val allocator = NameAllocator(
            outputDir.listFiles().mapNotNull { it.name }.toSet(),
            overwrite = options.overwrite
        )
        val workDir = File(context.cacheDir, "metabatch-work").apply {
            deleteRecursively()
            mkdirs()
        }

        // Results are reported to the UI in input order: ProgressScreen
        // maps fileResults[i] to sourceNames[i], so a fast file must not
        // overtake a slow one in the list — completed results wait in
        // [pending] until every earlier file has been reported.
        val semaphore = Semaphore(MAX_PARALLEL_FILES)
        val reportMutex = Mutex()
        val pending = mutableMapOf<Int, BatchItemResult>()
        var nextToReport = 0

        suspend fun report(index: Int, result: BatchItemResult) {
            reportMutex.withLock {
                pending[index] = result
                while (true) {
                    val r = pending.remove(nextToReport) ?: break
                    onFileResult(r)
                    onProgress(nextToReport + 1, sources.size, r.name)
                    nextToReport++
                }
            }
        }

        suspend fun processOne(index: Int, source: Uri, name: String): BatchItemResult {
            var temp: File? = null
            var converted: File? = null
            val result = try {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (!isSupportedMediaName(name)) {
                    throw IOException("Unsupported format. This build writes JPG, PNG, WebP, MP4 and MOV; HEIC, TIFF, AVIF, RAW, MKV and WebM are skipped without changing the source.")
                }
                // Videos always keep their original format: there is
                // no re-encode step for video, so the format option
                // only applies to images.
                val targetExt = if (isVideoName(name)) ext else targetExtension(ext, options.format)
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
                // No conversion (targetExt == source ext): pass null so
                // the name is built from the original filename verbatim
                // — "photo.jpeg" stays ".jpeg", "PHOTO.JPG" keeps its
                // case. Only a real format change rewrites the suffix.
                val outName = allocator.allocate(
                    original = name,
                    suffixMode = options.namingSuffix,
                    targetExt = if (targetExt == ext) null else targetExt
                )
                if (options.overwrite) {
                    outputDir.findFile(outName)?.delete()
                }
                val out = outputDir.createFile(mime, outName)
                    ?: throw IOException("Cannot create output file.")
                resolver.openOutputStream(out.uri, "w")?.use { output ->
                    fileToWrite.inputStream().use { input -> input.copyTo(output) }
                } ?: throw IOException("Cannot write output file.")

                BatchItemResult(name, true, "Saved as $outName")
            } catch (e: Exception) {
                BatchItemResult(name, false, e.message ?: "Unknown error")
            } finally {
                temp?.delete()
                converted?.delete()
            }
            report(index, result)
            return result
        }

        try {
            coroutineScope {
                sources.mapIndexed { index, source ->
                    async {
                        val name = queryName(resolver, source)
                        // Videos hold the whole file in RAM (Mp4Xmp), so a
                        // video takes all permits and runs alone; images
                        // share the 3. (This coroutines Semaphore is
                        // single-permit only, so take them one by one.)
                        val permits = if (isVideoName(name)) MAX_PARALLEL_FILES else 1
                        repeat(permits) { semaphore.acquire() }
                        try {
                            processOne(index, source, name)
                        } finally {
                            repeat(permits) { semaphore.release() }
                        }
                    }
                }.awaitAll()
            }
        } finally {
            workDir.deleteRecursively()
        }
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

    /** Video file-name check: MP4 and MOV share the same box structure. */
    fun isVideoName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in videoExtensions
    }

    /** Images or videos — everything this build can write. */
    fun isSupportedMediaName(name: String): Boolean =
        isSupportedImageName(name) || isVideoName(name)

    /**
     * Pure output-name builder (unit-tested on the JVM).
     * suffixMode: "base_metabatch.ext", else "base.ext". On collision the
     * name is numbered ("base_metabatch_1.ext" / "base_1.ext") unless the
     * caller deletes the existing file first (overwrite mode).
     * [targetExt] replaces the original extension when the format changes;
     * null means no conversion, and the original extension is then used
     * verbatim (spelling and case preserved — "photo.jpeg" stays ".jpeg").
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

    /** Pure format mapping (unit-tested on the JVM). Videos always stay original. */
    fun targetExtension(sourceExt: String, format: ExportFormat): String {
        if (sourceExt.lowercase() in videoExtensions) return sourceExt.lowercase()
        return when (format) {
            ExportFormat.ORIGINAL -> sourceExt
            ExportFormat.JPEG -> "jpg"
            ExportFormat.PNG -> "png"
            ExportFormat.WEBP -> "webp"
        }
    }

    fun mimeForExt(ext: String): String = when (ext) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> "image/webp"
    }
}
