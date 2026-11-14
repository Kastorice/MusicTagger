package com.musictag.core.net.musicbrainz

import com.musictag.core.net.ArtworkPayload
import com.musictag.core.net.Http
import com.musictag.core.net.HttpTransport
import com.musictag.core.net.LyricsResult
import com.musictag.core.net.MusicSource
import com.musictag.core.net.MusicSourceId
import com.musictag.core.net.NetException
import com.musictag.core.net.RemoteSong
import com.musictag.core.net.arr
import com.musictag.core.net.asMap
import com.musictag.core.net.ensureSuccess
import com.musictag.core.net.long
import com.musictag.core.net.netCall
import com.musictag.core.net.obj
import com.musictag.core.net.parseJsonObjectBody
import com.musictag.core.net.str
import java.net.URLEncoder

/**
 * MusicBrainz metadata source.
 *
 * Search uses the public `ws/2/recording` endpoint (JSON). MusicBrainz has no
 * lyrics, so [fetchLyrics] always reports an empty result; cover art comes from
 * the Cover Art Archive using the first release MBID of each recording.
 *
 * The API asks clients to identify themselves and to send at most one request
 * per second; [search] throttles accordingly.
 */
class MusicBrainzSource(
    private val http: HttpTransport = Http,
) : MusicSource {
    override val id: MusicSourceId = MusicSourceId.MUSICBRAINZ

    private var lastRequestAt = 0L

    override fun search(keyword: String, limit: Int): List<RemoteSong> {
        if (limit <= 0 || keyword.isBlank()) return emptyList()
        throttle()
        val url = "$SEARCH_URL?query=${encode(keyword)}&fmt=json&limit=${limit.coerceIn(1, MAX_LIMIT)}"
        val response = getWithRetry(url).ensureSuccess()

        val root = parseJsonObjectBody(response.body)
        return root.arr("recordings").orEmpty().mapNotNull { item ->
            val recording = item.asMap() ?: return@mapNotNull null
            val recordingId = recording.str("id")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val title = recording.str("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val artists = artistNames(recording.arr("artist-credit"))
            val releases = recording.arr("releases").orEmpty().mapNotNull { it.asMap() }
            val release = releases.firstOrNull { it.str("status").equals("Official", ignoreCase = true) }
                ?: releases.firstOrNull()
            val album = release?.str("title")?.trim()?.takeIf { it.isNotEmpty() }
            val releaseId = release?.str("id")?.trim()?.takeIf { it.isNotEmpty() }
            RemoteSong(
                source = MusicSourceId.MUSICBRAINZ,
                id = recordingId,
                title = title,
                artists = artists,
                album = album,
                albumId = releaseId,
                coverUrl = releaseId?.let(::coverUrlFor),
                durationMs = recording.long("length")?.takeIf { it > 0 },
            )
        }.take(limit.coerceIn(1, MAX_LIMIT))
    }

    /** MusicBrainz is a metadata database and does not serve lyrics. */
    override fun fetchLyrics(song: RemoteSong): LyricsResult = LyricsResult()

    override fun fetchArtwork(song: RemoteSong): ArtworkPayload? {
        val releaseId = song.albumId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val bytes = try {
            netCall { http.download(coverUrlFor(releaseId), HEADERS) }
        } catch (_: NetException) {
            // Many releases have no cover art; the archive answers 404.
            return null
        }
        if (bytes.isEmpty()) return null
        val mimeType = Http.sniffImageMimeType(bytes) ?: return null
        return ArtworkPayload(bytes, mimeType)
    }

    private fun artistNames(credits: List<Any?>?): String {
        val names = credits.orEmpty().mapNotNull { credit ->
            val map = credit.asMap() ?: return@mapNotNull null
            (map.str("name") ?: map.obj("artist")?.str("name"))
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }
        return names.joinToString(" / ").ifBlank { "未知歌手" }
    }

    /**
     * MusicBrainz rate-limits aggressively and answers 503/429 when requests
     * arrive too fast. Retry twice with a growing delay before surfacing an
     * error, so a quick "search again" tap usually still works.
     */
    private fun getWithRetry(url: String): com.musictag.core.net.HttpResponse {
        var response = netCall { http.get(url, HEADERS) }
        var attempt = 1
        while (response.code == 429 || response.code == 503) {
            if (attempt > 2) {
                throw NetException("MusicBrainz 暂时限流，请稍后重试")
            }
            Thread.sleep(RETRY_DELAY_MS * attempt)
            throttle()
            response = netCall { http.get(url, HEADERS) }
            attempt++
        }
        return response
    }

    private fun coverUrlFor(releaseId: String): String = "$COVER_PREFIX$releaseId/front-500"

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    @Synchronized
    private fun throttle() {
        val now = System.currentTimeMillis()
        val wait = MIN_INTERVAL_MS - (now - lastRequestAt)
        if (wait > 0) Thread.sleep(wait)
        lastRequestAt = System.currentTimeMillis()
    }

    private companion object {
        const val MAX_LIMIT = 100
        const val MIN_INTERVAL_MS = 1_100L
        const val RETRY_DELAY_MS = 1_200L
        const val SEARCH_URL = "https://musicbrainz.org/ws/2/recording"
        const val COVER_PREFIX = "https://coverartarchive.org/release/"
        const val USER_AGENT = "MusicTagger/1.2.8 (https://github.com/Kastorice/MusicTagger)"
        val HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
        )
    }
}
