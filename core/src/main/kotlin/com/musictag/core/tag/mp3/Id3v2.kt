package com.musictag.core.tag.mp3

import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Defensive ID3v2 (2.2 / 2.3 / 2.4) reader.
 *
 * Parsing never reads more than [MAX_TAG_SIZE] bytes, validates every frame
 * length against the tag body and never loops without making progress, so a
 * corrupt download cannot make the editor hang or exhaust memory.
 */
internal object Id3v2 {

    const val HEADER_SIZE = 10
    const val FOOTER_SIZE = 10
    const val PADDING_SIZE = 1024

    /** Upper bound for a single ID3v2 tag; the format itself allows ~256 MiB. */
    const val MAX_TAG_SIZE = 64 * 1024 * 1024

    private const val MAX_FRAMES = 100_000

    private val ASCII: Charset = StandardCharsets.ISO_8859_1
    private val UTF8: Charset = StandardCharsets.UTF_8
    private val UTF16LE: Charset = StandardCharsets.UTF_16LE
    private val UTF16BE: Charset = StandardCharsets.UTF_16BE
    private val US_ASCII: Charset = StandardCharsets.US_ASCII

    /** IDs of frames fully owned by [TagDocument]; everything else is preserved on write. */
    val MODELED_IDS: Set<String> = setOf(
        "TIT2", "TPE1", "TALB", "TPE2", "TYER", "TDRC",
        "TRCK", "TPOS", "TCON", "COMM", "USLT", "APIC",
    )

    /** ID3v2.2 three character frame IDs mapped to their ID3v2.3 equivalents. */
    private val V22_TO_V23: Map<String, String> = mapOf(
        "BUF" to "RBUF", "CNT" to "PCNT", "COM" to "COMM", "CRA" to "AENC",
        "ETC" to "ETCO", "EQU" to "EQUA", "GEO" to "GEOB", "IPL" to "IPLS",
        "LNK" to "LINK", "MCI" to "MCDI", "MLL" to "MLLT", "PIC" to "APIC",
        "POP" to "POPM", "REV" to "RVRB", "RVA" to "RVAD", "SLT" to "SYLT",
        "STC" to "SYTC", "TAL" to "TALB", "TBP" to "TBPM", "TCM" to "TCOM",
        "TCO" to "TCON", "TCR" to "TCOP", "TDA" to "TDAT", "TDY" to "TDLY",
        "TEN" to "TENC", "TFT" to "TFLT", "TIM" to "TIME", "TKE" to "TKEY",
        "TLA" to "TLAN", "TLE" to "TLEN", "TMT" to "TMED", "TOA" to "TOPE",
        "TOF" to "TOFN", "TOL" to "TOLY", "TOR" to "TORY", "TOT" to "TOAL",
        "TP1" to "TPE1", "TP2" to "TPE2", "TP3" to "TPE3", "TP4" to "TPE4",
        "TPA" to "TPOS", "TPB" to "TPUB", "TRC" to "TSRC", "TRD" to "TRDA",
        "TRK" to "TRCK", "TS2" to "TSO2", "TSA" to "TSOA", "TSC" to "TSOC",
        "TSI" to "TSIZ", "TSP" to "TSOP", "TSS" to "TSSE", "TST" to "TSOT",
        "TT1" to "TIT1", "TT2" to "TIT2", "TT3" to "TIT3", "TXT" to "TEXT",
        "TXX" to "TXXX", "TYE" to "TYER", "UFI" to "UFID", "ULT" to "USLT",
        "WAF" to "WOAF", "WAR" to "WOAR", "WAS" to "WOAS", "WCM" to "WCOM",
        "WCP" to "WCOP", "WPB" to "WPUB", "WXX" to "WXXX",
    )

    /**
     * A frame as stored in the file. [data] is the logical frame data: tag level
     * unsynchronisation and (for v2.4) per-frame unsynchronisation have already
     * been undone. [flags] keeps the original 16 bit flag field.
     */
    class Frame(
        val id: String,
        val major: Int,
        val flags: Int,
        val data: ByteArray,
        val unsyncApplied: Boolean,
    )

