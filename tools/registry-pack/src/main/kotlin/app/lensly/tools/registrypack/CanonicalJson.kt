package app.lensly.tools.registrypack

/**
 * Canonical JSON: object keys sorted, no whitespace, and only the escapes JSON requires. Accepts maps, lists,
 * strings, Int and Long numbers, booleans and null. Output never depends on locale.
 */
internal object CanonicalJson {
    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(value.toString())
            is Int, is Long -> out.append(value.toString())
            is String -> quote(out, value)
            is Map<*, *> -> {
                out.append('{')
                value.entries.sortedBy { it.key as String }.forEachIndexed { index, entry ->
                    if (index > 0) out.append(',')
                    quote(out, entry.key as String)
                    out.append(':')
                    append(out, entry.value)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("not representable as canonical JSON: ${value::class.simpleName}")
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') {
                    out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }
}
