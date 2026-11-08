package com.musictag.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpTest {
    @Test
    fun rejectsNonHttpUrls() {
        listOf("ftp://example.com/x", "file:///c:/x.mp3", "mailto:someone@example.com", "example.com/x")
            .forEach { url ->
                val getError = assertThrows(NetException::class.java) { Http.get(url) }
                assertEquals("链接格式错误", getError.message)

                val downloadError = assertThrows(NetException::class.java) { Http.download(url) }
                assertEquals("链接格式错误", downloadError.message)
            }

        val postError = assertThrows(NetException::class.java) {
            Http.postForm("ftp://example.com/form", emptyMap())
        }
        assertEquals("链接格式错误", postError.message)
    }

    @Test
    fun upgradesHttpCoverUrlsToHttps() {
        assertEquals("https://p1.music.126.net/x.jpg", Http.httpsUrl("http://p1.music.126.net/x.jpg"))
        assertEquals("https://x", Http.httpsUrl("HTTP://x"))
        assertEquals("https://x", Http.httpsUrl("  https://x  "))
        assertEquals("", Http.httpsUrl(""))
    }

    @Test
    fun sniffsSupportedImageFormats() {
        assertEquals("image/jpeg", Http.sniffImageMimeType(JPEG_BYTES))
        assertEquals("image/jpeg", Http.sniffImageMimeType(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertEquals("image/png", Http.sniffImageMimeType(PNG_BYTES))
        assertEquals("image/webp", Http.sniffImageMimeType(WEBP_BYTES))
        assertNull(Http.sniffImageMimeType("plain text".toByteArray()))
        assertNull(Http.sniffImageMimeType(ByteArray(0)))
    }

    @Test
    fun userMessageFallsBackForForeignExceptions() {
        assertEquals("请求失败（HTTP 503）", Http.userMessage(NetException("请求失败（HTTP 503）")))
        assertEquals(Http.NETWORK_ERROR, Http.userMessage(RuntimeException("boom")))
    }

    @Test
    fun ensureSuccessRejectsNon2xx() {
        assertEquals(200, HttpResponse(200, "ok").ensureSuccess().code)
        assertEquals(204, HttpResponse(204, "").ensureSuccess().code)
        listOf(301, 404, 500).forEach { code ->
            val error = assertThrows(NetException::class.java) {
                HttpResponse(code, "").ensureSuccess()
            }
            assertEquals("请求失败（HTTP $code）", error.message)
        }
    }

    @Test
    fun downloadRejectsInvalidMaxSize() {
        val error = assertThrows(NetException::class.java) {
            Http.download("https://example.com/a.jpg", maxBytes = 0)
        }
        assertEquals("下载大小无效", error.message)
    }
}