    class ParsedTag(
        val major: Int,
        val revision: Int,
        val flags: Int,
        /** Header + body (+ footer for v2.4). */
        val totalBytes: Int,
        val frames: List<Frame>,
    )

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** Parses the ID3v2 tag at the very beginning of [channel], or returns null. */
    fun parse(channel: FileChannel, fileSize: Long): ParsedTag? {
        if (fileSize < HEADER_SIZE) return null
        val header = readAt(channel, 0L, HEADER_SIZE) { "读取 ID3 标签头失败" }
        if (!(header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte())) {
            return null
        }
        val major = header[3].toInt() and 0xFF
        if (major !in 2..4) {
            throw TagException("不支持的 ID3v2 版本：2.$major")
        }
        val revision = header[4].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val size = syncSafeToInt(header, 6)
        if (size < 0 || size > MAX_TAG_SIZE) {
            throw TagException("ID3 标签数据过大（$size 字节），拒绝处理")
        }
        val hasFooter = major == 4 && (flags and 0x10) != 0
        val totalBytes = HEADER_SIZE.toLong() + size + if (hasFooter) FOOTER_SIZE.toLong() else 0L
        if (totalBytes > fileSize) {
            throw TagException("ID3 标签长度超出文件大小，文件可能已损坏")
        }
        if (hasFooter) {
            val footer = readAt(channel, HEADER_SIZE + size.toLong(), 3) { "读取 ID3v2.4 页脚失败" }
            if (!(footer[0] == '3'.code.toByte() && footer[1] == 'D'.code.toByte() && footer[2] == 'I'.code.toByte())) {
                throw TagException("ID3v2.4 页脚缺失或损坏")
            }
        }
        val rawBody = readAt(channel, HEADER_SIZE.toLong(), size) { "读取 ID3 标签内容失败" }
        val tagUnsync = (flags and 0x80) != 0
        val body = if (tagUnsync) deUnsynchronise(rawBody) else rawBody
        val frames = parseFrames(body, major, flags, tagUnsync)
        return ParsedTag(major, revision, flags, totalBytes.toInt(), frames)
    }

    private fun parseFrames(body: ByteArray, major: Int, headerFlags: Int, tagUnsync: Boolean): List<Frame> {
        var position = 0
        if ((headerFlags and 0x40) != 0) {
            position = when (major) {
                2 -> throw TagException("不支持的 ID3v2.2 压缩标签")
                3 -> {
                    if (body.size < 4) throw TagException("ID3v2.3 扩展头损坏")
                    val extSize = beInt(body, 0)
                    if (extSize < 6 || 4 + extSize > body.size) throw TagException("ID3v2.3 扩展头损坏")
                    4 + extSize
                }
                else -> {
                    if (body.size < 4) throw TagException("ID3v2.4 扩展头损坏")
                    val extSize = syncSafeToInt(body, 0)
                    if (extSize < 6 || extSize > body.size) throw TagException("ID3v2.4 扩展头损坏")
                    extSize
                }
            }
        }
        val idLength = if (major == 2) 3 else 4
        val frameHeaderLength = if (major == 2) 6 else 10
        val frames = ArrayList<Frame>()
        while (position + frameHeaderLength <= body.size) {
            if ((body[position].toInt() and 0xFF) == 0) break // padding starts
            val id = String(body, position, idLength, US_ASCII)
            if (!isValidFrameId(id)) {
                // Some writers leave junk after the last frame. Treat it as the
                // end of the frame region instead of failing the whole file.
                break
            }
            val frameSize = when (major) {
                2 -> ((body[position + 3].toInt() and 0xFF) shl 16) or
                    ((body[position + 4].toInt() and 0xFF) shl 8) or
                    (body[position + 5].toInt() and 0xFF)
                3 -> beInt(body, position + 4)
                else -> {
                    for (i in 4..7) {
                        if ((body[position + i].toInt() and 0x80) != 0) {
                            throw TagException("ID3v2.4 帧 $id 的长度字段无效")
                        }
                    }
                    syncSafeToInt(body, position + 4)
                }
            }
            val flags = if (major == 2) 0 else
                (((body[position + 8].toInt() and 0xFF) shl 8) or (body[position + 9].toInt() and 0xFF))
            position += frameHeaderLength
            if (frameSize < 0 || frameSize > body.size - position) {
                throw TagException("ID3 标签帧 $id 长度越界，文件可能已损坏")
            }
            var data = body.copyOfRange(position, position + frameSize)
            position += frameSize
            var unsyncApplied = false
            if (major == 4 && !tagUnsync && (flags and 0x02) != 0) {
                data = deUnsynchronise(data)
                unsyncApplied = true
            }
            val canonicalId = if (major == 2) V22_TO_V23[id] ?: id else id
            frames.add(Frame(canonicalId, major, flags, data, unsyncApplied))
            if (frames.size > MAX_FRAMES) {
                throw TagException("ID3 标签帧数量异常（超过 $MAX_FRAMES 个）")
            }
        }
        return frames
    }

    private fun isValidFrameId(id: String): Boolean {
        if (id.isEmpty()) return false
        for (ch in id) {
            val valid = (ch in 'A'..'Z') || (ch in '0'..'9')
            if (!valid) return false
        }
        return true
    }

