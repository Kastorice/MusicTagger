package com.musictag.core.net

import com.musictag.core.net.netease.NeteaseSource
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures were captured live on 2026-11-01 from music.163.com (trimmed to the
 * fields the parser uses, lyrics truncated to a few lines).
 */
class NeteaseSourceTest {
    private val searchUrl = "https://music.163.com/api/cloudsearch/pc"

    private fun lyricUrl(id: String) = "https://music.163.com/api/song/lyric?os=pc&id=$id&lv=-1&kv=-1&tv=-1"

    private fun detailUrl(id: String) = "https://music.163.com/api/song/detail?ids=%5B$id%5D"

    private fun song(
        id: String = "451703096",
        coverUrl: String? = null,
    ) = RemoteSong(MusicSourceId.NETEASE, id, "title", "artist", coverUrl = coverUrl)

    @Test
    fun searchParsesRecordedFixtureExactly() {
        val http = FakeHttpTransport().apply {
            on(searchUrl, Fixtures.read("netease_search.json"))
        }

        val songs = NeteaseSource(http).search("Jay")

        assertEquals(3, songs.size)
        val first = songs[0]
        assertEquals(MusicSourceId.NETEASE, first.source)
        assertEquals("509781655", first.id)
        assertEquals("想你就写信 (Live)", first.title)
        assertEquals("周杰伦 / 李硕 / 张鑫", first.artists)
        assertEquals("中国新歌声第二季 第13期", first.album)
        assertEquals(238698L, first.durationMs)
        // Fixtures contain the http:// CDN URL NetEase actually returns; we upgrade to https.
        assertEquals(
            "https://p1.music.126.net/yD9vbpuILH-tqNRIaP640g==/109951163038292176.jpg",
            first.coverUrl,
        )

        val second = songs[1]
        assertEquals("5257138", second.id)
        assertEquals("屋顶", second.title)
        assertEquals("周杰伦 / 温岚 / 吴宗宪", second.artists)
        assertEquals("男女情歌对唱冠军全记录", second.album)
        assertEquals(319039L, second.durationMs)

        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals(searchUrl, request.url)
        assertEquals("Jay", request.form["s"])
        assertEquals("1", request.form["type"])
        assertEquals("20", request.form["limit"])
        assertEquals("0", request.form["offset"])
        assertEquals("https://music.163.com/", request.headers["Referer"])
        assertEquals("appver=2.0.2", request.headers["Cookie"])
    }

    @Test
    fun searchAcceptsLegacyArtistsAndAlbumShape() {
        val body = """
            {"code":200,"result":{"songs":[{
              "id":77,"name":"Legacy Song",
              "artists":[{"name":"Singer A"},{"name":"Singer B"}],
              "album":{"name":"Legacy Album","picUrl":"http://p2.music.126.net/legacy.jpg"},
              "duration":123000
            }]}}
        """.trimIndent()
        val http = FakeHttpTransport().apply { on(searchUrl, body) }

        val song = NeteaseSource(http).search("legacy").single()

        assertEquals("77", song.id)
        assertEquals("Legacy Song", song.title)
        assertEquals("Singer A / Singer B", song.artists)
        assertEquals("Legacy Album", song.album)
        assertEquals("https://p2.music.126.net/legacy.jpg", song.coverUrl)
        assertEquals(123000L, song.durationMs)
    }

    @Test
    fun searchReturnsEmptyListForMissingOrEmptyResult() {
        val missing = FakeHttpTransport().apply { on(searchUrl, """{"code":200}""") }
        assertTrue(NeteaseSource(missing).search("nothing").isEmpty())

        val empty = FakeHttpTransport().apply { on(searchUrl, """{"code":200,"result":{"songs":[]}}""") }
        assertTrue(NeteaseSource(empty).search("nothing").isEmpty())
    }

    @Test
    fun searchThrowsOnHttpError() {
        val http = FakeHttpTransport().apply { on(searchUrl, "", 500) }
        val error = assertThrows(NetException::class.java) { NeteaseSource(http).search("Jay") }
        assertEquals("请求失败（HTTP 500）", error.message)
    }

    @Test
    fun searchThrowsOnMalformedJson() {
        val http = FakeHttpTransport().apply { on(searchUrl, "<html>not json</html>") }
        val error = assertThrows(NetException::class.java) { NeteaseSource(http).search("Jay") }
        assertEquals("响应格式异常", error.message)
    }

    @Test
    fun searchMapsLeakedIoFailures() {
        val http = FakeHttpTransport().apply { failure = IOException("connection reset") }
        val error = assertThrows(NetException::class.java) { NeteaseSource(http).search("Jay") }
        assertEquals("网络连接失败，请检查网络设置", error.message)
    }

    @Test
    fun lyricsParseRecordedFixtureWithTranslation() {
        val http = FakeHttpTransport().apply {
            on(lyricUrl("451703096"), Fixtures.read("netease_lyric.json"))
        }

        val lyrics = NeteaseSource(http).fetchLyrics(song("451703096"))

        assertNotNull(lyrics.lrc)
        assertNotNull(lyrics.translation)
        assertEquals(9, lyrics.lrc!!.lines().size)
        assertEquals(8, lyrics.translation!!.lines().size)
        assertTrue(lyrics.lrc!!.startsWith("[00:00.000] 制作人 : Steve Mac"))
        assertTrue(lyrics.lrc!!.contains("[00:09.480]The club isn't the best place to find a lover"))
        assertTrue(lyrics.translation!!.startsWith("[00:09.480]这俱乐部不是个能找到安慰的地方"))
        assertTrue(lyrics.translation!!.contains("[00:11.750]所以我们去往酒吧"))

        assertEquals("GET", http.requests.single().method)
        assertEquals(lyricUrl("451703096"), http.requests.single().url)
        assertEquals("https://music.163.com/", http.requests.single().headers["Referer"])
    }

