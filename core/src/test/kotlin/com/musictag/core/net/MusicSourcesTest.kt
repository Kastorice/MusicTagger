package com.musictag.core.net

import com.musictag.core.net.netease.NeteaseSource
import com.musictag.core.net.qq.QqMusicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSourcesTest {
    @Test
    fun createReturnsNeteaseThenQq() {
        val sources = MusicSources.create()

        assertEquals(2, sources.size)
        assertEquals(listOf(MusicSourceId.NETEASE, MusicSourceId.QQ), sources.map { it.id })
        assertTrue(sources[0] is NeteaseSource)
        assertTrue(sources[1] is QqMusicSource)
        assertEquals("网易云音乐", sources[0].id.displayName)
        assertEquals("QQ 音乐", sources[1].id.displayName)
    }

    @Test
    fun byIdFindsProvider() {
        assertTrue(MusicSources.byId(MusicSourceId.NETEASE) is NeteaseSource)
        assertTrue(MusicSources.byId(MusicSourceId.QQ) is QqMusicSource)
        assertEquals(MusicSourceId.NETEASE, MusicSources.byId(MusicSourceId.NETEASE)!!.id)
    }

    @Test
    fun byIdPassesCustomNeteaseBase() {
        val source = MusicSources.byId(MusicSourceId.NETEASE, "http://192.168.1.10:3000/") as NeteaseSource
        assertEquals("http://192.168.1.10:3000", source.base)
    }
}
