package com.musictag.app.data

import android.content.Context
import com.musictag.app.data.model.AppSettings
import com.musictag.core.net.MusicSourceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences (theme + online providers). */
class SettingsStore(context: Context) {

    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings = AppSettings(
        dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, false),
        preferredSource = runCatching {
            MusicSourceId.valueOf(preferences.getString(KEY_SOURCE, MusicSourceId.NETEASE.name)!!)
        }.getOrDefault(MusicSourceId.NETEASE),
        neteaseApiBase = preferences.getString(KEY_NETEASE_BASE, "").orEmpty(),
    )

    fun setDynamicColor(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _settings.value = _settings.value.copy(dynamicColor = enabled)
    }

    fun setPreferredSource(source: MusicSourceId) {
        preferences.edit().putString(KEY_SOURCE, source.name).apply()
        _settings.value = _settings.value.copy(preferredSource = source)
    }

    fun setNeteaseApiBase(base: String) {
        val trimmed = base.trim()
        preferences.edit().putString(KEY_NETEASE_BASE, trimmed).apply()
        _settings.value = _settings.value.copy(neteaseApiBase = trimmed)
    }

    private companion object {
        const val PREFS = "music_tag_settings"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_SOURCE = "preferred_source"
        const val KEY_NETEASE_BASE = "netease_api_base"
    }
}
