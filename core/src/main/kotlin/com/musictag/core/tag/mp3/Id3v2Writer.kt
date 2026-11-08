package com.musictag.core.tag.mp3

import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Serializes a [TagDocument] back into an ID3v2.3 / ID3v2.4 tag.
 *
 * Encoding policy (see docs/spec-tag-engine.md):
 *  - ID3v2.3: ISO-8859-1 when every character is representable, otherwise
 *    UTF-16LE with a leading BOM (encoding 1). UTF-8 is never written in a
 *    v2.3 tag because many players mis-decode it.
 *  - ID3v2.4: UTF-8 (encoding 3).
 *
 * Frames that the document does not model are re-emitted from their parsed
 * form, so their IDs, flag bytes and payload stay byte-for-byte identical.
 */
internal object Id3v2Writer {

    private val ISO: Charset = StandardCharsets.ISO_8859_1
    private val UTF8: Charset = StandardCharsets.UTF_8
    private val UTF16LE: Charset = StandardCharsets.UTF_16LE
    private val US_ASCII: Charset = StandardCharsets.US_ASCII

    private fun isLatin1Representable(value: String): Boolean = try {
        ISO.newEncoder().canEncode(value)
    } catch (_: Exception) {
        false
    }

    /**
     * Builds the complete tag (header + frames + padding) for [document].
     *
     * @param sourceMajor major version of the existing tag (2, 3 or 4); v2.2
     *   input is upgraded to v2.3 and a missing tag defaults to v2.3.
     * @param preserved frames that are not modeled by the document (unknown
     *   frames such as TXXX, TSSE, ...) in their original order.
     */
    fun buildTag(sourceMajor: Int, document: TagDocument, preserved: List<Id3v2.Frame>): ByteArray {
        val major = if (sourceMajor == 4) 4 else 3
        val body = ByteArrayOutputStream(8192)

        writeTextFrame(body, major, "TIT2", document.title)
        writeTextFrame(body, major, "TPE1", document.artist)
        writeTextFrame(body, major, "TALB", document.album)
        writeTextFrame(body, major, "TPE2", document.albumArtist)
        if (major == 4) {
            writeTextFrame(body, major, "TDRC", document.year)
        } else {
            writeTextFrame(body, major, "TYER", document.year)
        }
        writeTextFrame(body, major, "TRCK", document.trackNumber)
        writeTextFrame(body, major, "TPOS", document.discNumber)
        writeTextFrame(body, major, "TCON", document.genre)
        document.comment?.let { writeCommentFrame(body, major, "COMM", it) }
        document.lyrics?.let { writeCommentFrame(body, major, "USLT", it) }
        document.artwork?.let { writeApicFrame(body, major, it) }

        for (frame in preserved) {
            writePreservedFrame(body, major, frame)
        }

        val frameBytes = body.size()
        if (frameBytes > Id3v2.MAX_TAG_SIZE - Id3v2.PADDING_SIZE) {
            throw TagException("标签数据过大（$frameBytes 字节），拒绝写入")
        }
        body.write(ByteArray(Id3v2.PADDING_SIZE))
        val bodyBytes = body.toByteArray()
        if (bodyBytes.size > Id3v2.MAX_TAG_SIZE) {
            throw TagException("标签数据过大（${bodyBytes.size} 字节），拒绝写入")
        }

        val tag = ByteArrayOutputStream(Id3v2.HEADER_SIZE + bodyBytes.size)
        tag.write('I'.code)
        tag.write('D'.code)
        tag.write('3'.code)
        tag.write(major)
        tag.write(0) // revision
        tag.write(0) // flags: no unsynchronisation, no extended header, no footer
        tag.write(Id3v2.syncSafeToBytes(bodyBytes.size))
        tag.write(bodyBytes)
        return tag.toByteArray()
    }

    // ------------------------------------------------------------------
    // Individual frames
    // ------------------------------------------------------------------

    private fun writeTextFrame(out: ByteArrayOutputStream, major: Int, id: String, value: String?) {
        if (value.isNullOrEmpty()) return
        writeFrame(out, major, id, 0, textPayload(major, value))
    }

