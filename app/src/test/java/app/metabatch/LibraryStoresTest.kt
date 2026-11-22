package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryStoresTest {

    @Test
    fun templateJson_roundTrip() {
        val templates = listOf(
            Template("风景", "日落", "海边日落", "风景、旅行", "bo", "", "", "ComfyUI"),
            Template("空模板")
        )
        val restored = TemplateStore.templatesFromJson(TemplateStore.templatesToJson(templates))
        assertEquals(templates, restored)
    }

    @Test
    fun templateJson_specialChars_roundTrip() {
        val templates = listOf(
            Template("名\"字\n换行", "标\t题", "描\\述", "a、b", "", "", "")
        )
        val restored = TemplateStore.templatesFromJson(TemplateStore.templatesToJson(templates))
        assertEquals(templates, restored)
    }

    @Test
    fun templateJson_badInput_returnsEmpty() {
        assertEquals(emptyList<Template>(), TemplateStore.templatesFromJson("not json"))
        assertEquals(emptyList<Template>(), TemplateStore.templatesFromJson(""))
    }

    @Test
    fun historyJson_roundTrip() {
        val records = listOf(
            ExportRecord(1793000000000L, 12, 12, 0, "MetaBatch", "content://x", 8300L),
            ExportRecord(1793000100000L, 3, 2, 1, "MetaBatch", "", 1200L)
        )
        val restored = HistoryStore.recordsFromJson(HistoryStore.recordsToJson(records))
        assertEquals(records, restored)
    }

    @Test
    fun addRecord_newestFirst_capsAt50() {
        var records = emptyList<ExportRecord>()
        repeat(60) { i ->
            records = HistoryStore.addRecord(
                records,
                ExportRecord(i.toLong(), 1, 1, 0, "F", "", 100L)
            )
        }
        assertEquals(HistoryStore.MAX_RECORDS, records.size)
        assertEquals(59L, records.first().timeMs)
        assertEquals(10L, records.last().timeMs)
        assertTrue(records.all { it.total == 1 })
    }
}