    /** Maps parsed frames to the format independent document model. */
    fun toDocument(tag: ParsedTag): TagDocument {
        val frames = tag.frames

        fun text(vararg ids: String): String? {
            for (id in ids) {
                val frame = frames.firstOrNull { it.id == id } ?: continue
                val value = decodeText(frame)
                if (value != null) return value
            }
            return null
        }

        val year = if (tag.major >= 4) text("TDRC", "TYER") else text("TYER", "TDRC")
        val comment = firstTextEntry(frames, "COMM")
        val lyrics = firstTextEntry(frames, "USLT")

        return TagDocument(
            title = text("TIT2"),
            artist = text("TPE1"),
            album = text("TALB"),
            albumArtist = text("TPE2"),
            year = year,
            trackNumber = text("TRCK"),
            discNumber = text("TPOS"),
            genre = text("TCON"),
            comment = comment,
            lyrics = lyrics,
            artwork = resolveArtwork(frames),
        )
    }

    private fun firstTextEntry(frames: List<Frame>, id: String): String? {
        val entries = frames.filter { it.id == id }.mapNotNull { decodeCommentOrLyrics(it) }
        if (entries.isEmpty()) return null
        // Prefer the description-less entry: players and tag editors put the
        // real comment/lyrics there, while named entries are internal metadata.
        val preferred = entries.firstOrNull { it.description.isEmpty() } ?: entries.first()
        return preferred.text
    }

    private fun resolveArtwork(frames: List<Frame>): Artwork? {
        val images = frames.filter { it.id == "APIC" }.mapNotNull { decodeApic(it) }
        if (images.isEmpty()) return null
        val chosen = images.firstOrNull { it.pictureType == 3 } ?: images.first()
        if (chosen.bytes.isEmpty()) return null
        return Artwork(chosen.bytes, chosen.mimeType, "")
    }

    // ------------------------------------------------------------------
    // Frame payload helpers
    // ------------------------------------------------------------------

    /** Frame data with compression/encryption/grouping/data-length prefixes removed. */
    fun decodableData(frame: Frame): ByteArray? {
        if (frame.major == 3) {
            if ((frame.flags and 0x00C0) != 0) return null // compression or encryption
            var data = frame.data
            if ((frame.flags and 0x0020) != 0) {
                if (data.isEmpty()) return null
                data = data.copyOfRange(1, data.size)
            }
            return data
        }
        if (frame.major == 4) {
            if ((frame.flags and 0x000C) != 0) return null // compression or encryption
            var start = 0
            if ((frame.flags and 0x0040) != 0) start += 1 // grouping identity
            if ((frame.flags and 0x0001) != 0) start += 4 // data length indicator
            if (start > frame.data.size) return null
            return frame.data.copyOfRange(start, frame.data.size)
        }
        return frame.data
    }

    fun decodeText(frame: Frame): String? {
        val data = decodableData(frame) ?: return null
        if (data.isEmpty()) return null
        val encoding = data[0].toInt() and 0xFF
        val raw = decodeRaw(encoding, data, 1, data.size - 1)
        return cleanTextValue(raw)
    }

