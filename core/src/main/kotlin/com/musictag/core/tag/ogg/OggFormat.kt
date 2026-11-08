package com.musictag.core.tag.ogg

import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import com.musictag.core.tag.TagFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Ogg Vorbis / Ogg Opus tag support.
 *
 * Reading and writing operate on the Ogg page layer and the Vorbis-comment
 * header packets only; audio pages are copied byte for byte (their sequence
 * number and CRC are adjusted when the header page count changes). See
 * `docs/spec-tag-engine.md` for the contract.
 */
object OggFormat : TagFormat {
    override val displayName: String = "OGG / OPUS"
    override val extensions: Set<String> = setOf("ogg", "oga", "opus")

    private const val IO_BUFFER_SIZE = 64 * 1024

    override fun supports(file: File): Boolean {
        if (file.extension.lowercase() in extensions) return true
        if (!file.isFile || file.length() < 4L) return false
        return try {
            FileInputStream(file).use { input ->
                val capture = ByteArray(4)
                var read = 0
                while (read < capture.size) {
                    val count = input.read(capture, read, capture.size - read)
                    if (count < 0) return false
                    read += count
                }
                capture.contentEquals(OGG_CAPTURE)
            }
        } catch (_: IOException) {
            false
        }
    }

    override fun read(file: File): TagDocument {
        if (!file.isFile) throw TagException("文件不存在：${file.name}")
        try {
            FileInputStream(file).use { rawInput ->
                BufferedInputStream(rawInput, IO_BUFFER_SIZE).use { input ->
                    val headerInfo = OggHeaderParser.parse(input)
                    return OggTagMapper.toDocument(headerInfo.commentBlock)
                }
            }
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException("读取 Ogg 文件失败：${e.message}", e)
        }
    }

    override fun write(file: File, document: TagDocument) {
        if (!file.isFile) throw TagException("文件不存在：${file.name}")
        val normalized = document.normalized()

        val headerInfo: OggHeaderInfo
        try {
            FileInputStream(file).use { rawInput ->
                BufferedInputStream(rawInput, IO_BUFFER_SIZE).use { input ->
                    headerInfo = OggHeaderParser.parse(input)
                }
            }
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException("读取 Ogg 文件失败：${e.message}", e)
        }

        val hasAudioPages = file.length() > headerInfo.audioOffset
        val commentBlock = OggTagMapper.toCommentBlock(headerInfo.commentBlock, normalized)
        val commentPacket = commentBlock.encode(headerInfo.codec)
        val extraHeaderPackets = if (headerInfo.codec == OggCodec.VORBIS) {
            listOf(commentPacket, headerInfo.setupPacket!!)
        } else {
            listOf(commentPacket)
        }
        val headerPages = OggHeaderWriter.buildHeaderPages(
            serialNumber = headerInfo.serialNumber,
            identificationPacket = headerInfo.identificationPacket,
            extraHeaderPackets = extraHeaderPackets,
            eosOnLastPage = !hasAudioPages && headerInfo.lastHeaderPageHadEos,
        )

        val target = file.absoluteFile
        val directory: File = target.parentFile
            ?: throw TagException("无法确定 Ogg 文件所在目录：${target.name}")
        var temporaryFile: File? = null
        try {
            temporaryFile = Files.createTempFile(directory.toPath(), ".${target.name}.", ".oggtmp").toFile()
            FileOutputStream(temporaryFile).use { rawOutput ->
                val output = BufferedOutputStream(rawOutput, IO_BUFFER_SIZE)
                for (page in headerPages) output.write(page)
                if (hasAudioPages) {
                    copyAudioPages(file, headerInfo, output, headerPages.size)
                }
                output.flush()
                rawOutput.fd.sync()
            }
            replaceAtomically(temporaryFile.toPath(), target.toPath())
            temporaryFile = null
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException("写入 Ogg 文件失败：${e.message}", e)
        } finally {
            temporaryFile?.let { Files.deleteIfExists(it.toPath()) }
        }
    }

    /**
     * Copies every audio page of [source] to [output], rewriting only the page
     * sequence numbers (and therefore the checksums) when the number of header
     * pages changed. Granule positions, flags, serial number, lacing tables and
     * payload bytes stay untouched.
     */
    private fun copyAudioPages(
        source: File,
        headerInfo: OggHeaderInfo,
        output: OutputStream,
        newHeaderPageCount: Int,
    ) {
        FileInputStream(source).use { rawInput ->
            BufferedInputStream(rawInput, IO_BUFFER_SIZE).use { input ->
                skipFully(input, headerInfo.audioOffset)

                var offset = headerInfo.audioOffset
                var audioPageIndex = 0L
                val sequenceNumbersChange = newHeaderPageCount != headerInfo.headerPageCount

                while (true) {
                    val page = OggPageCodec.read(input, offset, verifyCrc = false) ?: break
                    offset += page.raw.size

                    if (page.serialNumber != headerInfo.serialNumber) {
                        throw TagException("暂不支持串联或复用的 Ogg 流：音频区域检测到其它逻辑流（页面序列号不一致）")
                    }
                    if (page.isBos) {
                        throw TagException("暂不支持串联或复用的 Ogg 流：音频区域检测到新的逻辑流（BOS 页面）")
                    }
                    if (audioPageIndex == 0L && page.isContinuation) {
                        throw TagException("Ogg 音频页结构异常：第一个音频页带有继续标记")
                    }

                    if (sequenceNumbersChange) {
                        output.write(OggPageCodec.resequencePage(page.raw, newHeaderPageCount + audioPageIndex))
                    } else {
                        output.write(page.raw)
                    }
                    audioPageIndex++
                }
            }
        }
    }

    /** Skips exactly [byteCount] bytes or fails with a [TagException]. */
    private fun skipFully(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            if (input.read() < 0) {
                throw TagException("Ogg 文件被截断：无法定位音频数据")
            }
            remaining--
        }
    }

    /**
     * Replaces [target] with [temporary] atomically when the file system supports
     * it, and falls back to a plain replace (which is still crash-safe in the
     * sense that the original file is only touched at the very end).
     */
    private fun replaceAtomically(temporary: Path, target: Path) {
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (_: AtomicMoveNotSupportedException) {
            // Fall through to the non-atomic replacement below.
        } catch (_: FileAlreadyExistsException) {
            // Some providers ignore REPLACE_EXISTING for atomic moves.
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    }
}

private val OGG_CAPTURE: ByteArray = "OggS".toByteArray(Charsets.US_ASCII)
