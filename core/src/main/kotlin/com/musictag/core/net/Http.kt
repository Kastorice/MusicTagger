package com.musictag.core.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

/** A decoded HTTP response body plus the status code and content type. */
class HttpResponse(
    val code: Int,
    val body: String,
    val contentType: String? = null,
)

/** Blocking HTTP transport, injectable so unit tests never hit the network. */
interface HttpTransport {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResponse

    fun download(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Int = DEFAULT_MAX_DOWNLOAD): ByteArray

    companion object {
        /** Covers are embedded in audio tags, so keep the limit modest. */
        const val DEFAULT_MAX_DOWNLOAD = 8 * 1024 * 1024
    }
}

/**
 * Throws [NetException] unless this response is a 2xx success.
 *
 * [Http] already rejects non-2xx responses, but injected fake transports return
 * them verbatim, so every source funnels responses through this helper.
 */
fun HttpResponse.ensureSuccess(): HttpResponse {
    if (code !in 200..299) throw NetException("请求失败（HTTP $code）")
    return this
}

/** Runs a transport call and maps leaked I/O failures to a friendly message. */
internal fun <T> netCall(block: () -> T): T = try {
    block()
} catch (e: NetException) {
    throw e
} catch (e: Exception) {
    throw NetException(Http.NETWORK_ERROR, e)
}

/**
 * Real transport over [HttpURLConnection]: follows redirects, negotiates gzip,
 * decodes UTF-8, adds browser-like headers and per-service Referer/Cookie.
 *
 * All failures surface as [NetException] with a short Chinese message; raw
 * exceptions never leak to the UI.
 */
object Http : HttpTransport {
    /** Desktop browser user agent; some endpoints reject the default JDK one. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val NETWORK_ERROR = "网络连接失败，请检查网络设置"
    const val BAD_URL = "链接格式错误"
    const val RESPONSE_TOO_LARGE = "响应内容过大"

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val NETEASE_REFERER = "https://music.163.com/"
    private const val NETEASE_COOKIE = "appver=2.0.2"
    private const val QQ_REFERER = "https://y.qq.com/"

    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val raw = call("GET", url, null, headers, MAX_TEXT_BYTES)
        return HttpResponse(raw.code, decodeUtf8(raw.bytes), raw.contentType)
    }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }.toByteArray(StandardCharsets.UTF_8)
        val merged = LinkedHashMap<String, String>(headers)
        if (merged.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
            merged["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8"
        }
        val raw = call("POST", url, body, merged, MAX_TEXT_BYTES)
        return HttpResponse(raw.code, decodeUtf8(raw.bytes), raw.contentType)
    }

    override fun download(url: String, headers: Map<String, String>, maxBytes: Int): ByteArray {
        if (maxBytes <= 0) throw NetException("下载大小无效")
        return call("GET", url, null, headers, maxBytes).bytes
    }

    /** Upgrades a public cover URL from plain HTTP to HTTPS (no-op otherwise). */
    fun httpsUrl(url: String): String {
        val trimmed = url.trim()
        return if (trimmed.startsWith("http://", ignoreCase = true)) {
            "https://" + trimmed.substring("http://".length)
        } else {
            trimmed
        }
    }

