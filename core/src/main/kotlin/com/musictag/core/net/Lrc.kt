package com.musictag.core.net

/**
 * LRC helpers: detect LRC, strip timestamps for previews, and merge translations.
 *
 * Timestamps are matched in the common `[mm:ss]`, `[mm:ss.SSS]` and `[mm:ss:SS]`
 * forms. Merging matches lines by the numeric time (not by textual tag), so
 * `[00:09.48]` and `[00:09.480]` still pair up.
 */
object Lrc {
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    private val META_TAG = Regex(
        """^\[(ti|ar|al|by|offset|length|re|ve|tool|kana|id):.*]$""",
        RegexOption.IGNORE_CASE,
    )

    /** True when the text contains at least one LRC time tag. */
    fun isLrc(text: String?): Boolean =
        !text.isNullOrBlank() && text.lineSequence().any { TIME_TAG.containsMatchIn(it) }

    /** Removes `[mm:ss.xx]` tags and LRC metadata lines, keeping readable lyrics. */
    fun plainText(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { META_TAG.matches(it) }
            .map { line -> TIME_TAG.replace(line, "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /**
     * Merges an original LRC and a translated LRC line by line (best effort).
     *
     * Every original line whose timestamp exists in [translation] gets the
     * translated text appended as an extra line (same timestamp would be
     * duplicated, so the plain translated text follows directly, keeping the
     * result valid LRC). Lines without a translation are left untouched.
     */
    fun mergeTranslation(original: String?, translation: String?): String? {
        if (original.isNullOrBlank()) return original
        if (translation.isNullOrBlank()) return original

        val translatedLines = LinkedHashMap<Long, String>()
        translation.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || META_TAG.matches(line)) return@forEach
            val text = TIME_TAG.replace(line, "").trim()
            if (text.isEmpty()) return@forEach
            timeValues(line).forEach { millis -> translatedLines[millis] = text }
        }
        if (translatedLines.isEmpty()) return original

        val result = StringBuilder()
        var first = true
        original.lineSequence().forEach { rawLine ->
            if (!first) result.append('\n')
            first = false
            result.append(rawLine)

            val line = rawLine.trim()
            if (line.isEmpty() || META_TAG.matches(line)) return@forEach
            val translated = timeValues(line)
                .asSequence()
                .mapNotNull { translatedLines[it] }
                .firstOrNull()
            if (translated != null) result.append('\n').append(translated)
        }
        return result.toString()
    }

    private fun timeValues(line: String): List<Long> =
        TIME_TAG.findAll(line).map { match -> match.toMillis() }.toList()

    private fun MatchResult.toMillis(): Long {
        val minutes = groupValues[1].toLongOrNull() ?: 0L
        val seconds = groupValues[2].toLongOrNull() ?: 0L
        val fraction = groupValues[3]
        val millis = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        return minutes * 60_000L + seconds * 1_000L + millis
    }
}
