package com.musictag.core.tag

import java.io.File

/** Thrown when an audio file cannot be parsed or its metadata cannot be written. */
class TagException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Embedded front-cover image.
 *
 * Immutable by convention: callers must not mutate [bytes].
 */
class Artwork(
    val bytes: ByteArray,
    /** Normalized MIME type, e.g. `image/jpeg`, `image/png` or `image/webp`. */
    val mimeType: String,
    val description: String = "",
) {
    init {
        require(bytes.isNotEmpty()) { "Artwork bytes must not be empty" }
    }

    val extension: String
        get() = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }

    fun isSameImage(other: Artwork?): Boolean =
        other != null && other.mimeType == mimeType && other.bytes.contentEquals(bytes)

    override fun equals(other: Any?): Boolean = other is Artwork && isSameImage(other)

    override fun hashCode(): Int = 31 * mimeType.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "Artwork(mimeType=$mimeType, size=${bytes.size})"

    companion object {
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** Best-effort MIME sniffing based on the image signature. */
        fun sniffMimeType(bytes: ByteArray): String {
            if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(JPEG_MAGIC)) return "image/jpeg"
            if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) return "image/png"
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) return "image/webp"
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return "image/jpeg"
            return "image/jpeg"
        }

        fun fromBytes(bytes: ByteArray, description: String = ""): Artwork =
            Artwork(bytes, sniffMimeType(bytes), description)
    }
}

/**
 * Canonical, format independent representation of editable audio metadata.
 *
 * `null` means "this field is not present in the file". An empty string is
 * normalized to `null` by writers, so callers can simply pass `text.ifBlank { null }`.
 */
data class TagDocument(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    val trackNumber: String? = null,
    val discNumber: String? = null,
    val genre: String? = null,
    val comment: String? = null,
    val lyrics: String? = null,
    val artwork: Artwork? = null,
) {
    /** All text fields normalized: blank strings become null. */
    fun normalized(): TagDocument = copy(
        title = title.nilIfBlank(),
        artist = artist.nilIfBlank(),
        album = album.nilIfBlank(),
        albumArtist = albumArtist.nilIfBlank(),
        year = year.nilIfBlank(),
        trackNumber = trackNumber.nilIfBlank(),
        discNumber = discNumber.nilIfBlank(),
        genre = genre.nilIfBlank(),
        comment = comment.nilIfBlank(),
        lyrics = lyrics.nilIfBlank(),
        artwork = artwork,
    )

    /** True when this document contains no editable metadata at all. */
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null && albumArtist == null &&
            year == null && trackNumber == null && discNumber == null && genre == null &&
            comment == null && lyrics == null && artwork == null

    private fun String?.nilIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

/** A container/audio format that can read and rewrite its embedded metadata. */
interface TagFormat {
    /** Human readable format name, e.g. `MP3`. */
    val displayName: String

    /** Lower-case extensions handled by this implementation, without the dot. */
    val extensions: Set<String>

    /** Whether [file] looks like this format (extension and/or magic bytes). */
    fun supports(file: File): Boolean

    /**
     * Reads metadata from [file]. Must return [TagDocument] with nulls when the
     * file is valid but carries no metadata. Throws [TagException] for invalid files.
     */
    fun read(file: File): TagDocument

    /**
     * Rewrites the metadata of [file] so that it matches [document] exactly for
     * every modeled field (null removes the field, null artwork removes the cover).
     * Unknown/unmodeled tags, audio data and technical headers must be preserved.
     */
    fun write(file: File, document: TagDocument)
}
