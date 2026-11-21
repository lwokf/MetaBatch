package app.metabatch

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class BatchItemResult(val name: String, val success: Boolean, val detail: String)

object BatchProcessor {
    suspend fun run(
        context: Context,
        sources: List<Uri>,
        outputTree: Uri,
        values: MetadataValues,
        onProgress: (Int, Int, String) -> Unit
    ): List<BatchItemResult> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val outputDir = DocumentFile.fromTreeUri(context, outputTree)
            ?: throw IOException("Cannot open output folder.")

        val results = mutableListOf<BatchItemResult>()
        val workDir = File(context.cacheDir, "metabatch-work").apply {
            deleteRecursively()
            mkdirs()
        }

        try {
            sources.forEachIndexed { index, source ->
                val name = queryName(resolver, source)
                onProgress(index + 1, sources.size, name)
                var temp: File? = null
                try {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in setOf("jpg", "jpeg", "png", "webp")) {
                        throw IOException("Unsupported format. This build writes JPG, PNG and WebP; HEIC, TIFF, AVIF and RAW are skipped without changing the source.")
                    }
                    temp = File.createTempFile("mb_${index}_", ".$ext", workDir)
                    val inputStream = resolver.openInputStream(source)
                        ?: throw IOException("Cannot read source file.")
                    inputStream.use { input ->
                        temp.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (temp.length() == 0L) throw IOException("The selected file is empty.")

                    MetadataWriter.write(temp, values)

                    val mime = when (ext) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        else -> "image/webp"
                    }
                    val safeName = makeOutputName(name, outputDir)
                    val out = outputDir.createFile(mime, safeName)
                        ?: throw IOException("Cannot create output file.")
                    resolver.openOutputStream(out.uri, "w")?.use { output ->
                        temp.inputStream().use { input -> input.copyTo(output) }
                    } ?: throw IOException("Cannot write output file.")

                    results += BatchItemResult(name, true, "Saved as $safeName")
                } catch (e: Exception) {
                    results += BatchItemResult(name, false, e.message ?: "Unknown error")
                } finally {
                    temp?.delete()
                }
            }
        } finally {
            workDir.deleteRecursively()
        }
        results
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

    private fun makeOutputName(original: String, dir: DocumentFile): String {
        val dot = original.lastIndexOf('.')
        val base = if (dot > 0) original.substring(0, dot) else original
        val ext = if (dot > 0) original.substring(dot) else ""
        var candidate = "${base}_metabatch$ext"
        var n = 1
        while (dir.findFile(candidate) != null) {
            candidate = "${base}_metabatch_${n++}$ext"
        }
        return candidate
    }
}
