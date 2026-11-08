package com.musictag.core.tag.mp3

import com.musictag.core.tag.TagDocument
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel

/**
 * Legacy ID3v1 / ID3v1.1 trailer support (`TAG` + 128 bytes at end of file).
 *
 * ID3v1 is only used as a read fallback when the file has no ID3v2 tag. On write
 * the trailer is always removed: it cannot represent Unicode and would otherwise
 * shadow the freshly written ID3v2 values in older players.
 */
internal object Id3v1 {

    const val SIZE = 128

    /** Standard ID3v1 genre list (0..79); 255 means "no genre". */
    private val GENRES = listOf(
        "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop",
        "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap",
        "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
        "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance",
        "Classical", "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
        "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock",
        "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
        "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
        "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi",
        "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock",
    )

    /** True when the file ends with an ID3v1 trailer (and the trailer is after the audio). */
    fun exists(channel: FileChannel, fileSize: Long, audioStart: Long): Boolean {
        if (fileSize < SIZE) return false
        if (audioStart > fileSize - SIZE) return false
        val tag = try {
            Id3v2.readAt(channel, fileSize - SIZE, 3) { "读取 ID3v1 标签失败" }
        } catch (_: Exception) {
            return false
        }
        return tag[0] == 'T'.code.toByte() && tag[1] == 'A'.code.toByte() && tag[2] == 'G'.code.toByte()
    }

    /** Reads the trailer as a fallback document; returns null when there is none. */
    fun read(channel: FileChannel, fileSize: Long, audioStart: Long): TagDocument? {
        if (!exists(channel, fileSize, audioStart)) return null
        val bytes = Id3v2.readAt(channel, fileSize - SIZE, SIZE) { "读取 ID3v1 标签失败" }
        val title = decodeField(bytes, 3, 30)
        val artist = decodeField(bytes, 33, 30)
        val album = decodeField(bytes, 63, 30)
        val year = decodeField(bytes, 93, 4)
        var commentLength = 30
        var trackNumber: String? = null
        // ID3v1.1 stores the track number in byte 29 of the comment when byte 28 is NUL.
        if (bytes[125].toInt() == 0 && bytes[126].toInt() != 0) {
            commentLength = 28
            trackNumber = (bytes[126].toInt() and 0xFF).toString()
        }
        val comment = decodeField(bytes, 97, commentLength)
        val genreByte = bytes[127].toInt() and 0xFF
        val genre = GENRES.getOrNull(genreByte)
        return TagDocument(
            title = title,
            artist = artist,
            album = album,
            year = year,
            trackNumber = trackNumber,
            genre = genre,
            comment = comment,
        )
    }

    private fun decodeField(bytes: ByteArray, offset: Int, length: Int): String? {
        val raw = String(bytes, offset, length, StandardCharsets.ISO_8859_1)
        val value = raw.trimEnd('\u0000', ' ', '\t', '\r', '\n').trim()
        return value.ifEmpty { null }
    }
}
