package com.musictag.core.tag.ogg

import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import java.util.Base64

/**
 * Maps between the generic [TagDocument] model and Vorbis comments (used by both
 * Ogg Vorbis and Ogg Opus).
 *
 * Cover art is stored as `METADATA_BLOCK_PICTURE` (a base64 encoded FLAC
 * PICTURE block, see [FlacPictureBlock]). The legacy `COVERART`/`COVERARTMIME`
 * pair is accepted when reading, but only `METADATA_BLOCK_PICTURE` is written.
 */
internal object OggTagMapper {

    private const val KEY_TITLE = "TITLE"
    private const val KEY_ARTIST = "ARTIST"
    private const val KEY_ALBUM = "ALBUM"
    private const val KEY_ALBUM_ARTIST = "ALBUMARTIST"
    private const val KEY_ALBUM_ARTIST_SPACED = "ALBUM ARTIST"
    private const val KEY_DATE = "DATE"
    private const val KEY_YEAR = "YEAR"
    private const val KEY_TRACK_NUMBER = "TRACKNUMBER"
    private const val KEY_TRACK_TOTAL = "TRACKTOTAL"
    private const val KEY_DISC_NUMBER = "DISCNUMBER"
    private const val KEY_DISC_TOTAL = "DISCTOTAL"
    private const val KEY_GENRE = "GENRE"
    private const val KEY_COMMENT = "COMMENT"
    private const val KEY_DESCRIPTION = "DESCRIPTION"
    private const val KEY_LYRICS = "LYRICS"
    private const val KEY_LYRICS_ALT = "UNSYNCEDLYRICS"
    private const val KEY_PICTURE = "METADATA_BLOCK_PICTURE"
    private const val KEY_COVER_ART = "COVERART"
    private const val KEY_COVER_ART_MIME = "COVERARTMIME"

    /** Keys that this format owns: they are dropped and rewritten on every write. */
    private val MODELED_KEYS: Set<String> = setOf(
        KEY_TITLE, KEY_ARTIST, KEY_ALBUM, KEY_ALBUM_ARTIST, KEY_ALBUM_ARTIST_SPACED,
        KEY_DATE, KEY_YEAR, KEY_TRACK_NUMBER, KEY_TRACK_TOTAL, "TOTALTRACKS",
        KEY_DISC_NUMBER, KEY_DISC_TOTAL, "TOTALDISCS",
        KEY_GENRE, KEY_COMMENT, KEY_DESCRIPTION, KEY_LYRICS, KEY_LYRICS_ALT,
        KEY_PICTURE, KEY_COVER_ART, KEY_COVER_ART_MIME,
    )

    // ---------------------------------------------------------------- reading

    fun toDocument(block: CommentBlock): TagDocument {
        val entries = block.comments.mapNotNull { CommentEntry.parse(it) }

        fun firstValue(vararg keys: String): String? {
            for (key in keys) {
                for (entry in entries) {
                    if (!entry.keyEquals(key)) continue
                    if (entry.value.isNotEmpty()) return entry.value
                }
            }
            return null
        }

        var trackNumber = firstValue(KEY_TRACK_NUMBER)
        val trackTotal = firstValue(KEY_TRACK_TOTAL, "TOTALTRACKS")
        if (trackNumber != null && trackTotal != null && !trackNumber.contains('/')) {
            trackNumber = "$trackNumber/$trackTotal"
        }

        var discNumber = firstValue(KEY_DISC_NUMBER)
        val discTotal = firstValue(KEY_DISC_TOTAL, "TOTALDISCS")
        if (discNumber != null && discTotal != null && !discNumber.contains('/')) {
            discNumber = "$discNumber/$discTotal"
        }

        return TagDocument(
            title = firstValue(KEY_TITLE),
            artist = firstValue(KEY_ARTIST),
            album = firstValue(KEY_ALBUM),
            albumArtist = firstValue(KEY_ALBUM_ARTIST, KEY_ALBUM_ARTIST_SPACED),
            year = firstValue(KEY_DATE, KEY_YEAR),
            trackNumber = trackNumber,
            discNumber = discNumber,
            genre = firstValue(KEY_GENRE),
            comment = firstValue(KEY_COMMENT, KEY_DESCRIPTION),
            lyrics = firstValue(KEY_LYRICS, KEY_LYRICS_ALT),
            artwork = readArtwork(entries),
        )
    }

