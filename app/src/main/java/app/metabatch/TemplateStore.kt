package app.metabatch

import android.content.Context

/** 一个命名模板：七个表单字段的快照。 */
data class Template(
    val name: String,
    val title: String = "",
    val description: String = "",
    val keywords: String = "",
    val creator: String = "",
    val rights: String = "",
    val source: String = "",
    val software: String = ""
)

/**
 * 模板的存取。JSON 编解码走自带的 JsonCodec（纯函数，单元测试覆盖），
 * SharedPreferences 只负责存一个字符串。
 */
object TemplateStore {
    private const val PREFS = "metabatch"
    private const val KEY = "templates_json"

    fun templatesToJson(templates: List<Template>): String {
        return JsonCodec.arr(*templates.map { t ->
            JsonCodec.obj(
                "name" to t.name,
                "title" to t.title,
                "description" to t.description,
                "keywords" to t.keywords,
                "creator" to t.creator,
                "rights" to t.rights,
                "source" to t.source,
                "software" to t.software
            )
        }.toTypedArray())
    }

    fun templatesFromJson(json: String): List<Template> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val parsed = JsonCodec.parse(json) as? List<*> ?: return emptyList()
            parsed.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                val name = m["name"] as? String ?: ""
                if (name.isBlank()) return@mapNotNull null
                Template(
                    name = name,
                    title = m["title"] as? String ?: "",
                    description = m["description"] as? String ?: "",
                    keywords = m["keywords"] as? String ?: "",
                    creator = m["creator"] as? String ?: "",
                    rights = m["rights"] as? String ?: "",
                    source = m["source"] as? String ?: "",
                    software = m["software"] as? String ?: ""
                )
            }
        }.getOrDefault(emptyList())
    }

    fun load(context: Context): List<Template> =
        templatesFromJson(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "").orEmpty()
        )

    fun save(context: Context, templates: List<Template>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, templatesToJson(templates)).apply()
    }
}
