package com.musictag.app.data

import android.content.Context
import com.musictag.app.data.model.TrackItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the in-app library (ordered list of track URIs + cached metadata
 * snapshots) so the list is instant on the next launch and refreshed later.
 */
class LibraryStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): List<TrackItem> {
        val raw = preferences.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    parseItem(json)?.let(::add)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(items: List<TrackItem>) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        preferences.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun toJson(item: TrackItem): JSONObject = JSONObject().apply {
        put("uri", item.uri)
        put("displayName", item.displayName)
        put("title", item.title ?: JSONObject.NULL)
        put("artist", item.artist ?: JSONObject.NULL)
        put("album", item.album ?: JSONObject.NULL)
        put("durationMs", item.durationMs ?: JSONObject.NULL)
        put("mimeType", item.mimeType ?: JSONObject.NULL)
        put("sizeBytes", item.sizeBytes ?: JSONObject.NULL)
        put("formatName", item.formatName ?: JSONObject.NULL)
    }

    private fun parseItem(json: JSONObject): TrackItem? {
        val uri = json.optString("uri").takeIf { it.isNotBlank() } ?: return null
        return TrackItem(
            uri = uri,
            displayName = json.optString("displayName").ifBlank { "音频文件" },
            title = json.stringOrNull("title"),
            artist = json.stringOrNull("artist"),
            album = json.stringOrNull("album"),
            durationMs = if (json.has("durationMs") && !json.isNull("durationMs")) json.optLong("durationMs") else null,
            mimeType = json.stringOrNull("mimeType"),
            sizeBytes = if (json.has("sizeBytes") && !json.isNull("sizeBytes")) json.optLong("sizeBytes") else null,
            formatName = json.stringOrNull("formatName"),
        )
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private companion object {
        const val PREFS = "music_tag_library"
        const val KEY_ITEMS = "items"
    }
}
