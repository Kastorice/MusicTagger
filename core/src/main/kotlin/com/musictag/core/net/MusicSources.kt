package com.musictag.core.net

import com.musictag.core.net.netease.NeteaseSource
import com.musictag.core.net.qq.QqMusicSource

/**
 * Registry of online metadata providers.
 *
 * All providers share the real [Http] transport; the app calls them on
 * `Dispatchers.IO` because every operation is blocking.
 */
object MusicSources {
    /**
     * @param neteaseApiBase optional self-hosted NeteaseCloudMusicApi base URL
     *   (e.g. `http://192.168.1.10:3000`). When null the public endpoints are used.
     */
    fun create(neteaseApiBase: String? = null): List<MusicSource> = listOf(
        NeteaseSource(Http, neteaseApiBase),
        QqMusicSource(Http),
    )

    fun byId(id: MusicSourceId, neteaseApiBase: String? = null): MusicSource? =
        create(neteaseApiBase).firstOrNull { it.id == id }
}
