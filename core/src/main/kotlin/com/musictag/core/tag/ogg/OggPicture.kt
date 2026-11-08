package com.musictag.core.tag.ogg

import com.musictag.core.tag.Artwork
import java.io.ByteArrayOutputStream

/**
 * The FLAC `PICTURE` metadata block body, which is also the payload used by the
 * `METADATA_BLOCK_PICTURE` Vorbis comment found in Ogg Vorbis and Ogg Opus
 * files (the comment value is the base64 encoding of this structure).
 *
 * Layout (all lengths big-endian):
 * ```
 * 32 bits  picture type (3 = front cover)
 * 32 bits  MIME type length, followed by the ASCII MIME type
 * 32 bits  description length, followed by the UTF-8 description
 * 32 bits  width, 32 bits height, 32 bits colour depth, 32 bits colours used
 * 32 bits  picture data length, followed by the picture data
 * ```
 */
internal object FlacPictureBlock {
    const val TYPE_FRONT_COVER = 3

    private const val MIN_BLOCK_SIZE = 32

    class Picture(
        val pictureType: Int,
        val mimeType: String,
        val description: String,
        val data: ByteArray,
    )

    /** Parses a picture block, returning `null` when the structure is not usable. */
    fun parse(bytes: ByteArray): Picture? {
        if (bytes.size < MIN_BLOCK_SIZE) return null
        var position = 0

        fun readUInt32(): Long? {
            if (position + 4 > bytes.size) return null
            val value = ((bytes[position].toLong() and 0xFFL) shl 24) or
                ((bytes[position + 1].toLong() and 0xFFL) shl 16) or
                ((bytes[position + 2].toLong() and 0xFFL) shl 8) or
                (bytes[position + 3].toLong() and 0xFFL)
            position += 4
            return value
        }

        fun readBlob(length: Long): ByteArray? {
            if (length < 0 || length > (bytes.size - position).toLong()) return null
            val blob = bytes.copyOfRange(position, position + length.toInt())
            position += length.toInt()
            return blob
        }

        val pictureType = readUInt32()?.toInt() ?: return null
        val mimeLength = readUInt32() ?: return null
        val mimeBytes = readBlob(mimeLength) ?: return null
        val descriptionLength = readUInt32() ?: return null
        val descriptionBytes = readBlob(descriptionLength) ?: return null
        // width, height, colour depth, number of colours
        repeat(4) { if (readUInt32() == null) return null }
        val dataLength = readUInt32() ?: return null
        val data = readBlob(dataLength) ?: return null
        if (data.isEmpty()) return null

        return Picture(
            pictureType = pictureType,
            mimeType = String(mimeBytes, Charsets.US_ASCII).trim(),
            description = String(descriptionBytes, Charsets.UTF_8),
            data = data,
        )
    }

    /** Builds the picture block body for a front cover. */
    fun encode(artwork: Artwork): ByteArray {
        val output = ByteArrayOutputStream()
        writeUInt32(output, TYPE_FRONT_COVER)

        val mimeBytes = artwork.mimeType.toByteArray(Charsets.US_ASCII)
        writeUInt32(output, mimeBytes.size)
        output.write(mimeBytes)

        val descriptionBytes = artwork.description.toByteArray(Charsets.UTF_8)
        writeUInt32(output, descriptionBytes.size)
        output.write(descriptionBytes)

        // Width, height, colour depth and colours used are unknown (0).
        repeat(4) { writeUInt32(output, 0) }

        writeUInt32(output, artwork.bytes.size)
        output.write(artwork.bytes)
        return output.toByteArray()
    }

    private fun writeUInt32(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }
}
