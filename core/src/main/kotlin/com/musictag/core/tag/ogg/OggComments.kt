package com.musictag.core.tag.ogg

import com.musictag.core.tag.TagException
import java.io.ByteArrayOutputStream

/** Codec carried inside the Ogg container. */
internal enum class OggCodec(val displayName: String, val headerPacketCount: Int) {
    VORBIS("Vorbis", 3),
    OPUS("Opus", 2),
    ;

    /** Magic prefix of the comment header packet for this codec. */
    val commentPacketPrefix: ByteArray
        get() = when (this) {
            VORBIS -> VORBIS_COMMENT_PREFIX
            OPUS -> OPUS_TAGS_PREFIX
        }

    companion object {
        val VORBIS_IDENTIFICATION_PREFIX: ByteArray = byteArrayOf(0x01) + "vorbis".toByteArray(Charsets.US_ASCII)
        val VORBIS_COMMENT_PREFIX: ByteArray = byteArrayOf(0x03) + "vorbis".toByteArray(Charsets.US_ASCII)
        val VORBIS_SETUP_PREFIX: ByteArray = byteArrayOf(0x05) + "vorbis".toByteArray(Charsets.US_ASCII)
        val OPUS_IDENTIFICATION_PREFIX: ByteArray = "OpusHead".toByteArray(Charsets.US_ASCII)
        val OPUS_TAGS_PREFIX: ByteArray = "OpusTags".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * Parsed Vorbis/Opus comment header packet content.
 *
 * The vendor string and any comment that is not modeled by [com.musictag.core.tag.TagDocument]
 * are preserved verbatim. [trailingData] keeps bytes that follow the comment
 * list (Opus allows optional padding/extension data there; Vorbis may carry
 * data after its framing bit).
 */
internal class CommentBlock(
    val vendor: String,
    /** Raw `KEY=value` entries exactly as stored in the file. */
    val comments: List<String>,
    val trailingData: ByteArray,
) {

    /** Serialises this comment block into a complete comment header packet. */
    fun encode(codec: OggCodec): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(codec.commentPacketPrefix)

        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)
        writeUInt32(output, vendorBytes.size)
        output.write(vendorBytes)

        writeUInt32(output, comments.size)
        for (comment in comments) {
            val bytes = comment.toByteArray(Charsets.UTF_8)
            writeUInt32(output, bytes.size)
            output.write(bytes)
        }

        // The Vorbis comment header terminates with a framing bit; Opus has none.
        if (codec == OggCodec.VORBIS) output.write(0x01)
        output.write(trailingData)
        return output.toByteArray()
    }

    companion object {
        /** Practical upper bound protecting against malicious length fields. */
        private const val MAX_COMMENT_COUNT = 1_000_000

        /** Parses a Vorbis (packet type 3) or Opus (`OpusTags`) comment packet. */
        fun parse(packet: ByteArray, codec: OggCodec): CommentBlock {
            val prefix = codec.commentPacketPrefix
            if (packet.size < prefix.size + 4) {
                throw TagException("Ogg 注释数据包不完整（${codec.displayName}）")
            }
            for (index in prefix.indices) {
                if (packet[index] != prefix[index]) {
                    throw TagException("Ogg 注释数据包签名无效（${codec.displayName}）")
                }
            }
            var position = prefix.size

            val vendorLength = readUInt32Le(packet, position)
            position += 4
            if (vendorLength < 0 || vendorLength > packet.size - position) {
                throw TagException("Ogg 注释数据损坏：厂商字符串长度异常")
            }
            val vendor = String(packet, position, vendorLength.toInt(), Charsets.UTF_8)
            position += vendorLength.toInt()

            if (position + 4 > packet.size) {
                throw TagException("Ogg 注释数据损坏：缺少注释条目数量字段")
            }
            val commentCount = readUInt32Le(packet, position)
            position += 4
            if (commentCount > MAX_COMMENT_COUNT || commentCount > (packet.size - position).toLong() / 4L) {
                throw TagException("Ogg 注释数据损坏：注释条目数量异常")
            }

            val comments = ArrayList<String>(commentCount.toInt())
            repeat(commentCount.toInt()) {
                if (position + 4 > packet.size) {
                    throw TagException("Ogg 注释数据损坏：注释条目长度字段缺失")
                }
                val entryLength = readUInt32Le(packet, position)
                position += 4
                if (entryLength < 0 || entryLength > packet.size - position) {
                    throw TagException("Ogg 注释数据损坏：注释条目长度异常")
                }
                comments.add(String(packet, position, entryLength.toInt(), Charsets.UTF_8))
                position += entryLength.toInt()
            }

            var trailing = packet.copyOfRange(position, packet.size)
            if (codec == OggCodec.VORBIS && trailing.isNotEmpty() && (trailing[0].toInt() and 0x01) != 0) {
                // Consume the framing bit.
                trailing = trailing.copyOfRange(1, trailing.size)
            }
            return CommentBlock(vendor, comments, trailing)
        }

        private fun writeUInt32(output: ByteArrayOutputStream, value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
            output.write((value ushr 16) and 0xFF)
            output.write((value ushr 24) and 0xFF)
        }
    }
}

/**
 * A single `KEY=value` Vorbis comment. Keys are case-insensitive; values are
 * UTF-8 and may contain any character including `=`.
 */
internal class CommentEntry private constructor(
    val key: String,
    val value: String,
    val raw: String,
) {
    fun keyEquals(candidate: String): Boolean = key.equals(candidate, ignoreCase = true)

    companion object {
        fun keyOf(raw: String): String? {
            val separator = raw.indexOf('=')
            if (separator <= 0) return null
            return raw.substring(0, separator)
        }

        fun parse(raw: String): CommentEntry? {
            val separator = raw.indexOf('=')
            if (separator <= 0) return null
            return CommentEntry(raw.substring(0, separator), raw.substring(separator + 1), raw)
        }
    }
}