    private fun readArtwork(entries: List<CommentEntry>): Artwork? {
        var firstPicture: Artwork? = null
        for (entry in entries) {
            if (!entry.keyEquals(KEY_PICTURE)) continue
            val pictureBytes = decodeBase64(entry.value) ?: continue
            val picture = FlacPictureBlock.parse(pictureBytes) ?: continue
            val artwork = Artwork(
                bytes = picture.data,
                mimeType = picture.mimeType.ifBlank { Artwork.sniffMimeType(picture.data) },
                description = picture.description,
            )
            if (picture.pictureType == FlacPictureBlock.TYPE_FRONT_COVER) return artwork
            if (firstPicture == null) firstPicture = artwork
        }
        if (firstPicture != null) return firstPicture

        // Legacy COVERART/COVERARTMIME pair.
        val legacyData = entries.firstOrNull { it.keyEquals(KEY_COVER_ART) }?.let { decodeBase64(it.value) }
        if (legacyData != null && legacyData.isNotEmpty()) {
            val declaredMime = entries.firstOrNull { it.keyEquals(KEY_COVER_ART_MIME) }?.value
            val mime = if (declaredMime != null && declaredMime.startsWith("image/")) {
                declaredMime.trim()
            } else {
                Artwork.sniffMimeType(legacyData)
            }
            return Artwork(legacyData, mime, "")
        }
        return null
    }

    private fun decodeBase64(value: String): ByteArray? {
        // Some taggers wrap METADATA_BLOCK_PICTURE values over several lines, so
        // whitespace inside the payload is ignored while decoding.
        val cleaned = value.filterNot { it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        return try {
            Base64.getDecoder().decode(cleaned)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    // ---------------------------------------------------------------- writing

    /**
     * Builds the comment list for [document], keeping every unmodeled comment
     * (including duplicate keys and non-`KEY=value` entries) in its original order
     * and appending the modeled fields afterwards.
     */
    fun toCommentBlock(original: CommentBlock, document: TagDocument): CommentBlock {
        val normalized = document.normalized()
        val kept = original.comments.filterNot { raw ->
            val key = CommentEntry.keyOf(raw) ?: return@filterNot false
            key.uppercase() in MODELED_KEYS
        }

        val comments = ArrayList<String>(kept.size + 16)
        comments.addAll(kept)

        fun add(key: String, value: String?) {
            if (!value.isNullOrEmpty()) comments.add("$key=$value")
        }

        add(KEY_TITLE, normalized.title)
        add(KEY_ARTIST, normalized.artist)
        add(KEY_ALBUM, normalized.album)
        add(KEY_ALBUM_ARTIST, normalized.albumArtist)
        add(KEY_DATE, normalized.year)
        addNumberPair(comments, normalized.trackNumber, KEY_TRACK_NUMBER, KEY_TRACK_TOTAL)
        addNumberPair(comments, normalized.discNumber, KEY_DISC_NUMBER, KEY_DISC_TOTAL)
        add(KEY_GENRE, normalized.genre)
        add(KEY_COMMENT, normalized.comment)
        add(KEY_LYRICS, normalized.lyrics)

        normalized.artwork?.let { artwork ->
            val block = FlacPictureBlock.encode(artwork)
            comments.add("$KEY_PICTURE=" + Base64.getEncoder().encodeToString(block))
        }

        return CommentBlock(
            vendor = original.vendor,
            comments = comments,
            trailingData = original.trailingData,
        )
    }

    /** Writes `TRACKNUMBER`/`TRACKTOTAL` (or the disc equivalent) from an `n/total` string. */
    private fun addNumberPair(comments: MutableList<String>, value: String?, numberKey: String, totalKey: String) {
        if (value.isNullOrEmpty()) return
        val separator = value.indexOf('/')
        if (separator < 0) {
            comments.add("$numberKey=${value.trim()}")
            return
        }
        val number = value.substring(0, separator).trim()
        val total = value.substring(separator + 1).trim()
        if (number.isNotEmpty()) comments.add("$numberKey=$number")
        if (total.isNotEmpty()) comments.add("$totalKey=$total")
    }
}
