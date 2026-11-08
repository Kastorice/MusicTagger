package com.musictag.core.tag.ogg

import com.musictag.core.tag.TagException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min

/**
 * Everything the writer needs to know about the header region of an Ogg
 * Vorbis/Opus stream.
 */
internal class OggHeaderInfo(
    val codec: OggCodec,
    val serialNumber: Int,
    val identificationPacket: ByteArray,
    /** Vorbis setup header packet; `null` for Opus. */
    val setupPacket: ByteArray?,
    val commentBlock: CommentBlock,
    /** Number of pages covered by the codec header packets (identification + comment (+ setup)). */
    val headerPageCount: Int,
    /** Byte offset where the first audio page starts (may equal the file length). */
    val audioOffset: Long,
    /** Whether the final header page happened to carry the end-of-stream flag. */
    val lastHeaderPageHadEos: Boolean,
)

/**
 * Parses the codec header packets of an Ogg Vorbis or Ogg Opus logical stream.
 *
 * The parser never looks at audio pages (apart from not accepting audio packets
 * that start on a header page), which keeps `read` cheap for large files.
 */
internal object OggHeaderParser {

    /** Upper bound for a single header packet (guards against absurd length fields). */
    private const val MAX_HEADER_PACKET_BYTES = 16 * 1024 * 1024

    fun parse(input: InputStream): OggHeaderInfo {
        var offset = 0L
        val firstPage = OggPageCodec.read(input, offset, verifyCrc = true)
            ?: throw TagException("不是有效的 Ogg 文件：文件为空")
        offset += firstPage.raw.size

        if (!firstPage.isBos) {
            throw TagException("不是有效的 Ogg 流：第一个页面缺少 BOS（流开始）标记")
        }
        if (firstPage.isContinuation) {
            throw TagException("不是有效的 Ogg 流：第一个页面带有继续标记")
        }
        val serialNumber = firstPage.serialNumber
        val identificationPacket = singlePacketOnPage(firstPage)
        val codec = detectCodec(identificationPacket)

        val packets = ArrayList<ByteArray>(codec.headerPacketCount)
        packets.add(identificationPacket)
        val pendingPacket = ByteArrayOutputStream()
        var pageCount = 1
        var lastPage = firstPage

        while (packets.size < codec.headerPacketCount) {
            val page = OggPageCodec.read(input, offset, verifyCrc = true)
                ?: throw TagException("Ogg 头部数据不完整：文件在读取 ${codec.displayName} 头部数据包时结束")
            offset += page.raw.size
            pageCount++
            lastPage = page

            if (page.serialNumber != serialNumber) {
                throw TagException("暂不支持串联或复用的 Ogg 流：检测到多个逻辑流（页面序列号不一致）")
            }
            if (page.isBos) {
                throw TagException("暂不支持串联或复用的 Ogg 流：头部区域出现了新的逻辑流（BOS 页面）")
            }
            val expectsContinuation = pendingPacket.size() > 0
            if (page.isContinuation != expectsContinuation) {
                throw TagException("Ogg 头部数据包的分页标记不一致，文件可能已损坏")
            }

            var payloadPosition = 0
            for (segmentIndex in page.lacingValues.indices) {
                val segmentLength = page.lacingValues[segmentIndex]
                if (payloadPosition + segmentLength > page.payload.size) {
                    throw TagException("Ogg 页面数据损坏：分段长度超出页面负载")
                }
                pendingPacket.write(page.payload, payloadPosition, segmentLength)
                payloadPosition += segmentLength
                if (pendingPacket.size() > MAX_HEADER_PACKET_BYTES) {
                    throw TagException("Ogg 头部数据包过大（超过 ${MAX_HEADER_PACKET_BYTES / (1024 * 1024)} MiB），拒绝处理")
                }
                if (segmentLength < 255) {
                    packets.add(pendingPacket.toByteArray())
                    pendingPacket.reset()
                    if (packets.size == codec.headerPacketCount && segmentIndex != page.lacingValues.size - 1) {
                        throw TagException(
                            "暂不支持该 Ogg 结构：头部数据包结束后同一页面中仍有其它数据包（头部与音频数据交叉）",
                        )
                    }
                }
            }
        }

        if (pendingPacket.size() > 0) {
            throw TagException("Ogg 头部数据包不完整，文件可能已损坏")
        }

        val commentPacket = packets[1]
        if (!startsWith(commentPacket, codec.commentPacketPrefix)) {
            throw TagException("Ogg ${codec.displayName} 注释头数据包格式无效")
        }
        val commentBlock = CommentBlock.parse(commentPacket, codec)

        val setupPacket = if (codec == OggCodec.VORBIS) {
            val setup = packets[2]
            if (!startsWith(setup, OggCodec.VORBIS_SETUP_PREFIX)) {
                throw TagException("Ogg Vorbis 设置头数据包格式无效")
            }
            setup
        } else {
            null
        }

        return OggHeaderInfo(
            codec = codec,
            serialNumber = serialNumber,
            identificationPacket = identificationPacket,
            setupPacket = setupPacket,
            commentBlock = commentBlock,
            headerPageCount = pageCount,
            audioOffset = offset,
            lastHeaderPageHadEos = lastPage.isEos,
        )
    }

