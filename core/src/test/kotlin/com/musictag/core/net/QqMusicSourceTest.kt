package com.musictag.core.net

import com.musictag.core.net.qq.QqMusicSource
import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures were captured live on 2026-11-01 from c.y.qq.com / y.gtimg.cn
 * (trimmed to the fields the parser uses, lyrics truncated to a few lines).
 */
class QqMusicSourceTest {
    private val mid = "004fZneN2IToAD"
    private val albumMid = "003wsfw93THtMj"
    private val coverUrl = "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"

    private val searchUrl =
        "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=Jay&format=json&g_tk=5381"
    private val detailUrl =
        "https://c.y.qq.com/v8/fcg-bin/fcg_play_single_song.fcg?songmid=$mid&tpl=yqq_song_detail&format=json"
    private val lyricUrl =
        "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=1&g_tk=5381"

    private fun hit() = RemoteSong(
        source = MusicSourceId.QQ,
        id = mid,
        title = "Infinity",
        artists = "Jaymes Young",
    )

    @Test
    fun searchParsesRecordedFixtureExactly() {
        val http = FakeHttpTransport().apply { on(searchUrl, Fixtures.read("qq_smartbox.json")) }

        val songs = QqMusicSource(http).search("Jay")

        assertEquals(3, songs.size)
        val first = songs[0]
        assertEquals(MusicSourceId.QQ, first.source)
        assertEquals("004fZneN2IToAD", first.id)
        assertEquals("Infinity", first.title)
        assertEquals("Jaymes Young", first.artists)
        assertNull(first.album)
        assertNull(first.albumId)
        assertNull(first.coverUrl)
        assertNull(first.durationMs)

        val second = songs[1]
        assertEquals("004dPfPq4VgCmz", second.id)
        assertEquals("莫扎特钢琴曲k448", second.title)
        assertEquals("jaycd", second.artists)

        val request = http.requests.single()
        assertEquals("GET", request.method)
        assertEquals(searchUrl, request.url)
        assertEquals("https://y.qq.com/", request.headers["Referer"])
    }

    @Test
    fun searchRespectsLimit() {
        val http = FakeHttpTransport().apply { on(searchUrl, Fixtures.read("qq_smartbox.json")) }
        assertEquals(2, QqMusicSource(http).search("Jay", 2).size)
    }

    @Test
    fun searchReturnsEmptyWithoutSongs() {
        val noCategory = FakeHttpTransport().apply { on(searchUrl, """{"code":0,"data":{}}""") }
        assertTrue(QqMusicSource(noCategory).search("Jay").isEmpty())

        val emptyList = FakeHttpTransport().apply {
            on(searchUrl, """{"code":0,"data":{"song":{"itemlist":[]}}}""")
        }
        assertTrue(QqMusicSource(emptyList).search("Jay").isEmpty())

        val noRequest = FakeHttpTransport()
        assertTrue(QqMusicSource(noRequest).search("Jay", 0).isEmpty())
        assertTrue(noRequest.requests.isEmpty())
    }

    @Test
    fun searchPercentEncodesUtf8Keyword() {
        val encoded =
            "https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?key=%E5%91%A8%E6%9D%B0%E4%BC%A6&format=json&g_tk=5381"
        val http = FakeHttpTransport().apply {
            on(encoded, """{"code":0,"data":{"song":{"itemlist":[]}}}""")
        }
        QqMusicSource(http).search("周杰伦")
        assertEquals(encoded, http.lastRequest().url)
    }

    @Test
    fun searchThrowsOnHttpError() {
        val http = FakeHttpTransport().apply { on(searchUrl, "", 500) }
        val error = assertThrows(NetException::class.java) { QqMusicSource(http).search("Jay") }
        assertEquals("请求失败（HTTP 500）", error.message)
    }

    @Test
    fun searchThrowsOnMalformedJson() {
        val http = FakeHttpTransport().apply { on(searchUrl, "MusicJsonCallback(oops)") }
        val error = assertThrows(NetException::class.java) { QqMusicSource(http).search("Jay") }
        assertEquals("响应格式异常", error.message)
    }

    @Test
    fun searchMapsLeakedIoFailures() {
        val http = FakeHttpTransport().apply { failure = IOException("timeout") }
        val error = assertThrows(NetException::class.java) { QqMusicSource(http).search("Jay") }
        assertEquals("网络连接失败，请检查网络设置", error.message)
    }

    @Test
    fun resolveParsesRecordedFixtureExactly() {
        val http = FakeHttpTransport().apply {
            on(detailUrl, Fixtures.read("qq_detail.json"))
        }

        val resolved = QqMusicSource(http).resolve(hit())

        assertEquals(mid, resolved.id)
        assertEquals("Infinity", resolved.title)
        assertEquals("Jaymes Young", resolved.artists)
        assertEquals("Feel Something", resolved.album)
        assertEquals(albumMid, resolved.albumId)
        assertEquals(coverUrl, resolved.coverUrl)
        assertEquals(237000L, resolved.durationMs)
        assertEquals(detailUrl, http.requests.single().url)
    }