    /** Sniffs JPEG/PNG/WebP magic bytes; returns `null` for anything else. */
    fun sniffImageMimeType(bytes: ByteArray): String? {
        // JPEG SOI marker (FF D8); the third byte is normally FF but is not required.
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            return "image/jpeg"
        }
        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() && bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
        ) {
            return "image/png"
        }
        if (bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
            bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
        ) {
            return "image/webp"
        }
        return null
    }

    /** Maps any failure to a message safe to show in the UI. */
    fun userMessage(error: Throwable): String = when (error) {
        is NetException -> error.message ?: NETWORK_ERROR
        else -> NETWORK_ERROR
    }

    private data class RawResponse(
        val code: Int,
        val bytes: ByteArray,
        val contentType: String?,
        val location: String? = null,
    )

    private fun call(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
        maxBytes: Int,
    ): RawResponse = netCall {
        var currentUrl = requireHttpUrl(url)
        var currentMethod = method
        var currentBody = body
        var redirects = 0
        while (true) {
            val response = executeOnce(currentUrl, currentMethod, currentBody, headers, maxBytes)
            if (response.code in 300..399) {
                val location = response.location ?: throw NetException("请求失败（HTTP ${response.code}）")
                if (redirects++ >= MAX_REDIRECTS) throw NetException("重定向次数过多")
                currentUrl = resolveRedirect(currentUrl, location)
                if (response.code == 303 ||
                    ((response.code == 301 || response.code == 302) && currentMethod == "POST")
                ) {
                    currentMethod = "GET"
                    currentBody = null
                }
                continue
            }
            if (response.code !in 200..299) throw NetException("请求失败（HTTP ${response.code}）")
            return@netCall response
        }
        @Suppress("UNREACHABLE_CODE")
        throw NetException(NETWORK_ERROR)
    }

    private fun executeOnce(
        url: String,
        method: String,
        body: ByteArray?,
        headers: Map<String, String>,
        maxBytes: Int,
    ): RawResponse {
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw NetException(BAD_URL, e)
        }
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = method
            connectionHeaders(url, headers).forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code in 300..399) {
                return RawResponse(code, ByteArray(0), connection.contentType, connection.getHeaderField("Location"))
            }
            if (code !in 200..299) {
                return RawResponse(code, ByteArray(0), connection.contentType)
            }
            val bytes = readBody(connection, maxBytes)
            return RawResponse(code, bytes, connection.contentType)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(connection: HttpURLConnection, maxBytes: Int): ByteArray {
        val rawInput = connection.inputStream ?: throw NetException(NETWORK_ERROR)
        val encoding = connection.contentEncoding?.lowercase().orEmpty()
        val stream: InputStream = if ("gzip" in encoding) GZIPInputStream(rawInput) else rawInput
        return readLimited(stream, maxBytes)
    }

    private fun readLimited(stream: InputStream, maxBytes: Int): ByteArray {
        stream.use { input ->
            val buffer = ByteArray(16 * 1024)
            val out = ByteArrayOutputStream(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw NetException(RESPONSE_TOO_LARGE)
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    private fun connectionHeaders(url: String, provided: Map<String, String>): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        result["User-Agent"] = USER_AGENT
        result["Accept"] = "*/*"
        result["Accept-Encoding"] = "gzip"
        result["Accept-Language"] = "zh-CN,zh;q=0.9,en;q=0.8"
        val host = hostOf(url).orEmpty().lowercase()
        when {
            host == "music.163.com" || host.endsWith(".music.163.com") -> {
                result["Referer"] = NETEASE_REFERER
                result["Cookie"] = NETEASE_COOKIE
            }
            host.endsWith("qq.com") || host.endsWith("gtimg.cn") -> {
                result["Referer"] = QQ_REFERER
            }
        }
        // Caller supplied headers win, and replace differently-cased defaults.
        for ((name, value) in provided) {
            val existing = result.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            if (existing != null) result.remove(existing)
            result[name] = value
        }
        return result
    }

    private fun hostOf(url: String): String? = try {
        URI(url).host
    } catch (e: Exception) {
        null
    }

    private fun requireHttpUrl(url: String): String {
        val trimmed = url.trim()
        val lower = trimmed.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) throw NetException(BAD_URL)
        return trimmed
    }

    private fun resolveRedirect(base: String, location: String): String = try {
        requireHttpUrl(URI(base).resolve(location.trim()).toString())
    } catch (e: NetException) {
        throw e
    } catch (e: Exception) {
        throw NetException(BAD_URL, e)
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val text = String(bytes, StandardCharsets.UTF_8)
        return if (text.startsWith('\uFEFF')) text.substring(1) else text
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
