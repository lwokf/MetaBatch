package app.metabatch

import android.content.Context

/** 一次导出的记录：时间、成败、输出文件夹、用时。 */
data class ExportRecord(
    val timeMs: Long,
    val total: Int,
    val success: Int,
    val failed: Int,
    val folderName: String,
    val folderUri: String,
    val durationMs: Long
)

/**
 * 导出历史的存取。JSON 编解码与“新记录放最前面、最多留 50 条”
 * 都走自带的 JsonCodec / 纯函数（单元测试覆盖），
 * SharedPreferences 只负责存一个字符串。
 */
object HistoryStore {
    private const val PREFS = "metabatch"
    private const val KEY = "history_json"
    const val MAX_RECORDS = 50

    fun recordsToJson(records: List<ExportRecord>): String {
        return JsonCodec.arr(*records.map { r ->
            JsonCodec.obj(
                "timeMs" to r.timeMs,
                "total" to r.total,
                "success" to r.success,
                "failed" to r.failed,
                "folderName" to r.folderName,
                "folderUri" to r.folderUri,
                "durationMs" to r.durationMs
            )
        }.toTypedArray())
    }

    fun recordsFromJson(json: String): List<ExportRecord> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val parsed = JsonCodec.parse(json) as? List<*> ?: return emptyList()
            parsed.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                ExportRecord(
                    timeMs = (m["timeMs"] as? Number)?.toLong() ?: 0L,
                    total = (m["total"] as? Number)?.toInt() ?: 0,
                    success = (m["success"] as? Number)?.toInt() ?: 0,
                    failed = (m["failed"] as? Number)?.toInt() ?: 0,
                    folderName = m["folderName"] as? String ?: "",
                    folderUri = m["folderUri"] as? String ?: "",
                    durationMs = (m["durationMs"] as? Number)?.toLong() ?: 0L
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 新记录放最前面，最多留 MAX_RECORDS 条。 */
    fun addRecord(records: List<ExportRecord>, record: ExportRecord): List<ExportRecord> =
        (listOf(record) + records).take(MAX_RECORDS)

    fun load(context: Context): List<ExportRecord> =
        recordsFromJson(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "").orEmpty()
        )

    fun save(context: Context, records: List<ExportRecord>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, recordsToJson(records)).apply()
    }
}
