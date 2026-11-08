package com.musictag.core.tag.ogg

import com.musictag.core.tag.TagException
import java.io.InputStream

/** Ogg page header type flag: the first packet continues from the previous page. */
internal const val OGG_FLAG_CONTINUED = 0x01

/** Ogg page header type flag: beginning of a logical stream. */
internal const val OGG_FLAG_BOS = 0x02

/** Ogg page header type flag: end of a logical stream. */
internal const val OGG_FLAG_EOS = 0x04

/** Absolute maximum number of payload bytes addressable by one Ogg page. */
internal const val OGG_MAX_PAGE_PAYLOAD = 255 * 255

/** Size of the fixed part of an Ogg page header (before the lacing table). */
private const val OGG_PAGE_HEADER_SIZE = 27

/** Number of lacing values a page can carry at most. */
private const val OGG_MAX_PAGE_SEGMENTS = 255

/**
 * One parsed Ogg page, together with its raw bytes.
 *
 * The raw representation is kept because rewriting audio pages only needs to
 * patch the sequence number and checksum; keeping the original bytes guarantees
 * that packet payloads, lacing tables, granule positions, flags and serial
 * numbers are preserved exactly.
 */
internal class OggPage(
    val offset: Long,
    val headerType: Int,
    val granulePosition: Long,
    val serialNumber: Int,
    val sequenceNumber: Long,
    val lacingValues: IntArray,
    val payload: ByteArray,
    val raw: ByteArray,
) {
    val isBos: Boolean get() = headerType and OGG_FLAG_BOS != 0

    val isEos: Boolean get() = headerType and OGG_FLAG_EOS != 0

    val isContinuation: Boolean get() = headerType and OGG_FLAG_CONTINUED != 0

    /** True when the page ends in the middle of a packet (last lacing value is 255). */
    val endsWithContinuedPacket: Boolean
        get() = lacingValues.isNotEmpty() && lacingValues.last() == 255
}

/** Parses and serialises binary Ogg pages. */
internal object OggPageCodec {

    /**
     * Reads one page from [input]. Returns `null` on a clean end of file, throws
     * [TagException] for truncated or malformed pages.
     *
     * @param offset byte offset of this page in the stream, used for error messages.
     * @param verifyCrc whether the stored checksum must match the computed one.
     */
    fun read(input: InputStream, offset: Long, verifyCrc: Boolean): OggPage? {
        val firstByte = input.read()
        if (firstByte < 0) return null

        val header = ByteArray(OGG_PAGE_HEADER_SIZE)
        header[0] = firstByte.toByte()
        readFully(input, header, 1, OGG_PAGE_HEADER_SIZE - 1, offset)

        if (header[0] != 'O'.code.toByte() || header[1] != 'g'.code.toByte() ||
            header[2] != 'g'.code.toByte() || header[3] != 'S'.code.toByte()
        ) {
            throw TagException("不是有效的 Ogg 文件：偏移 $offset 处缺少 OggS 页面标记")
        }

        val version = header[4].toInt() and 0xFF
        if (version != 0) {
            throw TagException("不支持的 Ogg 版本 $version（仅支持版本 0）")
        }

        val headerType = header[5].toInt() and 0xFF
        val granulePosition = readInt64Le(header, 6)
        val serialNumber = readUInt32Le(header, 14).toInt()
        val sequenceNumber = readUInt32Le(header, 18)
        val storedCrc = readUInt32Le(header, 22)
        val segmentCount = header[26].toInt() and 0xFF

        val lacingBytes = ByteArray(segmentCount)
        readFully(input, lacingBytes, 0, segmentCount, offset)
        val lacingValues = IntArray(segmentCount) { lacingBytes[it].toInt() and 0xFF }

        var payloadSize = 0
        for (lacingValue in lacingValues) payloadSize += lacingValue

        val payload = ByteArray(payloadSize)
        readFully(input, payload, 0, payloadSize, offset)

        val raw = ByteArray(OGG_PAGE_HEADER_SIZE + segmentCount + payloadSize)
        System.arraycopy(header, 0, raw, 0, OGG_PAGE_HEADER_SIZE)
        System.arraycopy(lacingBytes, 0, raw, OGG_PAGE_HEADER_SIZE, segmentCount)
        System.arraycopy(payload, 0, raw, OGG_PAGE_HEADER_SIZE + segmentCount, payloadSize)

        if (verifyCrc) {
            val computed = computePageCrc(raw).toLong() and 0xFFFFFFFFL
            if (computed != storedCrc) {
                throw TagException("Ogg 页面校验失败：偏移 $offset 处的页面 CRC 不正确，文件可能已损坏")
            }
        }

        return OggPage(
            offset = offset,
            headerType = headerType,
            granulePosition = granulePosition,
            serialNumber = serialNumber,
            sequenceNumber = sequenceNumber,
            lacingValues = lacingValues,
            payload = payload,
            raw = raw,
        )
    }

