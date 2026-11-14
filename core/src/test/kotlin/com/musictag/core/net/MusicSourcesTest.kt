package com.musictag.core.net

import com.musictag.core.net.musicbrainz.MusicBrainzSource
import com.musictag.core.net.netease.NeteaseSource
import com.musictag.core.net.qq.QqMusicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSourcesTest {
    @Test
    fun createReturnsNeteaseQqThenMusicBrainz() {
        val sources = MusicSources.create()

        assertEquals(3, sources.size)
        assertEquals(
            listOf(MusicSourceId.NETEASE, MusicSourceId.QQ, MusicSourceId.MUSICBRAINZ),
            sources.map { it.id },
        )
        assertTrue(sources[0] is NeteaseSource)
        assertTrue(sources[1] is QqMusicSource)
        assertTrue(sources[2] is MusicBrainzSource)
        assertEquals("网易云音乐", sources[0].id.displayName)
        assertEquals("QQ 音乐", sources[1].id.displayName)
        assertEquals("MusicBrainz", sources[2].id.displayName)
    }

    @Test
    fun byIdFindsProvider() {
        assertTrue(MusicSources.byId(MusicSourceId.NETEASE) is NeteaseSource)
        assertTrue(MusicSources.byId(MusicSourceId.QQ) is QqMusicSource)
        assertTrue(MusicSources.byId(MusicSourceId.MUSICBRAINZ) is MusicBrainzSource)
        assertEquals(MusicSourceId.NETEASE, MusicSources.byId(MusicSourceId.NETEASE)!!.id)
    }

    @Test
    fun byIdPassesCustomNeteaseBase() {
        val source = MusicSources.byId(MusicSourceId.NETEASE, "http://192.168.1.10:3000/") as NeteaseSource
        assertEquals("http://192.168.1.10:3000", source.base)
    }
}
