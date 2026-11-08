package com.musictag.core.net.netease

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
 * NetEase Cloud Music metadata source.
 *
 * When [apiBase] is set the source talks to a self-hosted
 * [NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi)
 * instance (`GET /search`, `GET /lyric`, `GET /song/detail`); otherwise the
 * public `music.163.com` endpoints are used. Both return the same JSON shapes.
 */
class NeteaseSource(
    private val http: HttpTransport = Http,
    private val apiBase: String? = null,
) : MusicSource {
    override val id: MusicSourceId = MusicSourceId.NETEASE

    /** Normalized custom API base: no trailing slashes, `null` when not configured. */
    internal val base: String? = apiBase?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    override fun search(keyword: String, limit: Int): List<RemoteSong> {
        val safeLimit = limit.coerceIn(1, MAX_LIMIT)
        val response = netCall {
            if (base != null) {
                http.get("$base/search?keywords=${encode(keyword)}&limit=$safeLimit&type=1")
            } else {
                http.postForm(
                    SEARCH_URL,
                    linkedMapOf(
                        "s" to keyword,
                        "type" to "1",
                        "limit" to safeLimit.toString(),
                        "offset" to "0",
                    ),
                    PUBLIC_HEADERS,
                )
            }
        }.ensureSuccess()

        val root = parseObject(response.body)
        val result = root.obj("result")
        val songs = result?.arr("songs") ?: root.arr("songs") ?: return emptyList()
        return songs.mapNotNull { song -> song.asMap()?.toSong() }
    }

    override fun fetchLyrics(song: RemoteSong): LyricsResult {
        val response = netCall {
            if (base != null) {
                http.get("$base/lyric?id=${encode(song.id)}")
            } else {
                http.get("$LYRIC_URL?os=pc&id=${encode(song.id)}&lv=-1&kv=-1&tv=-1", PUBLIC_HEADERS)
            }
        }.ensureSuccess()

        val root = parseObject(response.body)
        val lrc = root.obj("lrc")?.str("lyric")?.trim()?.takeIf { it.isNotEmpty() }
        val translation = root.obj("tlyric")?.str("lyric")?.trim()?.takeIf { it.isNotEmpty() }
        return LyricsResult(lrc, translation)
    }

    override fun fetchArtwork(song: RemoteSong): ArtworkPayload? {
        val cover = song.coverUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: fetchDetailCoverUrl(song.id)
            ?: return null
        val url = Http.httpsUrl(cover)
        val bytes = netCall {
            if (base != null) http.download(url) else http.download(url, PUBLIC_HEADERS)
        }
        if (bytes.isEmpty()) return null
        val mimeType = Http.sniffImageMimeType(bytes) ?: throw NetException("封面格式不支持")
        return ArtworkPayload(bytes, mimeType)
    }

    /** Legacy `/api/song/detail` lookup used when search omitted `al.picUrl`. */
    private fun fetchDetailCoverUrl(id: String): String? {
        val response = netCall {
            if (base != null) {
                http.get("$base/song/detail?ids=${encode(id)}")
            } else {
                http.get("$DETAIL_URL?ids=%5B${encode(id)}%5D", PUBLIC_HEADERS)
            }
        }.ensureSuccess()
        val root = parseObject(response.body)
        return root.arr("songs")
            ?.firstOrNull()
            ?.asMap()
            ?.obj("album")
            ?.str("picUrl")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun Map<String, Any?>.toSong(): RemoteSong? {
        val id = long("id")?.toString()
            ?: str("id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val title = str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val albumObject = obj("al") ?: obj("album")
        val artists = namesOf(arr("ar"))
            .ifEmpty { namesOf(arr("artists")) }
            .ifEmpty { namesOf(arr("singer")) }
            .joinToString(" / ")
        val album = albumObject?.str("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: str("album")?.trim()?.takeIf { it.isNotEmpty() }
        val albumId = albumObject?.long("id")?.toString()
        val coverUrl = (albumObject?.str("picUrl") ?: obj("al")?.str("picUrl") ?: obj("album")?.str("picUrl"))
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Http::httpsUrl)
        val durationMs = long("dt") ?: long("duration")

        return RemoteSong(
            source = MusicSourceId.NETEASE,
            id = id,
            title = title,
            artists = artists,
            album = album,
            albumId = albumId,
            coverUrl = coverUrl,
            durationMs = durationMs,
        )
    }

    private fun namesOf(list: List<Any?>?): List<String> = list.orEmpty().mapNotNull { item ->
        val name = when (item) {
            is String -> item
            else -> item.asMap()?.str("name")
        }?.trim()
        name?.takeIf { it.isNotEmpty() }
    }

    /**
     * Parses a JSON object response, tolerating a JSONP wrapper (some legacy
     * endpoints answer `callback({...})` even when JSON was requested).
     */
    private fun parseObject(body: String): Map<String, Any?> = parseJsonObjectBody(body)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val MAX_LIMIT = 100
        const val SEARCH_URL = "https://music.163.com/api/cloudsearch/pc"
        const val LYRIC_URL = "https://music.163.com/api/song/lyric"
        const val DETAIL_URL = "https://music.163.com/api/song/detail"
        val PUBLIC_HEADERS = mapOf(
            "Referer" to "https://music.163.com/",
            "Cookie" to "appver=2.0.2",
        )
    }
}
