package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun buildOutputName_noExtension() {
        assertEquals(
            "photo_metabatch",
            BatchProcessor.buildOutputName("photo", true, emptySet())
        )
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
