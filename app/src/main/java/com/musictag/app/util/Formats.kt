package com.musictag.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Formats {

    fun duration(millis: Long?): String {
        if (millis == null || millis <= 0L) return "--:--"
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%d:%02d", minutes, seconds)
        }
    }

    fun fileSize(bytes: Long?): String {
        if (bytes == null || bytes <= 0L) return "未知"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format("%.1f MB", mb)
            else -> String.format("%.0f KB", kb)
        }
    }

    /** Exact byte count with thousands separators, e.g. "118,438 字节". */
    fun fileSizeExact(bytes: Long?): String =
        if (bytes == null || bytes < 0L) "未知" else String.format(Locale.US, "%,d 字节", bytes)

    /** Local date-time of a file's last modification. */
    fun dateTime(epochMs: Long?): String {
        if (epochMs == null || epochMs <= 0L) return "未知"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
    }

    fun durationMillis(millis: Long?): String =
        if (millis == null || millis <= 0L) "未知" else "$millis ms"

    /** "3/12" → "3 / 12"; leaves unusual values untouched. */
    fun trackNumber(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return "—"
        val parts = value.split("/")
        return if (parts.size == 2) "${parts[0].trim()} / ${parts[1].trim()}" else value
    }
}