    private fun cleanTextValue(raw: String): String? {
        val trimmed = raw.trimEnd('\u0000', ' ', '\t', '\r', '\n')
        if (trimmed.isEmpty()) return null
        // ID3v2.4 allows multiple values separated by NUL. The document model has
        // one string per field, so join them with "/" (the de-facto convention).
        val parts = trimmed.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.isEmpty()) null else parts.joinToString("/")
    }

    class TextEntry(val description: String, val text: String?)

    fun decodeCommentOrLyrics(frame: Frame): TextEntry? {
        val data = decodableData(frame) ?: return null
        if (data.size < 4) return null
        val encoding = data[0].toInt() and 0xFF
        val terminatorLength = if (encoding == 1 || encoding == 2) 2 else 1
        val terminator = findTerminator(data, 4, terminatorLength) ?: return null
        val description = decodeRaw(encoding, data, 4, terminator - 4).trim()
        val textStart = terminator + terminatorLength
        val rawText = decodeRaw(encoding, data, textStart, data.size - textStart)
        val text = rawText.trimEnd('\u0000', ' ', '\t', '\r', '\n').replace("\u0000", "")
        return TextEntry(description, text.ifEmpty { null })
    }

    class DecodedPicture(val pictureType: Int, val mimeType: String, val bytes: ByteArray)

    fun decodeApic(frame: Frame): DecodedPicture? {
        val data = decodableData(frame) ?: return null
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        var position = 1
        var declaredMime: String
        if (frame.major == 2) {
            if (position + 3 > data.size) return null
            val format = String(data, position, 3, US_ASCII).uppercase(Locale.ROOT)
            position += 3
            declaredMime = when (format) {
                "JPG" -> "image/jpeg"
                "PNG" -> "image/png"
                "GIF" -> "image/gif"
                "BMP" -> "image/bmp"
                else -> ""
            }
        } else {
            val mimeEnd = indexOfZero(data, position) ?: return null
            declaredMime = String(data, position, mimeEnd - position, US_ASCII)
            position = mimeEnd + 1
        }
        if (position >= data.size) return null
        val pictureType = data[position].toInt() and 0xFF
        position += 1
        if (position > data.size) return null
        val terminatorLength = if (encoding == 1 || encoding == 2) 2 else 1
        val descriptionEnd = findTerminator(data, position, terminatorLength) ?: data.size
        val imageStart = minOf(descriptionEnd + terminatorLength, data.size)
        if (imageStart >= data.size) return null
        val bytes = data.copyOfRange(imageStart, data.size)
        if (bytes.isEmpty()) return null
        val mime = normalizeMime(declaredMime, bytes)
        return DecodedPicture(pictureType, mime, bytes)
    }

    private fun normalizeMime(declared: String, bytes: ByteArray): String {
        val mime = declared.trim().lowercase(Locale.ROOT)
        return when {
            mime == "image/jpeg" || mime == "image/jpg" -> "image/jpeg"
            mime == "image/png" -> "image/png"
            mime == "image/webp" -> "image/webp"
            mime.startsWith("image/") -> mime
            else -> Artwork.sniffMimeType(bytes)
        }
    }

    private fun indexOfZero(data: ByteArray, start: Int): Int? {
        var i = start
        while (i < data.size) {
            if ((data[i].toInt() and 0xFF) == 0) return i
            i++
        }
        return null
    }

    private fun findTerminator(data: ByteArray, start: Int, terminatorLength: Int): Int? {
        if (terminatorLength == 1) {
            var i = start
            while (i < data.size) {
                if ((data[i].toInt() and 0xFF) == 0) return i
                i++
            }
            return null
        }
        var i = start
        while (i + 1 < data.size) {
            if ((data[i].toInt() and 0xFF) == 0 && (data[i + 1].toInt() and 0xFF) == 0) return i
            i += 2
        }
        return null
    }

    private fun decodeRaw(encoding: Int, data: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        return when (encoding) {
            0 -> decode(ASCII, data, offset, length)
            1 -> decodeUtf16WithBom(data, offset, length)
            2 -> decode(UTF16BE, data, offset, length)
            3 -> decode(UTF8, data, offset, length)
            else -> decode(UTF8, data, offset, length)
        }
    }

    private fun decodeUtf16WithBom(data: ByteArray, offset: Int, length: Int): String {
        if (length >= 2) {
            val b0 = data[offset].toInt() and 0xFF
            val b1 = data[offset + 1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return decode(UTF16LE, data, offset + 2, length - 2)
            if (b0 == 0xFE && b1 == 0xFF) return decode(UTF16BE, data, offset + 2, length - 2)
        }
        // Lenient fallback for writers that omit the BOM.
        return decode(UTF16LE, data, offset, length)
    }

    private fun decode(charset: Charset, data: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(data, offset, length))
                .toString()
        } catch (_: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------------
    // Low level helpers
    // ------------------------------------------------------------------

    fun syncSafeToInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    fun syncSafeToBytes(value: Int): ByteArray {
        require(value in 0..0x0FFFFFFF) { "value out of syncsafe range: $value" }
        return byteArrayOf(
            ((value ushr 21) and 0x7F).toByte(),
            ((value ushr 14) and 0x7F).toByte(),
            ((value ushr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
    }

    fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    fun beIntToBytes(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    /** Removes the zero byte inserted after 0xFF by the ID3 unsynchronisation scheme. */
    fun deUnsynchronise(input: ByteArray): ByteArray {
        var insertions = 0
        var i = 0
        while (i + 1 < input.size) {
            if ((input[i].toInt() and 0xFF) == 0xFF && (input[i + 1].toInt() and 0xFF) == 0x00) {
                insertions++
                i += 2
            } else {
                i++
            }
        }
        if (insertions == 0) return input
        val output = ByteArray(input.size - insertions)
        var source = 0
        var target = 0
        while (source < input.size) {
            output[target++] = input[source]
            if (source + 1 < input.size &&
                (input[source].toInt() and 0xFF) == 0xFF &&
                (input[source + 1].toInt() and 0xFF) == 0x00
            ) {
                source += 2
            } else {
                source++
            }
        }
        return output
    }

    /** Reads exactly [length] bytes at [position], throwing a Chinese [TagException] on failure. */
    fun readAt(channel: FileChannel, position: Long, length: Int, message: () -> String): ByteArray {
        if (length < 0) throw TagException("读取位置无效")
        if (length == 0) return ByteArray(0)
        val buffer = ByteBuffer.allocate(length)
        var current = position
        var zeroReads = 0
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, current)
            if (read < 0) throw TagException(message() + "：文件意外结束")
            if (read == 0) {
                zeroReads++
                if (zeroReads > 1000) throw TagException(message() + "：读取超时")
            } else {
                zeroReads = 0
                current += read
            }
        }
        return buffer.array()
    }
}
