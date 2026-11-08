package com.musictag.core.net.qq

import com.musictag.core.net.ArtworkPayload
import com.musictag.core.net.Http
import com.musictag.core.net.HttpTransport
import com.musictag.core.net.Lrc
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
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

/**
 * QQ Music metadata source.
 *
 * Search uses the `smartbox_new.fcg` suggestion endpoint (the classic
 * `client_search_cp` endpoint answers HTTP 500 from some networks), then
 * [resolve] fetches the album mid + duration from `fcg_play_single_song.fcg`.
 * Search hits intentionally carry no album/cover until resolved.
 */
class QqMusicSource(
    private val http: HttpTransport = Http,
) : MusicSource {
    override val id: MusicSourceId = MusicSourceId.QQ

    override fun search(keyword: String, limit: Int): List<RemoteSong> {
        if (limit <= 0) return emptyList()
        val response = netCall {
            http.get("$SEARCH_URL?key=${encode(keyword)}&format=json&g_tk=5381", HEADERS)
        }.ensureSuccess()

        val root = parseJsonObjectBody(response.body)
        val items = root.obj("data")?.obj("song")?.arr("itemlist") ?: return emptyList()
        return items.mapNotNull { item ->
            val song = item.asMap() ?: return@mapNotNull null
            val mid = song.str("mid")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = song.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val singer = song.str("singer")?.trim().orEmpty()
            RemoteSong(source = MusicSourceId.QQ, id = mid, title = title, artists = singer)
        }.take(limit.coerceIn(1, MAX_LIMIT))
    }

    override fun resolve(song: RemoteSong): RemoteSong {
        val response = netCall {
            http.get(
                "$DETAIL_URL?songmid=${encode(song.id)}&tpl=yqq_song_detail&format=json",
                HEADERS,
            )
        }.ensureSuccess()

        val root = parseJsonObjectBody(response.body)
        val item = root.arr("data")?.firstOrNull()?.asMap()
            ?: throw NetException("未找到歌曲详情")

        val title = item.str("title")?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.str("name")?.trim()?.takeIf { it.isNotEmpty() }
            ?: song.title
        val singers = namesOf(item.arr("singer"))
        val artists = singers.joinToString(" / ").takeIf { it.isNotEmpty() } ?: song.artists
        val albumObject = item.obj("album")
        val album = albumObject?.str("name")?.trim()?.takeIf { it.isNotEmpty() }
        val albumMid = albumObject?.str("mid")?.trim()?.takeIf { it.isNotEmpty() }
        val intervalSeconds = item.long("interval")
        val durationMs = intervalSeconds?.takeIf { it > 0 }?.times(1_000L)

        return song.copy(
            title = title,
            artists = artists,
            album = album ?: song.album,
            albumId = albumMid ?: song.albumId,
            coverUrl = albumMid?.let { coverUrlFor(it) } ?: song.coverUrl,
            durationMs = durationMs ?: song.durationMs,
        )
    }

    override fun fetchLyrics(song: RemoteSong): LyricsResult {
        val response = netCall {
            http.get(
                "$LYRIC_URL?songmid=${encode(song.id)}&format=json&nobase64=1&g_tk=5381",
                HEADERS,
            )
        }.ensureSuccess()

        val root = parseJsonObjectBody(response.body)
        val lrc = normalizeLyricText(root.str("lyric"))?.takeIf { it.isNotEmpty() }
        val translation = normalizeLyricText(root.str("trans"))?.takeIf { it.isNotEmpty() }
        return LyricsResult(lrc, translation)
    }

    override fun fetchArtwork(song: RemoteSong): ArtworkPayload? {
        val resolved = if (song.albumId.isNullOrBlank() && song.coverUrl.isNullOrBlank()) {
            resolve(song)
        } else {
            song
        }
        val cover = resolved.coverUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: resolved.albumId?.trim()?.takeIf { it.isNotEmpty() }?.let { coverUrlFor(it) }
            ?: return null
        val bytes = netCall { http.download(Http.httpsUrl(cover), HEADERS) }
        if (bytes.isEmpty()) return null
        val mimeType = Http.sniffImageMimeType(bytes) ?: throw NetException("封面格式不支持")
        return ArtworkPayload(bytes, mimeType)
    }

    private fun coverUrlFor(albumMid: String): String = "$COVER_PREFIX$albumMid.jpg"

    private fun namesOf(list: List<Any?>?): List<String> = list.orEmpty().mapNotNull { item ->
        val name = when (item) {
            is String -> item
            else -> item.asMap()?.str("name")
        }?.trim()
        name?.takeIf { it.isNotEmpty() }
    }

    /**
     * Normalizes a lyric/translation field: when `nobase64=1` was ignored the
     * field arrives Base64 encoded, and legacy responses embed HTML entities
     * (`&#10;`, `&amp;`) plus `<br>` tags instead of newlines.
     */
    private fun normalizeLyricText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = decodeMaybeBase64(raw.trim())
        return decodeHtmlEntities(text).trim()
    }

    private fun decodeMaybeBase64(text: String): String {
        if (text.indexOf('[') >= 0) return text
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.length < 16) return text
        if (compact.any { it !in BASE64_CHARS }) return text
        val decoded = decodeBase64(compact) ?: return text
        return if (Lrc.isLrc(decoded)) decoded else text
    }

    private fun decodeHtmlEntities(text: String): String {
        var result = text
        result = result.replace(Regex("(?i)<br\\s*/?>"), "\n")
        result = NUMERIC_ENTITY.replace(result) { match ->
            val raw = match.groupValues[1]
            val code = if (raw.startsWith("x") || raw.startsWith("X")) {
                raw.substring(1).toIntOrNull(16)
            } else {
                raw.toIntOrNull()
            }
            if (code == null || code <= 0 || code > Character.MAX_CODE_POINT) {
                match.value
            } else {
                String(Character.toChars(code))
            }
        }
        result = result
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        return result.replace("&amp;", "&")
    }

    /** Dependency free RFC 4648 Base64 decoder (no `java.util.Base64` need). */
    private fun decodeBase64(compact: String): String? {
        val output = ByteArrayOutputStream(compact.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in compact) {
            if (c == '=') continue
            val value = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xFF)
            }
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val MAX_LIMIT = 100
        const val SEARCH_URL = "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg"
        const val DETAIL_URL = "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg"
        const val LYRIC_URL = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
        const val COVER_PREFIX = "https://y.gtimg.cn/music/photo_new/T002R500x500M000"
        val HEADERS = mapOf("Referer" to "https://y.qq.com/")
        val NUMERIC_ENTITY = Regex("""&#(x?[0-9A-Fa-f]+);""")
        val BASE64_CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('+', '/', '=')
    }
}
