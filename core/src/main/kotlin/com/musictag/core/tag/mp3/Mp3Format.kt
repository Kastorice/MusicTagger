package com.musictag.core.tag.mp3

import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import com.musictag.core.tag.TagFormat
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * MP3 (MPEG-1/2 audio layer III) and raw ADTS AAC tag support.
 *
 * MP3 files carry metadata in an ID3v2 tag in front of the audio stream; raw
 * ADTS `.aac` files use exactly the same layout, so both are handled here.
 *
 * Design notes:
 *  - `read` accepts ID3v2.2/v2.3/v2.4 (unsynchronisation, extended header,
 *    v2.4 footer) and falls back to an ID3v1 trailer only when no ID3v2 tag is
 *    present. A file without any tag yields an empty document.
 *  - `write` preserves the original major version (v2.2 is upgraded to v2.3,
 *    a file without a tag gets v2.3), rewrites every modeled frame, keeps all
 *    unmodeled frames byte-for-byte and copies the audio payload unchanged.
 *    A v2.4 footer is not re-emitted (it is optional), duplicated leading ID3v2
 *    tags are dropped, and ID3v1 trailers are discarded on write so they cannot
 *    shadow the new values. An empty document still leaves a valid empty ID3v2
 *    tag (version preserved), so re-reading yields an empty [TagDocument].
 *  - The rewrite is crash safe: a temp file is created next to the target,
 *    fsynced, and atomically moved over the original.
 */
object Mp3Format : TagFormat {

    override val displayName: String = "MP3"

    /** `aac` is the raw ADTS stream flavour: an ID3v2 tag is simply prepended. */
    override val extensions: Set<String> = setOf("mp3", "mpga", "aac")

    private const val COPY_BUFFER_SIZE = 1024 * 1024
    private const val MAX_LEADING_TAGS = 8

    override fun supports(file: File): Boolean {
        if (!file.isFile) return false
        val extensionMatches = file.extension.lowercase(Locale.ROOT) in extensions
        val head = readHead(file)
        if (head != null && head.isNotEmpty()) {
            if (Mp3Audio.isForeignFormat(head)) return false
            if (head.size >= 3 &&
                head[0] == 'I'.code.toByte() &&
                head[1] == 'D'.code.toByte() &&
                head[2] == '3'.code.toByte()
            ) {
                return true
            }
            if (Mp3Audio.isFrameSync(head, 0)) return true
        }
        return extensionMatches
    }

