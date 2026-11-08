package com.musictag.core.tag.flac

import com.musictag.core.tag.TagException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Vendor string written when a file had no VORBIS_COMMENT block before the edit. */
internal const val DEFAULT_VENDOR_STRING = "MusicTagger"

/**
 * Comment fields that are represented by [com.musictag.core.tag.TagDocument]. They are removed
 * before the new document values are appended; every other field is preserved as-is.
 */
internal val MODELED_COMMENT_KEYS: Set<String> = setOf(
    "TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "DATE", "YEAR",
    "TRACKNUMBER", "DISCNUMBER", "GENRE", "COMMENT", "DESCRIPTION", "LYRICS", "UNSYNCEDLYRICS",
)

/**
 * A single Vorbis comment field (`KEY=value`). The raw bytes are kept so unmodeled fields can be
 * copied back into the rewritten block byte-for-byte.
 */
internal class VorbisCommentEntry(val raw: ByteArray) {
    val text: String = String(raw, StandardCharsets.UTF_8)

    val key: String?
    val value: String?

    init {
        val separator = text.indexOf('=')
        if (separator <= 0) {
            key = null
            value = null
        } else {
            val candidate = text.substring(0, separator).trim().uppercase(Locale.ROOT)
            if (candidate.isEmpty() || candidate.any { it.code < 0x20 || it == '=' }) {
                key = null
                value = null
            } else {
                key = candidate
                value = text.substring(separator + 1)
            }
        }
    }

    val isModeled: Boolean
        get() {
            val fieldKey = key ?: return false
            return fieldKey in MODELED_COMMENT_KEYS
        }
}

/** Fully parsed Vorbis comment payload (vendor string plus all fields). */
internal class VorbisComment(val vendor: ByteArray, val entries: List<VorbisCommentEntry>) {

    /** First non-blank value for [key] (the key is matched case-insensitively). */
    fun valueOf(key: String): String? {
        val wanted = key.uppercase(Locale.ROOT)
        for (entry in entries) {
            if (entry.key != wanted) continue
            val fieldValue = entry.value ?: continue
            if (fieldValue.isNotBlank()) return fieldValue
        }
        return null
    }

    /** First non-blank value among the candidate [keys], in preference order. */
    fun firstValueOf(vararg keys: String): String? {
        for (key in keys) {
            val value = valueOf(key)
            if (value != null) return value
        }
        return null
    }

    /** Serializes this comment as the payload of a VORBIS_COMMENT metadata block. */
    fun toBlockData(): ByteArray {
        if (vendor.size > MAX_BLOCK_DATA_SIZE) {
            throw TagException("Vorbis 注释厂商字符串过大，无法写入 FLAC")
        }
        var total = 4L + vendor.size + 4L
        for (entry in entries) {
            if (entry.raw.size > MAX_BLOCK_DATA_SIZE) {
                throw TagException("Vorbis 注释字段过大，无法写入 FLAC")
            }
            total += 4L + entry.raw.size
            if (total > MAX_BLOCK_DATA_SIZE) {
                throw TagException("Vorbis 注释过大（超过 16 MiB），无法写入 FLAC")
            }
        }
        val out = ByteArrayOutputStream(total.toInt())
        writeLe32(out, vendor.size)
        out.write(vendor)
        writeLe32(out, entries.size)
        for (entry in entries) {
            writeLe32(out, entry.raw.size)
            out.write(entry.raw)
        }
        return out.toByteArray()
    }

    companion object {
        /**
         * Merges the comment payloads of several VORBIS_COMMENT blocks into a single logical
         * comment (the vendor string and field order of the first block win).
         */
        fun combined(comments: List<VorbisComment>): VorbisComment {
            if (comments.isEmpty()) {
                return VorbisComment(DEFAULT_VENDOR_STRING.toByteArray(StandardCharsets.UTF_8), emptyList())
            }
            if (comments.size == 1) return comments[0]
            return VorbisComment(comments.first().vendor, comments.flatMap { it.entries })
        }
    }
}

internal fun VorbisComment.withFields(modeled: List<VorbisCommentEntry>): VorbisComment =
    VorbisComment(vendor, entries.filter { !it.isModeled } + modeled)

internal fun commentField(key: String, value: String?): VorbisCommentEntry? {
    if (value == null) return null
    return VorbisCommentEntry("$key=$value".toByteArray(StandardCharsets.UTF_8))
}

internal fun writeLe32(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 24) and 0xFF)
}

/** Parses the payload of a VORBIS_COMMENT metadata block (all lengths are little endian). */
internal object VorbisCommentParser {

    fun parse(data: ByteArray): VorbisComment {
        if (data.size < 8) throw corrupt("块长度不足")
        val reader = LeReader(data)
        val vendorLength = reader.readU32("厂商字符串长度")
        val vendor = reader.readBytes(vendorLength, "厂商字符串")
        val count = reader.readU32("注释条目数量")
        if (count > reader.remaining() / 4L) {
            throw corrupt("注释条目数量与块长度不符")
        }
        val entries = ArrayList<VorbisCommentEntry>(minOf(count.toInt(), 256))
        var index = 0
        while (index < count) {
            val length = reader.readU32("注释条目长度")
            entries.add(VorbisCommentEntry(reader.readBytes(length, "注释条目内容")))
            index++
        }
        if (reader.remaining() != 0) {
            throw corrupt("注释块尾部存在多余数据")
        }
        return VorbisComment(vendor, entries)
    }

    private fun corrupt(detail: String): TagException =
        TagException("FLAC 文件已损坏：Vorbis 注释块$detail")
}

private class LeReader(private val data: ByteArray) {
    private var position = 0

    fun remaining(): Int = data.size - position

    fun readU32(what: String): Long {
        if (remaining() < 4) throw corrupt(what)
        val value = (data[position].toLong() and 0xFF) or
            ((data[position + 1].toLong() and 0xFF) shl 8) or
            ((data[position + 2].toLong() and 0xFF) shl 16) or
            ((data[position + 3].toLong() and 0xFF) shl 24)
        position += 4
        return value
    }

    fun readBytes(length: Long, what: String): ByteArray {
        if (length < 0 || length > remaining().toLong()) throw corrupt(what)
        val result = data.copyOfRange(position, position + length.toInt())
        position += length.toInt()
        return result
    }

    private fun corrupt(what: String): Nothing =
        throw TagException("FLAC 文件已损坏：Vorbis 注释块中$what 超出块长度")
}
