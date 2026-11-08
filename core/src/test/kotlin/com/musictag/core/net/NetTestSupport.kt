package com.musictag.core.net

/**
 * Canonical request captured by [FakeHttpTransport], so tests can assert both
 * behaviour and exact URL construction without touching the network.
 */
data class RequestRecord(
    val method: String,
    val url: String,
    val form: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
)

/**
 * In-memory [HttpTransport] for unit tests. Canned responses are keyed by the
 * exact request URL; unmatched requests fail loudly so URL drift is caught.
 */
class FakeHttpTransport : HttpTransport {
    val requests = mutableListOf<RequestRecord>()
    val responses = mutableMapOf<String, HttpResponse>()
    val downloads = mutableMapOf<String, ByteArray>()
    var handler: ((RequestRecord) -> HttpResponse?)? = null
    var downloadHandler: ((RequestRecord) -> ByteArray?)? = null

    /** When set, every transport call throws it (simulates I/O failures). */
    var failure: Exception? = null

    fun on(url: String, body: String, code: Int = 200) {
        responses[url] = HttpResponse(code, body)
    }

    fun onDownload(url: String, bytes: ByteArray) {
        downloads[url] = bytes
    }

    override fun get(url: String, headers: Map<String, String>): HttpResponse =
        respond(RequestRecord("GET", url, headers = headers))

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse =
        respond(RequestRecord("POST", url, form, headers))

    override fun download(url: String, headers: Map<String, String>, maxBytes: Int): ByteArray {
        val request = RequestRecord("GET", url, headers = headers)
        requests += request
        failure?.let { throw it }
        downloadHandler?.invoke(request)?.let { return it }
        return downloads[url] ?: throw NetException("unexpected download: $url")
    }

    private fun respond(request: RequestRecord): HttpResponse {
        requests += request
        failure?.let { throw it }
        handler?.invoke(request)?.let { return it }
        return responses[request.url]
            ?: throw NetException("unexpected ${request.method}: ${request.url}")
    }

    fun lastRequest(): RequestRecord = requests.last()
}

/** Loads recorded (trimmed) responses from `src/test/resources/net/`. */
object Fixtures {
    fun read(name: String): String {
        val stream = Fixtures::class.java.classLoader.getResourceAsStream("net/$name")
            ?: error("missing fixture: net/$name")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

/** Minimal valid JPEG payload (magic bytes are all the sniffer looks at). */
val JPEG_BYTES: ByteArray = byteArrayOf(
    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46,
    0x49, 0x46, 0x00, 0x01,
)

/** Minimal valid PNG payload. */
val PNG_BYTES: ByteArray = byteArrayOf(
    0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
)

/** Minimal valid WebP payload. */
val WEBP_BYTES: ByteArray = "RIFF".toByteArray(Charsets.US_ASCII) +
    byteArrayOf(0x24, 0x00, 0x00, 0x00) +
    "WEBP".toByteArray(Charsets.US_ASCII) +
    byteArrayOf(0x56, 0x50, 0x38, 0x20)
