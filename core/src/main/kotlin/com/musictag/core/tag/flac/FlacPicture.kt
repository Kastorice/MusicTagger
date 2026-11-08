package com.musictag.core.tag.flac

import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/** FLAC PICTURE block picture type for the front cover. */
internal const val PICTURE_TYPE_FRONT_COVER = 3

private const val MAX_MIME_LENGTH = 1024
private const val MAX_DESCRIPTION_LENGTH = 64 * 1024

/** Parsed payload of a FLAC PICTURE metadata block (big endian fields). */
internal class FlacPicture(
    val pictureType: Int,
    val mimeType: String,
    val description: String,
    val width: Int,
    val height: Int,
    val depth: Int,
    val colors: Int,
    val data: ByteArray,
) {
    fun toArtwork(): Artwork = Artwork(data, normalizeMimeType(mimeType, data), description)
}

/** Normalizes a MIME type, falling back to signature sniffing when it is missing. */
internal fun normalizeMimeType(raw: String, data: ByteArray): String {
    var mime = raw.trim().lowercase(Locale.ROOT)
    if (mime.isEmpty()) return Artwork.sniffMimeType(data)
    mime = when (mime) {
        "image/jpg", "image/pjpeg", "image/x-jpg" -> "image/jpeg"
        "image/x-png" -> "image/png"
        else -> mime
    }
    return mime
}

/** Parses PICTURE block payloads. Throws [TagException] for malformed blocks. */
internal object FlacPictureParser {

    fun parse(data: ByteArray): FlacPicture {
        val reader = BeReader(data)
        val type = reader.readU32("图片类型")
        val mimeLength = reader.readU32("MIME 类型长度")
        val mime = String(reader.readBytes(mimeLength, "MIME 类型"), StandardCharsets.UTF_8)
        val descriptionLength = reader.readU32("描述长度")
        val description = String(reader.readBytes(descriptionLength, "描述"), StandardCharsets.UTF_8)
        val width = reader.readU32("图片宽度")
        val height = reader.readU32("图片高度")
        val depth = reader.readU32("颜色深度")
        val colors = reader.readU32("颜色数量")
        val dataLength = reader.readU32("图片数据长度")
        val imageBytes = reader.readBytes(dataLength, "图片数据")
        if (!reader.atEnd()) {
            throw TagException("FLAC 文件已损坏：PICTURE 块尾部存在多余数据")
        }
        if (imageBytes.isEmpty()) {
            throw TagException("FLAC 文件已损坏：PICTURE 块不包含图片数据")
        }
        return FlacPicture(
            pictureType = clampToInt(type),
            mimeType = mime,
            description = description,
            width = clampToInt(width),
            height = clampToInt(height),
            depth = clampToInt(depth),
            colors = clampToInt(colors),
            data = imageBytes,
        )
    }

    private fun clampToInt(value: Long): Int =
        if (value in 0..Int.MAX_VALUE.toLong()) value.toInt() else 0

    private class BeReader(private val data: ByteArray) {
        private var position = 0

        fun atEnd(): Boolean = position == data.size

        fun readU32(what: String): Long {
            if (data.size - position < 4) throw corrupt(what)
            val value = ((data[position].toLong() and 0xFF) shl 24) or
                ((data[position + 1].toLong() and 0xFF) shl 16) or
                ((data[position + 2].toLong() and 0xFF) shl 8) or
                (data[position + 3].toLong() and 0xFF)
            position += 4
            return value
        }

        fun readBytes(length: Long, what: String): ByteArray {
            if (length < 0 || length > (data.size - position).toLong()) throw corrupt(what)
            val result = data.copyOfRange(position, position + length.toInt())
            position += length.toInt()
            return result
        }

        private fun corrupt(what: String): Nothing =
            throw TagException("FLAC 文件已损坏：PICTURE 块中$what 超出块长度")
    }
}

/** Serializes an [Artwork] into a FLAC PICTURE block payload with picture type 3. */
internal object FlacPictureWriter {

