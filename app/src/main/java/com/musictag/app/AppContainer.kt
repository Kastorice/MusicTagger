package com.musictag.app

import android.content.Context
import com.musictag.app.data.ArtworkRepository
import com.musictag.app.data.LibraryStore
import com.musictag.app.data.SettingsStore
import com.musictag.app.data.TrackFileStore
import com.musictag.app.data.TrackRepository
import com.musictag.core.net.MusicSource
import com.musictag.core.net.MusicSources

/**
 * Manual dependency container (no DI framework needed for an app this size).
 * Created once by [MusicTagApplication].
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settingsStore: SettingsStore = SettingsStore(appContext)
    val artworkRepository: ArtworkRepository = ArtworkRepository(appContext)
    val fileStore: TrackFileStore = TrackFileStore(appContext)

    private val libraryStore: LibraryStore = LibraryStore(appContext)

    val trackRepository: TrackRepository =
        TrackRepository(appContext, fileStore, libraryStore, artworkRepository)

    @Volatile
    private var cachedSources: List<MusicSource> = emptyList()

    @Volatile
    private var cachedNeteaseBase: String? = null

    /** Online providers, rebuilt when the NetEase API base changes. */
    fun musicSources(): List<MusicSource> {
        val base = settingsStore.settings.value.neteaseApiBase.trim().ifBlank { null }
        if (cachedSources.isEmpty() || cachedNeteaseBase != base) {
            cachedSources = MusicSources.create(base)
            cachedNeteaseBase = base
        }
        return cachedSources
    }

    fun pruneEditCache() {
        try {
            fileStore.pruneCache()
        } catch (_: Exception) {
        }
    }
}
