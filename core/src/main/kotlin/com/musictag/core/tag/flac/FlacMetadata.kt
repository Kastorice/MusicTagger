package com.musictag.core.tag.flac

import com.musictag.core.tag.TagException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** FLAC metadata block types (FLAC format specification, section 8). */
internal const val BLOCK_STREAMINFO = 0
internal const val BLOCK_PADDING = 1
internal const val BLOCK_APPLICATION = 2
internal const val BLOCK_SEEKTABLE = 3
internal const val BLOCK_VORBIS_COMMENT = 4
internal const val BLOCK_CUESHEET = 5
internal const val BLOCK_PICTURE = 6

/** A metadata block can store at most 2^24 - 1 bytes (24 bit length field). */
internal const val MAX_BLOCK_DATA_SIZE = 0xFFFFFF

/** Upper bound on the total amount of metadata we buffer in memory. */
internal const val MAX_METADATA_BYTES = 64L * 1024 * 1024

/** Sanity cap for the number of metadata blocks in a single file. */
internal const val MAX_BLOCK_COUNT = 100_000

/** Size of the fresh PADDING block appended by every write. */
internal const val PADDING_BLOCK_SIZE = 4096

internal val FLAC_MAGIC: ByteArray =
    byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())

/** One FLAC metadata block: the type plus its raw data (without the 4 byte header). */
internal class FlacBlock(val type: Int, val data: ByteArray)

/** Parsed FLAC metadata: all blocks in file order, plus the offset where audio frames start. */
internal class FlacStructure(val blocks: List<FlacBlock>, val audioOffset: Long)

/**
 * Reads the metadata block chain of a FLAC stream.
 *
 * Only metadata is loaded into memory (capped by [MAX_METADATA_BYTES]); audio frames are never
 * touched, callers stream them from [FlacStructure.audioOffset] onwards.
 */
internal object FlacParser {

    fun readStructure(file: File): FlacStructure {
        if (!file.isFile) throw TagException("FLAC 文件不存在：${file.name}")
        val declaredLength = file.length()
        try {
            FileInputStream(file).use { raw ->
                val input = BufferedInputStream(raw, 64 * 1024)
                return readStructure(input, file.name, declaredLength)
            }
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException("读取 FLAC 文件失败：${file.name}", e)
        }
    }

    private fun readStructure(input: InputStream, name: String, fileLength: Long): FlacStructure {
        val magic = ByteArray(4)
        if (!readFullyOrEof(input, magic, name)) {
            throw TagException("不是有效的 FLAC 文件（缺少 fLaC 标识）：$name")
        }
        if (!magic.contentEquals(FLAC_MAGIC)) {
            throw TagException("不是有效的 FLAC 文件（缺少 fLaC 标识）：$name")
        }

        val blocks = ArrayList<FlacBlock>()
        var offset = 4L
        var totalData = 0L
        while (true) {
            if (blocks.size >= MAX_BLOCK_COUNT) {
                throw TagException("FLAC 文件已损坏：元数据块数量过多：$name")
            }
            val first = input.read()
            if (first < 0) {
                if (blocks.isEmpty()) {
                    throw TagException("FLAC 文件已损坏：缺少 STREAMINFO 块：$name")
                }
                throw TagException("FLAC 文件已损坏：元数据块不完整（缺少结束标记）：$name")
            }
            val isLast = (first and 0x80) != 0
            val type = first and 0x7F
            if (type == 0x7F) {
                throw TagException("FLAC 文件已损坏：存在非法的元数据块类型（127）：$name")
            }
            val lengthBytes = ByteArray(3)
            if (!readFullyOrEof(input, lengthBytes, name)) {
                throw TagException("FLAC 文件已损坏：元数据块长度字段被截断：$name")
            }
            val length = ((lengthBytes[0].toInt() and 0xFF) shl 16) or
                ((lengthBytes[1].toInt() and 0xFF) shl 8) or
                (lengthBytes[2].toInt() and 0xFF)
            totalData += length
            if (totalData > MAX_METADATA_BYTES) {
                throw TagException("FLAC 元数据超过 64 MiB，无法处理：$name")
            }
            if (offset + 4L + length > fileLength) {
                throw TagException("FLAC 文件已损坏：元数据块长度超出文件大小：$name")
            }
            if (blocks.isNotEmpty() && type == BLOCK_STREAMINFO) {
                throw TagException("FLAC 文件已损坏：STREAMINFO 块不是第一个元数据块：$name")
            }
            val data = ByteArray(length)
            if (!readFullyOrEof(input, data, name)) {
                throw TagException("FLAC 文件已损坏：元数据块数据被截断：$name")
            }
            blocks.add(FlacBlock(type, data))
            offset += 4L + length
            if (isLast) break
        }

        val streamInfo = blocks.first()
        if (streamInfo.type != BLOCK_STREAMINFO) {
            throw TagException("FLAC 文件已损坏：第一个元数据块不是 STREAMINFO：$name")
        }
        if (streamInfo.data.size < 34) {
            throw TagException("FLAC 文件已损坏：STREAMINFO 块长度异常：$name")
        }
        return FlacStructure(blocks.toList(), offset)
    }

    /**
     * Fills [buffer] completely. Returns false when EOF is hit before the first byte is read
     * (clean end of input), throws when the input is truncated in the middle of the buffer.
     */
    private fun readFullyOrEof(input: InputStream, buffer: ByteArray, name: String): Boolean {
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count < 0) {
                if (read == 0) return false
                throw TagException("FLAC 文件已损坏：元数据块被截断：$name")
            }
            read += count
        }
        return true
    }
}
