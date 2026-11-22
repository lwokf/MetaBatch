package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regression: keywords must start blank (bo, 2026-11-23) — the v0.3 sample
 * keywords 风景、旅行、自然 used to be the form default. */
class FormStateTest {

    @Test
    fun defaultKeywords_blank() {
        assertEquals("", FormState().keywords)
    }

    @Test
    fun applyDefault_keywordsBlank() {
        val form = FormState()
        form.keywords = "风景"
        form.applyDefault()
        assertEquals("", form.keywords)
        assertEquals("", form.title)
    }

    @Test
    fun clearAll_keywordsBlank() {
        val form = FormState()
        form.keywords = "风景"
        form.clearAll()
        assertEquals("", form.keywords)
    }
}
