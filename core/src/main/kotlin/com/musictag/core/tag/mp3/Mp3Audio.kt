package com.musictag.core.tag.mp3

import com.musictag.core.tag.TagException
import java.nio.channels.FileChannel

/** MPEG/ADTS detection helpers shared by [Mp3Format]. */
internal object Mp3Audio {

    /** Size of the probe buffer used to look for the first MPEG/ADTS frame. */
    const val SYNC_SCAN_LIMIT = 64 * 1024

    private val FLAC_MAGIC = "fLaC".toByteArray(Charsets.ISO_8859_1)
    private val OGG_MAGIC = "OggS".toByteArray(Charsets.ISO_8859_1)
    private val FTYP = "ftyp".toByteArray(Charsets.ISO_8859_1)

    /** True when [bytes] contains an MPEG audio / ADTS frame sync at [index]. */
    fun isFrameSync(bytes: ByteArray, index: Int): Boolean {
        if (index < 0 || index + 1 >= bytes.size) return false
        val first = bytes[index].toInt() and 0xFF
        val second = bytes[index + 1].toInt() and 0xFF
        // MPEG audio: 11 sync bits (0xFFE0); ADTS: 12 sync bits (0xFFF0).
        return first == 0xFF && (second and 0xE0) == 0xE0
    }

    /** True when the leading bytes belong to a format that [Mp3Format] must not claim. */
    fun isForeignFormat(head: ByteArray): Boolean {
        if (head.size >= 4) {
            if (startsWith(head, 0, FLAC_MAGIC)) return true
            if (startsWith(head, 0, OGG_MAGIC)) return true
            // ISO base media files (M4A/MP4) carry "ftyp" at offset 4.
            if (head.size >= 8 && startsWith(head, 4, FTYP)) return true
        }
        return false
    }

    private fun startsWith(bytes: ByteArray, offset: Int, magic: ByteArray): Boolean {
        if (offset + magic.size > bytes.size) return false
        for (index in magic.indices) {
            if (bytes[offset + index] != magic[index]) return false
        }
        return true
    }

    /**
     * Validates that [audioStart] really points at audio data. A file that is
     * tag-only (no bytes after the tag) is accepted: it is unusual but harmless.
     *
     * @throws TagException when no frame sync can be found.
     */
    fun validateAudio(channel: FileChannel, fileSize: Long, audioStart: Long) {
        if (audioStart >= fileSize) return
        val remaining = fileSize - audioStart
        val length = minOf(remaining, SYNC_SCAN_LIMIT.toLong()).toInt()
        val bytes = Id3v2.readAt(channel, audioStart, length) { "读取音频数据失败" }
        for (index in 0 until bytes.size - 1) {
            if (isFrameSync(bytes, index)) return
        }
        throw TagException("未找到 MPEG 音频帧，文件可能已损坏或不是 MP3/AAC 格式")
    }
}
