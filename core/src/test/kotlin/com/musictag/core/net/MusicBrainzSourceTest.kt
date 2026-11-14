package com.musictag.core.net

import com.musictag.core.net.musicbrainz.MusicBrainzSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzSourceTest {
    private val searchUrl = "https://musicbrainz.org/ws/2/recording?query=Jay&fmt=json&limit=20"
    private val coverUrl = "https://coverartarchive.org/release/rel-1/front-500"

    private val searchJson = """
        {
          "recordings": [
            {
              "id": "rec-1",
              "title": "晴天",
              "length": 269000,
              "artist-credit": [
                {"name": "周杰伦", "artist": {"id": "a1", "name": "周杰伦"}}
              ],
              "releases": [
                {"id": "rel-1", "title": "叶惠美", "status": "Official"}
              ]
            },
            {
              "id": "rec-2",
              "title": "No Release",
              "artist-credit": [
                {"name": "A", "joinphrase": " & ", "artist": {"id": "a2"}},
                {"name": "B", "artist": {"id": "a3"}}
              ],
              "releases": []
            }
          ]
        }
    """.trimIndent()

    @Test
    fun searchParsesRecordingsAndThrottlesThroughHeaders() {
        val http = FakeHttpTransport().apply { on(searchUrl, searchJson) }

        val songs = MusicBrainzSource(http).search("Jay")

        assertEquals(2, songs.size)
        val first = songs[0]
        assertEquals(MusicSourceId.MUSICBRAINZ, first.source)
        assertEquals("rec-1", first.id)
        assertEquals("晴天", first.title)
        assertEquals("周杰伦", first.artists)
        assertEquals("叶惠美", first.album)
        assertEquals("rel-1", first.albumId)
        assertEquals(coverUrl, first.coverUrl)
        assertEquals(269000L, first.durationMs)

        val second = songs[1]
        assertEquals("A / B", second.artists)
        assertNull(second.album)
        assertNull(second.coverUrl)
        assertNull(second.durationMs)

        val request = http.requests.single()
        assertTrue(request.headers["User-Agent"].orEmpty().contains("MusicTagger"))
        assertEquals("application/json", request.headers["Accept"])
    }

    @Test
    fun searchEmptyKeywordSkipsNetwork() {
        val http = FakeHttpTransport()
        assertTrue(MusicBrainzSource(http).search(" ", 5).isEmpty())
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun fetchLyricsIsAlwaysEmpty() {
        val song = RemoteSong(MusicSourceId.MUSICBRAINZ, "rec-1", "晴天", "周杰伦")
        assertTrue(MusicBrainzSource(FakeHttpTransport()).fetchLyrics(song).isEmpty)
    }

    @Test
    fun fetchArtworkDownloadsCoverArchiveImage() {
        val http = FakeHttpTransport().apply { onDownload(coverUrl, JPEG_BYTES) }
        val song = RemoteSong(
            MusicSourceId.MUSICBRAINZ,
            "rec-1",
            "晴天",
            "周杰伦",
            albumId = "rel-1",
        )

        val payload = MusicBrainzSource(http).fetchArtwork(song)

        assertEquals("image/jpeg", payload?.mimeType)
        assertEquals(JPEG_BYTES.size, payload?.bytes?.size)
    }

    @Test
    fun fetchArtworkMissingCoverReturnsNull() {
        val song = RemoteSong(MusicSourceId.MUSICBRAINZ, "rec-1", "晴天", "周杰伦")
        assertNull(MusicBrainzSource(FakeHttpTransport()).fetchArtwork(song))
    }
}
