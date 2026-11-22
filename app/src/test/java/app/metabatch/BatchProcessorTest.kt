package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.concurrent.thread

class BatchProcessorTest {

    @Test
    fun buildOutputName_suffixMode_noCollision() {
        assertEquals(
            "photo_metabatch.jpg",
            BatchProcessor.buildOutputName("photo.jpg", true, emptySet())
        )
    }

    @Test
    fun buildOutputName_suffixMode_collisionNumbered() {
        val existing = setOf("photo_metabatch.jpg", "photo_metabatch_1.jpg")
        assertEquals(
            "photo_metabatch_2.jpg",
            BatchProcessor.buildOutputName("photo.jpg", true, existing)
        )
    }

    @Test
    fun buildOutputName_originalMode_noCollision() {
        assertEquals(
            "photo.jpg",
            BatchProcessor.buildOutputName("photo.jpg", false, emptySet())
        )
    }

    @Test
    fun buildOutputName_originalMode_collisionNumbered() {
        assertEquals(
            "photo_1.jpg",
            BatchProcessor.buildOutputName("photo.jpg", false, setOf("photo.jpg"))
        )
    }

    @Test
    fun buildOutputName_targetExtReplacesExtension() {
        assertEquals(
            "photo_metabatch.png",
            BatchProcessor.buildOutputName("photo.jpg", true, emptySet(), targetExt = "png")
        )
        assertEquals(
            "photo.png",
            BatchProcessor.buildOutputName("photo.jpg", false, emptySet(), targetExt = "png")
        )
    }

    @Test
    fun buildOutputName_noConversion_keepsExtensionVerbatim() {
        // No targetExt: the original suffix is used as-is, not normalized.
        assertEquals(
            "photo.jpeg",
            BatchProcessor.buildOutputName("photo.jpeg", false, emptySet())
        )
        assertEquals(
            "PHOTO.JPG",
            BatchProcessor.buildOutputName("PHOTO.JPG", false, emptySet())
        )
        assertEquals(
            "photo_metabatch.jpeg",
            BatchProcessor.buildOutputName("photo.jpeg", true, emptySet())
        )
    }

    @Test
    fun buildOutputName_noExtension() {
        assertEquals(
            "photo_metabatch",
            BatchProcessor.buildOutputName("photo", true, emptySet())
        )
    }

    @Test
    fun deletableSources_skipsSourceThatIsAlsoTheOutput() {
        val a = "content://com.android.externalstorage.documents/document/primary%3AF%2Fa.png"
        val b = "content://x/b.png"
        val results = listOf(
            BatchItemResult("a.png", true, "Saved as a.png", outputUri = a),
            BatchItemResult("b.png", true, "Saved as b.png", outputUri = "content://x/b_out.png")
        )
        assertEquals(listOf(b), BatchProcessor.deletableSourceUris(listOf(a, b), results))
    }

    @Test
    fun deletableSources_skipsFailedExports() {
        val results = listOf(BatchItemResult("a.png", false, "boom"))
        assertEquals(emptyList<String>(), BatchProcessor.deletableSourceUris(listOf("content://x/a.png"), results))
    }

    @Test
    fun nameAllocator_overwriteIgnoresPreExistingNames() {
        val allocator = NameAllocator(setOf("photo.jpg"), overwrite = true)
        assertEquals("photo.jpg", allocator.allocate("photo.jpg", false, null))
        // A second source in the same batch mapping to the same name is
        // still numbered — only files from earlier runs are ignored.
        assertEquals("photo_1.jpg", allocator.allocate("photo.jpg", false, null))
    }

    @Test
    fun nameAllocator_noOverwriteKeepsPreExistingNames() {
        val allocator = NameAllocator(setOf("photo.jpg"), overwrite = false)
        assertEquals("photo_1.jpg", allocator.allocate("photo.jpg", false, null))
    }

    @Test
    fun nameAllocator_concurrentAllocationsAreUnique() {
        val allocator = NameAllocator(setOf("photo.jpg"))
        val results = Collections.synchronizedList(mutableListOf<String>())
        val threads = List(20) {
            thread {
                repeat(5) { results += allocator.allocate("photo.jpg", false, null) }
            }
        }
        threads.forEach { it.join() }
        assertEquals(100, results.size)
        assertEquals(100, results.toSet().size)
        assertTrue("photo.jpg" !in results)
    }

    @Test
    fun targetExtension_mapping() {
        assertEquals("jpg", BatchProcessor.targetExtension("jpg", ExportFormat.ORIGINAL))
        assertEquals("jpeg", BatchProcessor.targetExtension("jpeg", ExportFormat.ORIGINAL))
        assertEquals("jpg", BatchProcessor.targetExtension("png", ExportFormat.JPEG))
        assertEquals("png", BatchProcessor.targetExtension("jpg", ExportFormat.PNG))
        assertEquals("webp", BatchProcessor.targetExtension("jpg", ExportFormat.WEBP))
    }

    @Test
    fun isSupportedImageName_mapping() {
        assertTrue(BatchProcessor.isSupportedImageName("photo.jpg"))
        assertTrue(BatchProcessor.isSupportedImageName("photo.PNG"))
        assertTrue(BatchProcessor.isSupportedImageName("a.webp"))
        assertFalse(BatchProcessor.isSupportedImageName("a.heic"))
        assertFalse(BatchProcessor.isSupportedImageName("noext"))
    }

    @Test
    fun isSupportedMediaName_mapping() {
        assertTrue(BatchProcessor.isSupportedMediaName("clip.mp4"))
        assertTrue(BatchProcessor.isSupportedMediaName("clip.MOV"))
        assertTrue(BatchProcessor.isSupportedMediaName("photo.jpg"))
        assertFalse(BatchProcessor.isSupportedMediaName("clip.mkv"))
        assertFalse(BatchProcessor.isSupportedMediaName("clip.webm"))
        assertFalse(BatchProcessor.isSupportedMediaName("clip.avi"))
    }

    @Test
    fun isVideoName_mapping() {
        assertTrue(BatchProcessor.isVideoName("clip.mp4"))
        assertTrue(BatchProcessor.isVideoName("clip.MOV"))
        assertFalse(BatchProcessor.isVideoName("photo.jpg"))
        assertFalse(BatchProcessor.isVideoName("clip.mkv"))
    }

    @Test
    fun targetExtension_videoStaysOriginal() {
        assertEquals("mp4", BatchProcessor.targetExtension("mp4", ExportFormat.JPEG))
        assertEquals("mov", BatchProcessor.targetExtension("MOV", ExportFormat.PNG))
        assertEquals("mp4", BatchProcessor.targetExtension("mp4", ExportFormat.ORIGINAL))
    }

    @Test
    fun mimeForExt_mapping() {
        assertEquals("image/jpeg", BatchProcessor.mimeForExt("jpg"))
        assertEquals("image/jpeg", BatchProcessor.mimeForExt("jpeg"))
        assertEquals("image/png", BatchProcessor.mimeForExt("png"))
        assertEquals("image/webp", BatchProcessor.mimeForExt("webp"))
        assertEquals("video/mp4", BatchProcessor.mimeForExt("mp4"))
        assertEquals("video/quicktime", BatchProcessor.mimeForExt("mov"))
    }
}
