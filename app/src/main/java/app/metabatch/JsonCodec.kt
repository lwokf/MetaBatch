package app.metabatch

/**
 * Minimal JSON codec for arrays of flat objects (what TemplateStore and
 * HistoryStore persist). org.json lives in the Android framework as stubs
 * on the JVM ("Stub!"), so unit tests cannot use it — this tiny codec keeps
 * the stores' JSON helpers JVM-testable with no extra dependency.
 */
object JsonCodec {

    /** Escape one string value (without surrounding quotes). */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code < 0x20) sb.append("\\u%04x".format(c.code))
                    else sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    /** One flat object: {"key": value, ...} (numbers/booleans bare, else quoted). */
    fun obj(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder()
        sb.append('{')
        pairs.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append('"').append(escape(k)).append("\":")
            when (v) {
                null -> sb.append("null")
                is Number, is Boolean -> sb.append(v.toString())
                else -> sb.append('"').append(escape(v.toString())).append('"')
            }
        }
        sb.append('}')
        return sb.toString()
    }

    fun arr(vararg items: String): String = "[${items.joinToString(",")}]"

    /** Parse any JSON value; returns Map / List / String / Long / Double / Boolean / null. */
    fun parse(text: String): Any? = Parser(text).parseTop()

    private class Parser(private val s: String) {
        private var i = 0

        fun parseTop(): Any? {
            val v = parseValue()
            skipWs()
            if (i != s.length) error("Trailing characters after JSON value")
            return v
        }

        private fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) error("Unexpected end of JSON")
            return when (val c = s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> {
                    expect("true"); true
                }
                'f' -> {
                    expect("false"); false
                }
                'n' -> {
                    expect("null"); null
                }
                else -> parseNumber()
            }
        }

        private fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun expect(lit: String) {
            if (!s.startsWith(lit, i)) error("Expected $lit")
            i += lit.length
        }

        private fun parseObject(): Map<String, Any?> {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                if (i >= s.length || s[i] != ':') error("Expected ':' in object")
                i++
                map[key] = parseValue()
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> {
                        i++
                        continue
                    }
                    i < s.length && s[i] == '}' -> {
                        i++
                        return map
                    }
                    else -> error("Expected ',' or '}' in object")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            i++ // [
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> {
                        i++
                        continue
                    }
                    i < s.length && s[i] == ']' -> {
                        i++
                        return list
                    }
                    else -> error("Expected ',' or ']' in array")
                }
            }
        }

        private fun parseString(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) error("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) error("Bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> error("Bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = i
            while (i < s.length && s[i] in "-+0123456789.eE") i++
            val token = s.substring(start, i)
            return token.toLongOrNull() ?: token.toDoubleOrNull()
                ?: error("Bad number $token")
        }
    }
}