    /** The first page must contain exactly one complete packet (the identification header). */
    private fun singlePacketOnPage(page: OggPage): ByteArray {
        if (page.lacingValues.isEmpty()) {
            throw TagException("不是有效的 Ogg 流：第一个页面没有数据")
        }
        if (page.endsWithContinuedPacket) {
            throw TagException("暂不支持该 Ogg 结构：标识头数据包跨越多个页面")
        }
        for (index in 0 until page.lacingValues.size - 1) {
            if (page.lacingValues[index] != 255) {
                throw TagException("暂不支持该 Ogg 结构：第一个页面包含多个数据包")
            }
        }
        return page.payload
    }

    private fun detectCodec(identificationPacket: ByteArray): OggCodec {
        if (startsWith(identificationPacket, OggCodec.VORBIS_IDENTIFICATION_PREFIX)) {
            if (identificationPacket.size < 30) {
                throw TagException("Ogg Vorbis 标识头数据不完整")
            }
            return OggCodec.VORBIS
        }
        if (startsWith(identificationPacket, OggCodec.OPUS_IDENTIFICATION_PREFIX)) {
            if (identificationPacket.size < 19) {
                throw TagException("Ogg Opus 标识头数据不完整")
            }
            return OggCodec.OPUS
        }
        if (startsWith(identificationPacket, "fLaC".toByteArray(Charsets.US_ASCII))) {
            throw TagException("暂不支持的 Ogg 编码格式：Ogg FLAC（目前仅支持 Ogg Vorbis 与 Ogg Opus）")
        }
        if (startsWith(identificationPacket, "Speex   ".toByteArray(Charsets.US_ASCII))) {
            throw TagException("暂不支持的 Ogg 编码格式：Speex（目前仅支持 Ogg Vorbis 与 Ogg Opus）")
        }
        if (startsWith(identificationPacket, byteArrayOf(0x80.toByte()) + "theora".toByteArray(Charsets.US_ASCII))) {
            throw TagException("暂不支持的 Ogg 编码格式：Theora 视频（目前仅支持 Ogg Vorbis 与 Ogg Opus）")
        }
        throw TagException("不支持该 Ogg 编码格式：无法识别逻辑流标识头（仅支持 Ogg Vorbis 与 Ogg Opus）")
    }
}

/** Builds the header pages of a rewritten Ogg Vorbis/Opus stream. */
internal object OggHeaderWriter {