    @Test
    fun resolveFallsBackSensiblyAndIgnoresZeroInterval() {
        val body = """
            {"code":0,"data":[{"name":"Name Only","singer":[{"name":"S1"},{"name":"S2"}],"interval":0}]}
        """.trimIndent()
        val http = FakeHttpTransport().apply { on(detailUrl, body) }
        val original = RemoteSong(
            MusicSourceId.QQ, mid, "Original", "Original Artist",
            album = "Old Album", durationMs = 5000L,
        )

        val resolved = QqMusicSource(http).resolve(original)

        assertEquals("Name Only", resolved.title)
        assertEquals("S1 / S2", resolved.artists)
        assertEquals("Old Album", resolved.album)
        assertEquals(5000L, resolved.durationMs)
        assertNull(resolved.albumId)
        assertNull(resolved.coverUrl)
    }

    @Test
    fun resolveThrowsWhenDataIsMissing() {
        listOf("""{"code":0,"data":[]}""", """{"code":0}""").forEach { body ->
            val http = FakeHttpTransport().apply { on(detailUrl, body) }
            val error = assertThrows(NetException::class.java) { QqMusicSource(http).resolve(hit()) }
            assertEquals("未找到歌曲详情", error.message)
        }
    }

    @Test
    fun lyricsParseRecordedFixture() {
        val http = FakeHttpTransport().apply {
            on(lyricUrl, Fixtures.read("qq_lyric.json"))
        }

        val lyrics = QqMusicSource(http).fetchLyrics(hit())

        assertNotNull(lyrics.lrc)
        assertNull(lyrics.translation)
        assertEquals(11, lyrics.lrc!!.lines().size)
        assertTrue(lyrics.lrc!!.startsWith("[ti:Infinity]"))
        assertTrue(lyrics.lrc!!.contains("[00:00.50]Infinity (无穷无尽) - Jaymes Young"))
        assertTrue(lyrics.lrc!!.contains("[00:15.03]Baby this love I'll never let it die"))
        assertEquals(lyricUrl, http.requests.single().url)
    }

    @Test
    fun lyricsDecodeHtmlEntitiesAndBrTags() {
        val body = """
            {"retcode":0,"lyric":"[00:01.00]Hello&#10;[00:02.00]World &amp; &lt;x&gt;<br>[00:03.00]Last","trans":""}
        """.trimIndent()
        val http = FakeHttpTransport().apply { on(lyricUrl, body) }

        val lyrics = QqMusicSource(http).fetchLyrics(hit())

        assertEquals("[00:01.00]Hello\n[00:02.00]World & <x>\n[00:03.00]Last", lyrics.lrc)
    }

    @Test
    fun lyricsDecodeBase64WhenNobase64WasIgnored() {
        val encoded = Base64.getEncoder().encodeToString("[00:01.00]Secret".toByteArray(Charsets.UTF_8))
        val http = FakeHttpTransport().apply {
            on(lyricUrl, """{"retcode":0,"lyric":"$encoded","trans":""}""")
        }

        assertEquals("[00:01.00]Secret", QqMusicSource(http).fetchLyrics(hit()).lrc)
    }

    @Test
    fun lyricsEmptyWhenProviderHasNoLyric() {
        val http = FakeHttpTransport().apply {
            on(lyricUrl, """{"retcode":0,"lyric":"","trans":""}""")
        }
        val lyrics = QqMusicSource(http).fetchLyrics(hit())
        assertTrue(lyrics.isEmpty)
        assertNull(lyrics.lrc)
        assertNull(lyrics.translation)
    }

    @Test
    fun artworkResolvesSongThenDownloadsCover() {
        val http = FakeHttpTransport().apply {
            on(detailUrl, Fixtures.read("qq_detail.json"))
            onDownload(coverUrl, PNG_BYTES)
        }

        val payload = QqMusicSource(http).fetchArtwork(hit())!!

        assertEquals("image/png", payload.mimeType)
        assertArrayEquals(PNG_BYTES, payload.bytes)
        assertEquals(detailUrl, http.requests[0].url)
        assertEquals(coverUrl, http.requests[1].url)
    }

    @Test
    fun artworkUsesAlbumIdWithoutResolvingAgain() {
        val http = FakeHttpTransport().apply { onDownload(coverUrl, JPEG_BYTES) }
        val song = hit().copy(albumId = albumMid)

        val payload = QqMusicSource(http).fetchArtwork(song)!!

        assertEquals("image/jpeg", payload.mimeType)
        assertEquals(1, http.requests.size)
        assertEquals(coverUrl, http.requests[0].url)
    }

    @Test
    fun artworkReturnsNullWhenAlbumMidIsMissing() {
        val http = FakeHttpTransport().apply {
            on(detailUrl, """{"code":0,"data":[{"title":"No Album","name":"No Album"}]}""")
        }
        assertNull(QqMusicSource(http).fetchArtwork(hit()))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun artworkRejectsNonImagePayload() {
        val http = FakeHttpTransport().apply {
            onDownload(coverUrl, "not an image".toByteArray())
        }
        val error = assertThrows(NetException::class.java) {
            QqMusicSource(http).fetchArtwork(hit().copy(albumId = albumMid))
        }
        assertEquals("封面格式不支持", error.message)
    }
}