    fun build(artwork: Artwork): ByteArray {
        val imageData = artwork.bytes
        if (imageData.isEmpty()) throw TagException("封面图片数据为空，无法写入 FLAC")
        val mime = normalizeMimeType(artwork.mimeType, imageData)
        val mimeBytes = mime.toByteArray(StandardCharsets.UTF_8)
        if (mimeBytes.size > MAX_MIME_LENGTH) {
            throw TagException("封面图片 MIME 类型过长，无法写入 FLAC")
        }
        val descriptionBytes = artwork.description.toByteArray(StandardCharsets.UTF_8)
        if (descriptionBytes.size > MAX_DESCRIPTION_LENGTH) {
            throw TagException("封面图片描述过长，无法写入 FLAC")
        }
        val info = ImageInfo.parse(imageData)
        val total = 4L + 4L + mimeBytes.size + 4L + descriptionBytes.size + 16L + 4L + imageData.size
        if (total > MAX_BLOCK_DATA_SIZE) {
            throw TagException("封面图片过大（超过 16 MiB），无法写入 FLAC")
        }
        val out = ByteArrayOutputStream(total.toInt())
        writeBe32(out, PICTURE_TYPE_FRONT_COVER)
        writeBe32(out, mimeBytes.size)
        out.write(mimeBytes)
        writeBe32(out, descriptionBytes.size)
        out.write(descriptionBytes)
        writeBe32(out, info.width)
        writeBe32(out, info.height)
        writeBe32(out, info.depth)
        writeBe32(out, info.colors)
        writeBe32(out, imageData.size)
        out.write(imageData)
        return out.toByteArray()
    }

    private fun writeBe32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

/** Width/height/depth/colors information extracted from a PNG or JPEG cover image. */
internal class ImageInfo(val width: Int, val height: Int, val depth: Int, val colors: Int) {
    companion object {
        val UNKNOWN = ImageInfo(0, 0, 0, 0)

        fun parse(bytes: ByteArray): ImageInfo = parsePng(bytes) ?: parseJpeg(bytes) ?: UNKNOWN

        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        private fun parsePng(bytes: ByteArray): ImageInfo? {
            if (bytes.size < 29) return null
            for (index in PNG_SIGNATURE.indices) {
                if (bytes[index] != PNG_SIGNATURE[index]) return null
            }
            if (asciiAt(bytes, 12, 4) != "IHDR") return null
            val width = readBe32(bytes, 16)
            val height = readBe32(bytes, 20)
            if (width <= 0 || height <= 0) return null
            val bitDepth = bytes[24].toInt() and 0xFF
            val colorType = bytes[25].toInt() and 0xFF
            val channels = when (colorType) {
                0 -> 1
                2 -> 3
                3 -> 1
                4 -> 2
                6 -> 4
                else -> return null
            }
            val colors = if (colorType == 3) paletteEntryCount(bytes) else 0
            return ImageInfo(width, height, bitDepth * channels, colors)
        }

        /** Scans the first PNG chunks for the PLTE chunk and returns its entry count. */
        private fun paletteEntryCount(bytes: ByteArray): Int {
            var position = 8
            val limit = minOf(bytes.size, 1 shl 20)
            while (position + 12 <= limit) {
                val length = readBe32(bytes, position)
                if (length < 0 || length > bytes.size - position - 12) return 0
                val chunkType = asciiAt(bytes, position + 4, 4)
                when (chunkType) {
                    "PLTE" -> return length / 3
                    "IDAT", "IEND" -> return 0
                }
                position += 12 + length
            }
            return 0
        }

        private fun parseJpeg(bytes: ByteArray): ImageInfo? {
            if (bytes.size < 4) return null
            if ((bytes[0].toInt() and 0xFF) != 0xFF || (bytes[1].toInt() and 0xFF) != 0xD8) return null
            var index = 2
            while (index + 1 < bytes.size) {
                if ((bytes[index].toInt() and 0xFF) != 0xFF) return null
                var marker = bytes[index + 1].toInt() and 0xFF
                while (marker == 0xFF) {
                    index++
                    if (index + 1 >= bytes.size) return null
                    marker = bytes[index + 1].toInt() and 0xFF
                }
                when {
                    marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> index += 2
                    marker == 0xD9 || marker == 0xDA -> return null
                    else -> {
                        if (index + 4 > bytes.size) return null
                        val segmentLength = ((bytes[index + 2].toInt() and 0xFF) shl 8) or
                            (bytes[index + 3].toInt() and 0xFF)
                        if (segmentLength < 2 || index + 2 + segmentLength > bytes.size) return null
                        val isStartOfFrame = marker in 0xC0..0xCF &&
                            marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                        if (isStartOfFrame) {
                            if (segmentLength < 8) return null
                            val precision = bytes[index + 4].toInt() and 0xFF
                            val height = ((bytes[index + 5].toInt() and 0xFF) shl 8) or
                                (bytes[index + 6].toInt() and 0xFF)
                            val width = ((bytes[index + 7].toInt() and 0xFF) shl 8) or
                                (bytes[index + 8].toInt() and 0xFF)
                            val components = bytes[index + 9].toInt() and 0xFF
                            if (width <= 0 || height <= 0) return null
                            return ImageInfo(width, height, precision * components, 0)
                        }
                        index += 2 + segmentLength
                    }
                }
            }
            return null
        }

        private fun asciiAt(bytes: ByteArray, offset: Int, length: Int): String =
            String(bytes, offset, length, StandardCharsets.US_ASCII)

        private fun readBe32(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }
}