    /**
     * Serialises the identification header plus [extraHeaderPackets] (comment,
     * and setup for Vorbis) into Ogg pages.
     *
     * The identification header is placed alone on the first (BOS) page. The
     * remaining header packets are packed back-to-back on the following pages: a
     * page holds as many packets as fit into its 255 lacing values / 65025
     * payload bytes, and a packet that crosses a page boundary is continued on
     * the next page with the continuation flag set. The final page always
     * finishes exactly at the end of the last header packet, so the first audio
     * packet starts on a fresh page, as required by both the Vorbis
     * encapsulation spec and RFC 7845 for Opus.
     */
    fun buildHeaderPages(
        serialNumber: Int,
        identificationPacket: ByteArray,
        extraHeaderPackets: List<ByteArray>,
        eosOnLastPage: Boolean,
    ): List<ByteArray> {
        // The identification header must be alone on the first (BOS) page: the
        // Vorbis encapsulation spec and RFC 7845 (Opus) both require this.
        val identificationLacing = lacingOf(identificationPacket)
        require(identificationLacing.size <= 255) {
            "identification packet does not fit into a single Ogg page"
        }
        val pages = ArrayList<ByteArray>()
        pages.add(
            OggPageCodec.buildPage(
                headerType = OGG_FLAG_BOS,
                granulePosition = 0L,
                serialNumber = serialNumber,
                sequenceNumber = 0L,
                lacingValues = identificationLacing.toIntArray(),
                payload = identificationPacket,
            ),
        )

        // Comment (and, for Vorbis, setup) header packets start on a fresh page and
        // may share pages with each other; a packet that does not fit into one page
        // continues on the following pages with the continuation flag set.
        val lacingValues = ArrayList<Int>()
        val payloadCollector = ByteArrayOutputStream()
        for (packet in extraHeaderPackets) {
            payloadCollector.write(packet)
            lacingValues.addAll(lacingOf(packet))
        }
        val allPayload = payloadCollector.toByteArray()
        require(lacingValues.isNotEmpty()) { "missing comment header packet" }

        var segmentIndex = 0
        var payloadOffset = 0
        var sequenceNumber = 1L
        var previousPageEndedMidPacket = false

        while (segmentIndex < lacingValues.size) {
            val segmentCount = min(255, lacingValues.size - segmentIndex)
            val pageLacing = IntArray(segmentCount) { lacingValues[segmentIndex + it] }
            var pagePayloadSize = 0
            for (lacingValue in pageLacing) pagePayloadSize += lacingValue
            val pagePayload = allPayload.copyOfRange(payloadOffset, payloadOffset + pagePayloadSize)
            val isLastPage = segmentIndex + segmentCount >= lacingValues.size

            var headerType = 0
            if (previousPageEndedMidPacket) headerType = headerType or OGG_FLAG_CONTINUED
            if (isLastPage && eosOnLastPage) headerType = headerType or OGG_FLAG_EOS

            // Header packets all carry granule position 0; a page that ends in the
            // middle of a packet has no granule position (-1).
            val granulePosition = if (pageLacing[segmentCount - 1] == 255) -1L else 0L

            pages.add(
                OggPageCodec.buildPage(
                    headerType = headerType,
                    granulePosition = granulePosition,
                    serialNumber = serialNumber,
                    sequenceNumber = sequenceNumber,
                    lacingValues = pageLacing,
                    payload = pagePayload,
                ),
            )

            previousPageEndedMidPacket = pageLacing[segmentCount - 1] == 255
            segmentIndex += segmentCount
            payloadOffset += pagePayloadSize
            sequenceNumber++
        }
        return pages
    }

    /** Ogg lacing values for one complete packet. */
    private fun lacingOf(packet: ByteArray): ArrayList<Int> {
        val lacingValues = ArrayList<Int>(packet.size / 255 + 1)
        var remaining = packet.size
        while (remaining >= 255) {
            lacingValues.add(255)
            remaining -= 255
        }
        lacingValues.add(remaining)
        return lacingValues
    }
}

/** True when [bytes] starts with [prefix]. */
internal fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
    if (bytes.size < prefix.size) return false
    for (index in prefix.indices) {
        if (bytes[index] != prefix[index]) return false
    }
    return true
}
