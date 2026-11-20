package com.remaply.navigation.core.internal

/**
 * A tiny JSON encoder for request options (maps of strings, numbers, booleans, lists and maps).
 * Avoids a serialization dependency, and works in plain JVM unit tests unlike android's org.json.
 */
internal object Json {
    fun encodeOrNull(options: Map<String, Any?>): String? = if (options.isEmpty()) null else encode(options)

    fun encode(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> appendString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> out.append(finite(value).toString())
            is Float -> out.append(finite(value.toDouble()).toString())
            is Number -> out.append(value.toString())
            is Enum<*> -> appendString(out, value.name)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    appendString(out, k.toString())
                    out.append(':')
                    append(out, v)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    append(out, v)
                }
                out.append(']')
            }
            is Array<*> -> append(out, value.asList())
            else -> throw IllegalArgumentException("Unsupported JSON value type: ${value::class.java.name}")
        }
    }

    private fun finite(d: Double): Double {
        require(d.isFinite()) { "JSON numbers must be finite, was $d" }
        return d
    }

    private fun appendString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}
