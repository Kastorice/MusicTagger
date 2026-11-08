package com.musictag.core.net

import com.musictag.core.tag.Artwork

/** Supported online metadata providers. */
enum class MusicSourceId(val displayName: String) {
    NETEASE("网易云音乐"),
    QQ("QQ 音乐"),
}

/** A search result (or a resolved song with album information). */
data class RemoteSong(
    val source: MusicSourceId,
    val id: String,
    val title: String,
    val artists: String,
    val album: String? = null,
    /** Provider specific album identifier used to build cover URLs (QQ: album mid). */
    val albumId: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
) {
    val subtitle: String
        get() = listOfNotNull(artists.takeIf { it.isNotBlank() }, album?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
}

/** Lyrics resolved from a provider. [lrc] may be null when the provider has no lyrics. */
data class LyricsResult(
    val lrc: String? = null,
    /** Optional translated LRC (currently only NetEase provides this). */
    val translation: String? = null,
) {
    val isEmpty: Boolean get() = lrc.isNullOrBlank() && translation.isNullOrBlank()
}

/** Raw cover bytes plus detected MIME type, ready to be embedded as a tag. */
data class ArtworkPayload(val bytes: ByteArray, val mimeType: String) {
    fun toArtwork(description: String = ""): Artwork = Artwork(bytes, mimeType, description)
}

/**
 * Online metadata provider. All calls are blocking; the app invokes them on
 * [kotlinx.coroutines.Dispatchers.IO] (or a plain worker thread).
 */
interface MusicSource {
    val id: MusicSourceId

    /** Searches songs by free text keyword. Throws [NetException] on failure. */
    fun search(keyword: String, limit: Int = 20): List<RemoteSong>

    /**
     * Fills in album / cover information for a search hit when the search
     * result did not contain it (QQ Music). Default: already resolved.
     */
    fun resolve(song: RemoteSong): RemoteSong = song

    /** Fetches lyrics (LRC text when available). Throws [NetException] on failure. */
    fun fetchLyrics(song: RemoteSong): LyricsResult

    /** Downloads the cover image for [song]. Returns null when there is no cover. */
    fun fetchArtwork(song: RemoteSong): ArtworkPayload?
}

/** Network failures reported to the UI with a user friendly message. */
class NetException(message: String, cause: Throwable? = null) : Exception(message, cause)