    private fun textPayload(major: Int, value: String): ByteArray {
        val text = sanitize(value)
        if (major == 4) {
            val encoded = text.toByteArray(UTF8)
            val payload = ByteArray(1 + encoded.size + 1) // encoding byte + UTF-8 + NUL
            payload[0] = 3
            System.arraycopy(encoded, 0, payload, 1, encoded.size)
            return payload
        }
        if (isLatin1Representable(text)) {
            val encoded = text.toByteArray(ISO)
            val payload = ByteArray(1 + encoded.size + 1)
            payload[0] = 0
            System.arraycopy(encoded, 0, payload, 1, encoded.size)
            return payload
        }
        val out = ByteArrayOutputStream(text.length * 2 + 6)
        out.write(1) // UTF-16 with BOM
        out.write(0xFF)
        out.write(0xFE)
        out.write(text.toByteArray(UTF16LE))
        out.write(0)
        out.write(0)
        return out.toByteArray()
    }

    private fun writeCommentFrame(out: ByteArrayOutputStream, major: Int, id: String, value: String) {
        writeFrame(out, major, id, 0, commentPayload(major, value))
    }

    private fun commentPayload(major: Int, value: String): ByteArray {
        val text = sanitize(value)
        val encoding: Int
        val charset: Charset
        when {
            major == 4 -> {
                encoding = 3
                charset = UTF8
            }
            isLatin1Representable(text) -> {
                encoding = 0
                charset = ISO
            }
            else -> {
                encoding = 1
                charset = UTF16LE
            }
        }
        val out = ByteArrayOutputStream(text.length * 2 + 16)
        out.write(encoding)
        out.write("und".toByteArray(US_ASCII))
        if (encoding == 1) {
            // empty content descriptor: a UTF-16 terminator without BOM
            out.write(0)
            out.write(0)
        } else {
            out.write(0)
        }
        if (encoding == 1) {
            out.write(0xFF)
            out.write(0xFE)
        }
        out.write(text.toByteArray(charset))
        return out.toByteArray()
    }

    private fun writeApicFrame(out: ByteArrayOutputStream, major: Int, artwork: Artwork) {
        if (artwork.bytes.size > Id3v2.MAX_TAG_SIZE) {
            throw TagException("封面图片过大（${artwork.bytes.size} 字节），拒绝写入")
        }
        val mime = artworkMime(artwork)
        val payload = ByteArrayOutputStream(artwork.bytes.size + 32)
        payload.write(0) // ISO-8859-1: MIME + description are plain ASCII
        payload.write(mime.toByteArray(ISO))
        payload.write(0)
        payload.write(3) // picture type: front cover
        payload.write(0) // empty description
        payload.write(artwork.bytes)
        writeFrame(out, major, "APIC", 0, payload.toByteArray())
    }

    private fun artworkMime(artwork: Artwork): String {
        val declared = artwork.mimeType.trim().lowercase(Locale.ROOT)
        return when {
            declared == "image/jpeg" || declared == "image/jpg" -> "image/jpeg"
            declared == "image/png" -> "image/png"
            declared == "image/webp" -> "image/webp"
            else -> Artwork.sniffMimeType(artwork.bytes)
        }
    }

    // ------------------------------------------------------------------
    // Preserved frames
    // ------------------------------------------------------------------

    private fun writePreservedFrame(out: ByteArrayOutputStream, major: Int, frame: Id3v2.Frame) {
        // v2.2-only frame IDs that have no v2.3 equivalent cannot be represented
        // after the v2.2 -> v2.3 upgrade and are dropped.
        if (frame.id.length != 4) return
        var flags = if (frame.major == 2) 0 else frame.flags and 0xFFFF
        if (frame.unsyncApplied) {
            // The payload was de-unsynchronised while parsing; it is emitted as
            // plain data, so the v2.4 per-frame unsynchronisation bit must go.
            flags = flags and 0x02.inv() and 0xFFFF
        }
        writeFrame(out, major, frame.id, flags, frame.data)
    }

    private fun writeFrame(out: ByteArrayOutputStream, major: Int, id: String, flags: Int, data: ByteArray) {
        require(id.length == 4) { "frame id must be 4 characters: $id" }
        if (major == 4 && data.size > 0x0FFFFFFF) {
            throw TagException("标签帧 $id 过大（${data.size} 字节），拒绝写入")
        }
        out.write(id.toByteArray(US_ASCII))
        if (major == 4) {
            out.write(Id3v2.syncSafeToBytes(data.size))
        } else {
            out.write(Id3v2.beIntToBytes(data.size))
        }
        out.write((flags ushr 8) and 0xFF)
        out.write(flags and 0xFF)
        out.write(data)
    }

    private fun sanitize(value: String): String = value.replace("\u0000", "")
}