    @Throws(TagException::class)
    override fun read(file: File): TagDocument {
        if (!file.isFile) throw TagException("文件不存在：${file.name}")
        try {
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val fileSize = channel.size()
                if (fileSize <= 0L) throw TagException("文件为空，不是有效的 MP3/AAC 文件")
                val tag = Id3v2.parse(channel, fileSize)
                val audioStart = if (tag != null) {
                    skipTrailingTags(channel, tag.totalBytes.toLong(), fileSize)
                } else {
                    0L
                }
                Mp3Audio.validateAudio(channel, fileSize, audioStart)
                if (tag != null) return Id3v2.toDocument(tag)
                return Id3v1.read(channel, fileSize, audioStart) ?: TagDocument()
            }
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException("无法读取 ${file.name} 的 ID3 标签：${e.message}", e)
        } catch (e: Exception) {
            throw TagException("读取 ${file.name} 的 ID3 标签失败：${e.message}", e)
        }
    }

    @Throws(TagException::class)
    override fun write(file: File, document: TagDocument) {
        if (!file.isFile) throw TagException("文件不存在：${file.name}")
        val normalized = document.normalized()
        try {
            var plan: WritePlan? = null
            FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
                val fileSize = channel.size()
                if (fileSize <= 0L) throw TagException("文件为空，不是有效的 MP3/AAC 文件")
                val existing = Id3v2.parse(channel, fileSize)
                val audioStart = if (existing != null) {
                    skipTrailingTags(channel, existing.totalBytes.toLong(), fileSize)
                } else {
                    0L
                }
                Mp3Audio.validateAudio(channel, fileSize, audioStart)
                val hasId3v1 = Id3v1.exists(channel, fileSize, audioStart)
                if (normalized.isEmpty && existing == null && !hasId3v1) {
                    // Nothing to remove: avoid a pointless rewrite of the audio.
                    return
                }
                val audioEnd = if (hasId3v1) fileSize - Id3v1.SIZE else fileSize
                if (audioEnd < audioStart) {
                    throw TagException("文件结构异常：标签区域超出了文件末尾")
                }
                val sourceMajor = existing?.major ?: 3
                val preserved = existing?.frames.orEmpty()
                    .filter { it.id !in Id3v2.MODELED_IDS }
                val tagBytes = Id3v2Writer.buildTag(sourceMajor, normalized, preserved)
                plan = WritePlan(audioStart, audioEnd, tagBytes)
            }
            val currentPlan = plan ?: return
            writeAtomically(file, currentPlan)
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException("无法写入 ${file.name} 的 ID3 标签：${e.message}", e)
        } catch (e: Exception) {
            throw TagException("写入 ${file.name} 的 ID3 标签失败：${e.message}", e)
        }
    }

    // ------------------------------------------------------------------
    // File structure helpers
    // ------------------------------------------------------------------

    private class WritePlan(
        val audioStart: Long,
        val audioEnd: Long,
        val tagBytes: ByteArray,
    )

    private fun readHead(file: File): ByteArray? = try {
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val length = minOf(channel.size(), 12L).toInt()
            if (length <= 0) ByteArray(0) else Id3v2.readAt(channel, 0L, length) { "读取文件头失败" }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Skips additional ID3v2 tags directly following the first one (some tools
     * write duplicated tags). They are stale metadata and are dropped on write.
     */
    private fun skipTrailingTags(channel: FileChannel, offset: Long, fileSize: Long): Long {
        var current = offset
        var guard = 0
        while (guard < MAX_LEADING_TAGS && current + Id3v2.HEADER_SIZE <= fileSize) {
            val header = try {
                Id3v2.readAt(channel, current, Id3v2.HEADER_SIZE) { "读取后续 ID3 标签失败" }
            } catch (_: Exception) {
                break
            }
            if (!(header[0] == 'I'.code.toByte() &&
                    header[1] == 'D'.code.toByte() &&
                    header[2] == '3'.code.toByte())
            ) {
                break
            }
            val major = header[3].toInt() and 0xFF
            if (major !in 2..4) break
            val size = Id3v2.syncSafeToInt(header, 6)
            if (size > Id3v2.MAX_TAG_SIZE) break
            val footer = if (major == 4 && (header[5].toInt() and 0x10) != 0) Id3v2.FOOTER_SIZE else 0
            val next = current + Id3v2.HEADER_SIZE + size + footer
            if (next <= current || next > fileSize) break
            current = next
            guard++
        }
        return current
    }

    // ------------------------------------------------------------------
    // Crash safe rewrite
    // ------------------------------------------------------------------

    private fun writeAtomically(file: File, plan: WritePlan) {
        val absolute = file.absoluteFile
        val directory = absolute.parentFile
            ?: throw TagException("无法确定文件所在目录：${file.name}")
        val targetPath = absolute.toPath()
        val temp: Path = try {
            Files.createTempFile(directory.toPath(), ".musictag-", ".tmp")
        } catch (e: IOException) {
            throw TagException("无法在文件所在目录创建临时文件：${e.message}", e)
        }
        var replaced = false
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { output ->
                writeFully(output, ByteBuffer.wrap(plan.tagBytes))
                FileChannel.open(targetPath, StandardOpenOption.READ).use { source ->
                    copyAudio(source, output, plan.audioStart, plan.audioEnd)
                }
                output.force(true)
            }
            replaceFile(temp, targetPath)
            replaced = true
            syncDirectory(directory)
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException("写入 ${file.name} 失败：${e.message}", e)
        } finally {
            if (!replaced) {
                try {
                    Files.deleteIfExists(temp)
                } catch (_: IOException) {
                    // Best effort cleanup; the original file is untouched.
                }
            }
        }
    }

    private fun copyAudio(source: FileChannel, target: FileChannel, start: Long, end: Long) {
        var position = start
        var remaining = end - start
        if (remaining <= 0) return
        val buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE)
        var zeroReads = 0
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val read = source.read(buffer, position)
            if (read < 0) throw TagException("复制音频数据时意外到达文件末尾，文件可能已损坏")
            if (read == 0) {
                zeroReads++
                if (zeroReads > 1000) throw TagException("复制音频数据失败：无法读取源文件")
                continue
            }
            zeroReads = 0
            position += read
            remaining -= read
            buffer.flip()
            writeFully(target, buffer)
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        var zeroWrites = 0
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer)
            if (written == 0) {
                zeroWrites++
                if (zeroWrites > 1000) throw TagException("写入文件失败：磁盘可能已满")
            } else {
                zeroWrites = 0
            }
        }
    }

    private fun replaceFile(temp: Path, target: Path) {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: FileAlreadyExistsException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun syncDirectory(directory: File) {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Windows cannot open a directory as a channel; the rename itself is
            // still atomic, this is only an extra durability hint on POSIX.
        }
    }
}
