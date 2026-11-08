package com.musictag.core.tag.mp4

import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException

/** iTunes metadata item atom names (keys inside `moov/udta/meta/ilst`). */
internal object Mp4ItemKeys {
    const val TITLE = "\u00A9nam"
    const val ARTIST = "\u00A9ART"
    const val ALBUM = "\u00A9alb"
    const val ALBUM_ARTIST = "aART"
    const val YEAR = "\u00A9day"
    const val TRACK = "trkn"
    const val DISC = "disk"
    const val GENRE = "\u00A9gen"
    const val COMMENT = "\u00A9cmt"
    const val LYRICS = "\u00A9lyr"
    const val COVER = "covr"

    /** Every ilst key this engine owns; all other keys are preserved verbatim. */
    val MODELED: Set<String> = setOf(
        TITLE, ARTIST, ALBUM, ALBUM_ARTIST, YEAR,
        TRACK, DISC, GENRE, COMMENT, LYRICS, COVER,
    )
}

/** `data` atom well-known type codes. */
private object Mp4DataTypes {
    const val IMPLICIT = 0L
    const val UTF8 = 1L
    const val UTF16BE = 2L
    const val JPEG = 13L
    const val PNG = 14L
}

/**
 * Reads and rebuilds the `ilst` metadata atom.
 *
 * Reading never fails on individual malformed items: unknown or broken items are
 * simply ignored so a partially corrupt tag still yields the readable fields.
 * Writing replaces the modeled keys, keeps every unknown item byte-for-byte and
 * removes modeled keys whose document field is null.
 */
internal object Mp4Ilst {

    /** Standard iTunes metadata handler: `hdlr` with handler type `mdir`/`appl`. */
    val METADATA_HDLR: ByteArray = buildMetadataHandler()

    fun read(ilst: Mp4Node?): TagDocument {
        if (ilst == null) return TagDocument()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var albumArtist: String? = null
        var year: String? = null
        var track: String? = null
        var disc: String? = null
        var genre: String? = null
        var comment: String? = null
        var lyrics: String? = null
        var artwork: Artwork? = null

        for (item in ilst.children) {
            when (item.type) {
                Mp4ItemKeys.TITLE -> if (title == null) title = textValue(item)
                Mp4ItemKeys.ARTIST -> if (artist == null) artist = textValue(item)
                Mp4ItemKeys.ALBUM -> if (album == null) album = textValue(item)
                Mp4ItemKeys.ALBUM_ARTIST -> if (albumArtist == null) albumArtist = textValue(item)
                Mp4ItemKeys.YEAR -> if (year == null) year = textValue(item)
                Mp4ItemKeys.TRACK -> if (track == null) track = pairValue(item)
                Mp4ItemKeys.DISC -> if (disc == null) disc = pairValue(item)
                Mp4ItemKeys.GENRE -> if (genre == null) genre = textValue(item)
                Mp4ItemKeys.COMMENT -> if (comment == null) comment = textValue(item)
                Mp4ItemKeys.LYRICS -> if (lyrics == null) lyrics = textValue(item)
                Mp4ItemKeys.COVER -> if (artwork == null) artwork = coverValue(item)
            }
        }
        return TagDocument(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            year = year,
            trackNumber = track,
            discNumber = disc,
            genre = genre,
            comment = comment,
            // Some writers store the lyrics in ©cmt only; fall back to it as the spec asks.
            lyrics = lyrics ?: comment,
            artwork = artwork,
        )
    }

    /**
     * Builds the complete bytes of a fresh `ilst` box.
     *
     * [existing] is the previous ilst node (null when the file had none). Its
     * unknown children are preserved in order and modeled children are replaced
     * or dropped in place; missing modeled keys are appended in canonical order.
     */
    fun build(existing: Mp4Node?, document: TagDocument): ByteArray {
        val modeled = modeledItems(document)
        val emitted = HashSet<String>()
        val parts = ArrayList<ByteArray>()
        if (existing != null) {
            for (item in existing.children) {
                if (item.type in Mp4ItemKeys.MODELED) {
                    val replacement = modeled[item.type]
                    if (replacement != null && emitted.add(item.type)) parts += replacement
                } else {
                    parts += item.rawBytes()
                }
            }
        }
        for ((key, bytes) in modeled) {
            if (bytes != null && !emitted.contains(key)) {
                parts += bytes
                emitted += key
            }
        }
        return buildBox(TYPE_ILST, parts)
    }

    // ------------------------------------------------------------------ reading

    private class ItemData(val type: Long, val payload: ByteArray)

    private fun dataBoxes(item: Mp4Node): List<ItemData> {
        val result = ArrayList<ItemData>()
        for (child in item.childBoxes()) {
            if (child.type != "data") continue
            val payload = child.payloadBytes()
            // type (4 bytes) + locale (4 bytes) + payload; skip unusably short data atoms.
            if (payload.size < 8) continue
            result += ItemData(Mp4Bytes.u32(payload, 0), payload.copyOfRange(8, payload.size))
        }
        return result
    }

    private fun textValue(item: Mp4Node): String? {
        val data = dataBoxes(item)
        val chosen = data.firstOrNull { it.type == Mp4DataTypes.UTF8 }
            ?: data.firstOrNull { it.type == Mp4DataTypes.IMPLICIT }
            ?: data.firstOrNull { it.type == Mp4DataTypes.UTF16BE }
            ?: data.firstOrNull()
            ?: return null
        val text = when (chosen.type) {
            Mp4DataTypes.UTF16BE -> decodeUtf16(chosen.payload)
            else -> String(chosen.payload, Charsets.UTF_8)
        }
        return text.trimEnd('\u0000').trim().takeIf { it.isNotEmpty() }
    }