    @Test
    fun lyricsWithoutTranslationOrLyrics() {
        val withLrc = FakeHttpTransport().apply {
            on(lyricUrl("42"), """{"code":200,"lrc":{"lyric":"[00:01.00]Hi"}}""")
        }
        val onlyOriginal = NeteaseSource(withLrc).fetchLyrics(song("42"))
        assertEquals("[00:01.00]Hi", onlyOriginal.lrc)
        assertNull(onlyOriginal.translation)

        val noLyric = FakeHttpTransport().apply {
            on(lyricUrl("43"), """{"code":200,"nolyric":true}""")
        }
        val empty = NeteaseSource(noLyric).fetchLyrics(song("43"))
        assertTrue(empty.isEmpty)
        assertNull(empty.lrc)
    }

    @Test
    fun artworkDownloadsSearchCoverOverHttps() {
        val cover = "http://p1.music.126.net/cover.jpg"
        val httpsCover = "https://p1.music.126.net/cover.jpg"
        val http = FakeHttpTransport().apply { onDownload(httpsCover, PNG_BYTES) }

        val payload = NeteaseSource(http).fetchArtwork(song(coverUrl = cover))!!

        assertEquals("image/png", payload.mimeType)
        assertArrayEquals(PNG_BYTES, payload.bytes)
        assertEquals("https://p1.music.126.net/cover.jpg", http.lastRequest().url)
        assertEquals("image/png", payload.toArtwork("cover").mimeType)
    }

    @Test
    fun artworkFallsBackToSongDetail() {
        val detailPic = "https://p2.music.126.net/yD9vbpuILH-tqNRIaP640g==/109951163038292176.jpg"
        val http = FakeHttpTransport().apply {
            on(detailUrl("509781655"), Fixtures.read("netease_detail.json"))
            onDownload(detailPic, JPEG_BYTES)
        }

        val payload = NeteaseSource(http).fetchArtwork(song("509781655"))!!

        assertEquals("image/jpeg", payload.mimeType)
        assertArrayEquals(JPEG_BYTES, payload.bytes)
        assertEquals("GET", http.requests[0].method)
        assertEquals(detailUrl("509781655"), http.requests[0].url)
        assertEquals(detailPic, http.requests[1].url)
    }

    @Test
    fun artworkReturnsNullWithoutAnyCover() {
        val http = FakeHttpTransport().apply {
            on(detailUrl("1"), """{"code":200,"songs":[]}""")
        }
        assertNull(NeteaseSource(http).fetchArtwork(song("1")))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun artworkRejectsNonImagePayload() {
        val http = FakeHttpTransport().apply {
            onDownload("https://p1.music.126.net/cover.jpg", "<html>error</html>".toByteArray())
        }
        val error = assertThrows(NetException::class.java) {
            NeteaseSource(http).fetchArtwork(song(coverUrl = "https://p1.music.126.net/cover.jpg"))
        }
        assertEquals("封面格式不支持", error.message)
    }

    @Test
    fun artworkReturnsNullForEmptyPayload() {
        val http = FakeHttpTransport().apply {
            onDownload("https://p1.music.126.net/cover.jpg", ByteArray(0))
        }
        assertNull(NeteaseSource(http).fetchArtwork(song(coverUrl = "https://p1.music.126.net/cover.jpg")))
    }

    @Test
    fun customApiBaseConstructsExactUrls() {
        val detailPic = "https://p2.music.126.net/yD9vbpuILH-tqNRIaP640g==/109951163038292176.jpg"
        val http = FakeHttpTransport().apply {
            on("http://192.168.1.10:3000/search?keywords=Jay&limit=5&type=1", Fixtures.read("netease_search.json"))
            on("http://192.168.1.10:3000/lyric?id=451703096", Fixtures.read("netease_lyric.json"))
            on("http://192.168.1.10:3000/song/detail?ids=509781655", Fixtures.read("netease_detail.json"))
            onDownload(detailPic, PNG_BYTES)
        }
        val source = NeteaseSource(http, "http://192.168.1.10:3000/")
        assertEquals("http://192.168.1.10:3000", source.base)

        assertEquals(3, source.search("Jay", 5).size)
        assertEquals("http://192.168.1.10:3000/search?keywords=Jay&limit=5&type=1", http.requests[0].url)
        assertEquals("GET", http.requests[0].method)

        val lyrics = source.fetchLyrics(song("451703096"))
        assertNotNull(lyrics.translation)
        assertEquals("http://192.168.1.10:3000/lyric?id=451703096", http.requests[1].url)

        val payload = source.fetchArtwork(song("509781655"))!!
        assertEquals("image/png", payload.mimeType)
        assertEquals("http://192.168.1.10:3000/song/detail?ids=509781655", http.requests[2].url)
        assertEquals(detailPic, http.requests[3].url)
    }

    @Test
    fun customApiBasePercentEncodesUtf8Keyword() {
        val encoded = "http://192.168.1.10:3000/search?keywords=%E5%91%A8%E6%9D%B0%E4%BC%A6&limit=1&type=1"
        val http = FakeHttpTransport().apply {
            on(encoded, """{"code":200,"result":{"songs":[]}}""")
        }
        NeteaseSource(http, "http://192.168.1.10:3000").search("周杰伦", 1)
        assertEquals(encoded, http.lastRequest().url)
    }
}
