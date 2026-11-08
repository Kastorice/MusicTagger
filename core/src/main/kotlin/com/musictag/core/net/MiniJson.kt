package com.musictag.core.net

/**
 * Minimal, dependency free JSON parser used by the online metadata sources.
 *
 * The parser accepts standard JSON and returns:
 * - [Map] `Map<String, Any?>` for objects
 * - [List] `List<Any?>` for arrays
 * - [String], [Double], [Boolean] and `null` for scalars
 *
 * Invalid input (including trailing garbage) throws [NetException] with the
 * user friendly message `响应格式异常`.
 */
object MiniJson {
    /** Guards against stack overflow on hostile/deeply nested input. */
    private const val MAX_DEPTH = 128

    private fun fail(): Nothing = throw NetException(MALFORMED)

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue(0)
        parser.skipWhitespace()
        if (!parser.atEnd()) fail()
        return value
    }

    private class Parser(private val text: String) {
        private var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail()
            skipWhitespace()
            if (atEnd()) fail()
            return when (val c = text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail()
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> fail()
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return result
            }
            while (true) {
                result.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return result
                    }
                    else -> fail()
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail()
                val c = text[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> sb.append(readEscape())
                    c.code < 0x20 -> fail()
                    else -> sb.append(c)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd()) fail()
            return when (val c = text[pos++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> readUnicodeEscape()
                else -> fail()
            }
        }

        /**
         * Reads `\uXXXX`. Surrogate pairs are handled naturally: the high and
         * low escapes are appended as two adjacent [Char]s, which forms a valid
         * UTF-16 surrogate pair in the resulting string.
         */
        private fun readUnicodeEscape(): Char = readHex4().toChar()

        private fun readHex4(): Int {
            if (pos + 4 > text.length) fail()
            var value = 0
            repeat(4) {
                val digit = Character.digit(text[pos], 16)
                if (digit < 0) fail()
                value = value * 16 + digit
                pos++
            }
            return value
        }

        private fun readNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            if (atEnd() || text[pos] !in '0'..'9') fail()
            while (!atEnd() && text[pos] in '0'..'9') pos++
            if (!atEnd() && text[pos] == '.') {
                pos++
                if (atEnd() || text[pos] !in '0'..'9') fail()
                while (!atEnd() && text[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (!atEnd() && (text[pos] == '+' || text[pos] == '-')) pos++
                if (atEnd() || text[pos] !in '0'..'9') fail()
                while (!atEnd() && text[pos] in '0'..'9') pos++
            }
            return text.substring(start, pos).toDoubleOrNull() ?: fail()
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (pos + literal.length > text.length) fail()
            if (text.regionMatches(pos, literal, 0, literal.length).not()) fail()
            pos += literal.length
            return value
        }

        private fun expect(c: Char) {
            if (atEnd() || text[pos] != c) fail()
            pos++
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else text[pos]
    }

    private const val MALFORMED = "响应格式异常"
}

/* ------------------------------------------------------------------------- */
/* Lenient accessors                                                          */
/* ------------------------------------------------------------------------- */

/** Returns this value as a JSON object, or `null` when it is not a map. */
@Suppress("UNCHECKED_CAST")
fun Any?.asMap(): Map<String, Any?>? = this as? Map<String, Any?>

/** Returns this value as a JSON array, or `null` when it is not a list. */
@Suppress("UNCHECKED_CAST")
fun Any?.asList(): List<Any?>? = this as? List<Any?>

/** String value of [key]; numbers and booleans are converted to text. */
fun Map<String, Any?>.str(key: String): String? = when (val value = this[key]) {
    is String -> value
    is Number -> value.toString()
    is Boolean -> value.toString()
    else -> null
}

/** Long value of [key]; accepts numbers and numeric strings. */
fun Map<String, Any?>.long(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull() ?: value.trim().toDoubleOrNull()?.toLong()
    else -> null
}

/** Nested object value of [key]. */
fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key].asMap()

/** Nested array value of [key]. */
fun Map<String, Any?>.arr(key: String): List<Any?>? = this[key].asList()

/**
 * Parses a response body that must contain a JSON object.
 *
 * Tolerates a UTF-8 BOM and a JSONP wrapper such as `callback({...})`, which
 * some legacy music endpoints emit even when JSON was requested. Throws
 * [NetException] with `响应格式异常` when the body is not a JSON object.
 */
internal fun parseJsonObjectBody(body: String): Map<String, Any?> {
    val text = body.trim().removePrefix("\uFEFF")
    val json = when {
        text.startsWith("{") -> text
        else -> {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) text.substring(start, end + 1) else text
        }
    }
    return MiniJson.parse(json).asMap() ?: throw NetException("响应格式异常")
}