    private fun pairValue(item: Mp4Node): String? {
        val data = dataBoxes(item)
        val chosen = data.firstOrNull { it.type == Mp4DataTypes.IMPLICIT } ?: data.firstOrNull() ?: return null
        val payload = chosen.payload
        if (payload.size < 4) return null
        val number = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        if (number <= 0) return null
        val total = if (payload.size >= 6) {
            ((payload[4].toInt() and 0xFF) shl 8) or (payload[5].toInt() and 0xFF)
        } else {
            0
        }
        return if (total > 0) "$number/$total" else "$number"
    }

    private fun coverValue(item: Mp4Node): Artwork? {
        for (data in dataBoxes(item)) {
            if (data.payload.isEmpty()) continue
            val mimeType = when (data.type) {
                Mp4DataTypes.JPEG -> "image/jpeg"
                Mp4DataTypes.PNG -> "image/png"
                else -> Artwork.sniffMimeType(data.payload)
            }
            return Artwork(data.payload, mimeType)
        }
        return null
    }

    private fun decodeUtf16(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
        }
        return String(bytes, Charsets.UTF_16BE)
    }

    // ------------------------------------------------------------------ writing

    /** Canonical key order used when appending missing items. */
    private fun modeledItems(document: TagDocument): LinkedHashMap<String, ByteArray?> {
        val items = LinkedHashMap<String, ByteArray?>()
        items[Mp4ItemKeys.TITLE] = normalizedText(document.title)?.let { textItem(Mp4ItemKeys.TITLE, it) }
        items[Mp4ItemKeys.ARTIST] = normalizedText(document.artist)?.let { textItem(Mp4ItemKeys.ARTIST, it) }
        items[Mp4ItemKeys.ALBUM] = normalizedText(document.album)?.let { textItem(Mp4ItemKeys.ALBUM, it) }
        items[Mp4ItemKeys.ALBUM_ARTIST] =
            normalizedText(document.albumArtist)?.let { textItem(Mp4ItemKeys.ALBUM_ARTIST, it) }
        items[Mp4ItemKeys.YEAR] = normalizedText(document.year)?.let { textItem(Mp4ItemKeys.YEAR, it) }
        items[Mp4ItemKeys.TRACK] = normalizedText(document.trackNumber)?.let { pairItem(Mp4ItemKeys.TRACK, it) }
        items[Mp4ItemKeys.DISC] = normalizedText(document.discNumber)?.let { pairItem(Mp4ItemKeys.DISC, it) }
        items[Mp4ItemKeys.GENRE] = normalizedText(document.genre)?.let { textItem(Mp4ItemKeys.GENRE, it) }
        items[Mp4ItemKeys.COMMENT] = normalizedText(document.comment)?.let { textItem(Mp4ItemKeys.COMMENT, it) }
        items[Mp4ItemKeys.LYRICS] = normalizedText(document.lyrics)?.let { textItem(Mp4ItemKeys.LYRICS, it) }
        items[Mp4ItemKeys.COVER] = document.artwork?.let { coverItem(it) }
        return items
    }

    private fun normalizedText(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun textItem(key: String, value: String): ByteArray {
        val payload = value.toByteArray(Charsets.UTF_8)
        val data = buildBox(
            "data",
            listOf(Mp4Bytes.u32Bytes(Mp4DataTypes.UTF8), Mp4Bytes.u32Bytes(0L), payload),
        )
        return buildBox(key, data)
    }

    private fun pairItem(key: String, value: String): ByteArray {
        val (number, total) = parsePair(value)
        val content = ByteArray(16)
        Mp4Bytes.putU32(content, 0, Mp4DataTypes.IMPLICIT)
        Mp4Bytes.putU32(content, 4, 0L) // locale
        // payload: 2 bytes pad, 2 bytes number, 2 bytes total, 2 bytes pad
        content[10] = (number ushr 8).toByte()
        content[11] = number.toByte()
        content[12] = (total ushr 8).toByte()
        content[13] = total.toByte()
        return buildBox(key, buildBox("data", content))
    }

    private fun parsePair(value: String): Pair<Int, Int> {
        val parts = value.trim().split('/', limit = 2)
        val number = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 0
        val total = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return number.coerceIn(0, 0xFFFF) to total.coerceIn(0, 0xFFFF)
    }

    private fun coverItem(artwork: Artwork): ByteArray {
        val dataType = when (artwork.mimeType.lowercase()) {
            "image/png" -> Mp4DataTypes.PNG
            "image/jpeg" -> Mp4DataTypes.JPEG
            else -> when (Artwork.sniffMimeType(artwork.bytes)) {
                "image/png" -> Mp4DataTypes.PNG
                "image/jpeg" -> Mp4DataTypes.JPEG
                else -> throw TagException(
                    "不支持的封面图片格式：${artwork.mimeType}，M4A 仅支持 JPEG 和 PNG"
                )
            }
        }
        val content = ByteArray(8 + artwork.bytes.size)
        Mp4Bytes.putU32(content, 0, dataType)
        Mp4Bytes.putU32(content, 4, 0L) // locale
        artwork.bytes.copyInto(content, 8)
        return buildBox(Mp4ItemKeys.COVER, buildBox("data", content))
    }

    private fun buildMetadataHandler(): ByteArray {
        // hdlr for metadata: version/flags, pre_defined, handler type 'mdir',
        // reserved 'appl', remaining reserved bytes and an empty name.
        val content = ByteArray(25)
        "mdir".toByteArray(Charsets.ISO_8859_1).copyInto(content, 8)
        "appl".toByteArray(Charsets.ISO_8859_1).copyInto(content, 12)
        return buildBox("hdlr", content)
    }
}