    /**
     * Builds one complete Ogg page including its checksum.
     *
     * @param granulePosition `-1` when no packet completes on this page.
     */
    fun buildPage(
        headerType: Int,
        granulePosition: Long,
        serialNumber: Int,
        sequenceNumber: Long,
        lacingValues: IntArray,
        payload: ByteArray,
    ): ByteArray {
        require(lacingValues.size <= OGG_MAX_PAGE_SEGMENTS) { "too many lacing values" }
        var expectedPayload = 0
        for (lacingValue in lacingValues) expectedPayload += lacingValue
        require(expectedPayload == payload.size) { "payload size does not match lacing table" }

        val headerSize = OGG_PAGE_HEADER_SIZE + lacingValues.size
        val raw = ByteArray(headerSize + payload.size)
        raw[0] = 'O'.code.toByte()
        raw[1] = 'g'.code.toByte()
        raw[2] = 'g'.code.toByte()
        raw[3] = 'S'.code.toByte()
        raw[4] = 0
        raw[5] = headerType.toByte()
        writeInt64Le(raw, 6, granulePosition)
        writeUInt32Le(raw, 14, serialNumber.toLong() and 0xFFFFFFFFL)
        writeUInt32Le(raw, 18, sequenceNumber and 0xFFFFFFFFL)
        writeUInt32Le(raw, 22, 0)
        raw[26] = lacingValues.size.toByte()
        for (index in lacingValues.indices) {
            raw[OGG_PAGE_HEADER_SIZE + index] = lacingValues[index].toByte()
        }
        System.arraycopy(payload, 0, raw, headerSize, payload.size)
        writeUInt32Le(raw, 22, computePageCrc(raw).toLong() and 0xFFFFFFFFL)
        return raw
    }

    /**
     * Rewrites only the page sequence number and the checksum of an already
     * serialised page. Every other byte (payload, lacing table, granule
     * position, header flags, serial number) is preserved.
     */
    fun resequencePage(raw: ByteArray, newSequenceNumber: Long): ByteArray {
        val copy = raw.copyOf()
        writeUInt32Le(copy, 18, newSequenceNumber and 0xFFFFFFFFL)
        writeUInt32Le(copy, 22, 0)
        writeUInt32Le(copy, 22, computePageCrc(copy).toLong() and 0xFFFFFFFFL)
        return copy
    }

    /** Computes the checksum of a raw page with its CRC field zeroed out. */
    private fun computePageCrc(raw: ByteArray): Int {
        val copy = raw.copyOf()
        writeUInt32Le(copy, 22, 0)
        return OggCrc.compute(copy)
    }

    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int, pageOffset: Long) {
        var read = 0
        while (read < length) {
            val count = input.read(buffer, offset + read, length - read)
            if (count < 0) {
                throw TagException("Ogg 页面数据被截断（页面起始偏移 $pageOffset）")
            }
            if (count == 0) continue
            read += count
        }
    }
}

/** Little-endian unsigned 32 bit read. */
internal fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

/** Little-endian signed 64 bit read. */
internal fun readInt64Le(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 7 downTo 0) {
        value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
    }
    return value
}

/** Little-endian unsigned 32 bit write. */
internal fun writeUInt32Le(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = (value and 0xFFL).toByte()
    bytes[offset + 1] = ((value ushr 8) and 0xFFL).toByte()
    bytes[offset + 2] = ((value ushr 16) and 0xFFL).toByte()
    bytes[offset + 3] = ((value ushr 24) and 0xFFL).toByte()
}

/** Little-endian signed 64 bit write. */
internal fun writeInt64Le(bytes: ByteArray, offset: Int, value: Long) {
    var remaining = value
    for (index in 0 until 8) {
        bytes[offset + index] = (remaining and 0xFFL).toByte()
        remaining = remaining ushr 8
    }
}
