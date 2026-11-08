package com.musictag.core.tag.flac

import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import com.musictag.core.tag.TagFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * FLAC support (`fLaC` container).
 *
 * [read] parses the metadata block chain, the first VORBIS_COMMENT block and the front cover
 * PICTURE block. [write] rebuilds only the Vorbis comment and the picture blocks: STREAMINFO,
 * SEEKTABLE, APPLICATION, CUESHEET, unknown blocks, unmodeled comment fields and the audio frames
 * are copied byte-for-byte. The file is replaced atomically through a temporary file in the same
 * directory, so a failed write never leaves a half-written original.
 */
object FlacFormat : TagFormat {

    override val displayName: String = "FLAC"

    override val extensions: Set<String> = setOf("flac")

    override fun supports(file: File): Boolean {
        if (!file.isFile) return false
        if (file.extension.equals("flac", ignoreCase = true)) return true
        return hasFlacMagic(file)
    }

    private fun hasFlacMagic(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val magic = ByteArray(4)
                var read = 0
                while (read < magic.size) {
                    val count = input.read(magic, read, magic.size - read)
                    if (count < 0) return false
                    read += count
                }
                magic.contentEquals(FLAC_MAGIC)
            }
        } catch (e: IOException) {
            false
        }
    }

    override fun read(file: File): TagDocument {
        return try {
            val structure = FlacParser.readStructure(file)
            documentFrom(structure.blocks)
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException("读取 FLAC 标签失败：${file.name}", e)
        } catch (e: Exception) {
            throw TagException("读取 FLAC 标签失败：${file.name}", e)
        }
    }

    private fun documentFrom(blocks: List<FlacBlock>): TagDocument {
        val comments = blocks
            .filter { it.type == BLOCK_VORBIS_COMMENT }
            .map { VorbisCommentParser.parse(it.data) }
        val comment = VorbisComment.combined(comments)
        return TagDocument(
            title = comment.firstValueOf("TITLE"),
            artist = comment.firstValueOf("ARTIST"),
            album = comment.firstValueOf("ALBUM"),
            albumArtist = comment.firstValueOf("ALBUMARTIST"),
            year = comment.firstValueOf("DATE", "YEAR"),
            trackNumber = comment.firstValueOf("TRACKNUMBER"),
            discNumber = comment.firstValueOf("DISCNUMBER"),
            genre = comment.firstValueOf("GENRE"),
            comment = comment.firstValueOf("COMMENT", "DESCRIPTION"),
            lyrics = comment.firstValueOf("LYRICS", "UNSYNCEDLYRICS"),
            artwork = selectArtwork(blocks),
        )
    }

    /** Front cover (type 3) preferred, otherwise the first picture block in the file. */
    private fun selectArtwork(blocks: List<FlacBlock>): Artwork? {
        val pictures = blocks
            .filter { it.type == BLOCK_PICTURE }
            .map { FlacPictureParser.parse(it.data) }
        if (pictures.isEmpty()) return null
        val selected = pictures.firstOrNull { it.pictureType == PICTURE_TYPE_FRONT_COVER }
            ?: pictures.first()
        return selected.toArtwork()
    }

    override fun write(file: File, document: TagDocument) {
        val normalized = document.normalized()
        try {
            val structure = FlacParser.readStructure(file)
            val blocks = buildBlocks(structure.blocks, normalized)
            writeAtomically(file, blocks, structure.audioOffset)
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException("写入 FLAC 标签失败：${file.name}", e)
        } catch (e: Exception) {
            throw TagException("写入 FLAC 标签失败：${file.name}", e)
        }
    }

    /**
     * Builds the new metadata block sequence. STREAMINFO stays first, the VORBIS_COMMENT block is
     * replaced at the position of the first old comment block (or inserted after STREAMINFO), the
     * PICTURE block is replaced at the position of the first old picture block (or inserted after
     * the comment), every other block is kept in order and a fresh 4 KiB PADDING block is appended.
     */
    private fun buildBlocks(original: List<FlacBlock>, document: TagDocument): List<FlacBlock> {
        val streamInfo = original.first()
        val commentBlock = buildCommentBlock(original, document)
        val pictureBlock = document.artwork?.let {
            FlacBlock(BLOCK_PICTURE, FlacPictureWriter.build(it))
        }
        val commentSlot = original.indexOfFirst { it.type == BLOCK_VORBIS_COMMENT }
            .let { if (it > 0) it else 1 }
        val pictureSlot = original.indexOfFirst { it.type == BLOCK_PICTURE }.takeIf { it > 0 } ?: -1

        val result = ArrayList<FlacBlock>(original.size + 3)
        result.add(FlacBlock(BLOCK_STREAMINFO, streamInfo.data))
        var commentWritten = false
        var pictureWritten = false
        for (index in 1 until original.size) {
            if (index == commentSlot) {
                result.add(commentBlock)
                commentWritten = true
            }
            if (index == pictureSlot && pictureBlock != null) {
                result.add(pictureBlock)
                pictureWritten = true
            }
            val block = original[index]
            when (block.type) {
                BLOCK_VORBIS_COMMENT, BLOCK_PICTURE, BLOCK_PADDING -> Unit
                else -> result.add(FlacBlock(block.type, block.data))
            }
        }
        if (!commentWritten) result.add(commentBlock)
        if (pictureBlock != null && !pictureWritten) {
            val commentIndex = result.indexOfFirst { it.type == BLOCK_VORBIS_COMMENT }
            result.add(if (commentIndex >= 0) commentIndex + 1 else result.size, pictureBlock)
        }
        result.add(FlacBlock(BLOCK_PADDING, ByteArray(PADDING_BLOCK_SIZE)))
        // The last-block flag is encoded from the position when the stream is written.
        return result
    }

    /** Builds the replacement comment block, preserving vendor and unmodeled fields. */
    private fun buildCommentBlock(original: List<FlacBlock>, document: TagDocument): FlacBlock {
        val comments = original
            .filter { it.type == BLOCK_VORBIS_COMMENT }
            .map { VorbisCommentParser.parse(it.data) }
        val existing = VorbisComment.combined(comments)
        val modeled = ArrayList<VorbisCommentEntry>()
        listOf(
            commentField("TITLE", document.title),
            commentField("ARTIST", document.artist),
            commentField("ALBUM", document.album),
            commentField("ALBUMARTIST", document.albumArtist),
            commentField("DATE", document.year),
            commentField("TRACKNUMBER", document.trackNumber),
            commentField("DISCNUMBER", document.discNumber),
            commentField("GENRE", document.genre),
            commentField("COMMENT", document.comment),
            commentField("LYRICS", document.lyrics),
        ).forEach { if (it != null) modeled.add(it) }
        return FlacBlock(BLOCK_VORBIS_COMMENT, existing.withFields(modeled).toBlockData())
    }

    /** Writes metadata + copied audio to a temp file and atomically swaps it into place. */
    private fun writeAtomically(target: File, blocks: List<FlacBlock>, audioOffset: Long) {
        val directory = target.absoluteFile.parentFile
            ?: throw TagException("无法确定 FLAC 文件所在目录：${target.name}")
        val temp: Path = try {
            Files.createTempFile(directory.toPath(), target.name + ".", ".tmp")
        } catch (e: IOException) {
            throw TagException("无法创建临时文件，写入 FLAC 失败：${target.name}", e)
        }
        try {
            writeMetadataAndAudio(temp, target, blocks, audioOffset)
            moveIntoPlace(temp, target)
        } catch (e: TagException) {
            throw e
        } catch (e: IOException) {
            throw TagException("写入 FLAC 文件失败：${target.name}", e)
        } finally {
            try {
                Files.deleteIfExists(temp)
            } catch (_: IOException) {
                // Best effort cleanup; the temp file is never referenced by the original.
            }
        }
    }

    private fun writeMetadataAndAudio(
        temp: Path,
        source: File,
        blocks: List<FlacBlock>,
        audioOffset: Long,
    ) {
        val targetLength = source.length()
        if (audioOffset > targetLength) {
            throw TagException("FLAC 文件已损坏：音频数据偏移超出文件长度：${source.name}")
        }
        FileOutputStream(temp.toFile()).channel.use { output ->
            writeFully(output, FLAC_MAGIC)
            blocks.forEachIndexed { index, block ->
                writeBlock(output, block, isLast = index == blocks.lastIndex)
            }
            FileInputStream(source).channel.use { input ->
                input.position(audioOffset)
                val buffer = ByteBuffer.allocate(256 * 1024)
                while (true) {
                    buffer.clear()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    buffer.flip()
                    while (buffer.hasRemaining()) {
                        output.write(buffer)
                    }
                }
            }
            output.force(true)
        }
        // Best-effort directory fsync so the rename itself survives a crash (Unix/Android only).
        try {
            FileChannel.open(temp.parent, StandardOpenOption.READ).use { it.force(true) }
        } catch (_: IOException) {
            // Not supported on this platform (e.g. Windows); ignoring it is safe for the file data.
        }
    }

    private fun writeBlock(channel: FileChannel, block: FlacBlock, isLast: Boolean) {
        val size = block.data.size
        if (size > MAX_BLOCK_DATA_SIZE) {
            throw TagException("FLAC 元数据块过大，无法写入文件")
        }
        val header = ByteArray(4)
        header[0] = ((if (isLast) 0x80 else 0) or (block.type and 0x7F)).toByte()
        header[1] = ((size ushr 16) and 0xFF).toByte()
        header[2] = ((size ushr 8) and 0xFF).toByte()
        header[3] = (size and 0xFF).toByte()
        writeFully(channel, header)
        if (size > 0) writeFully(channel, block.data)
    }

    private fun writeFully(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private fun moveIntoPlace(temp: Path, target: File) {
        val targetPath = target.toPath()
        try {
            Files.move(
                temp,
                targetPath,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            return
        } catch (e: AtomicMoveNotSupportedException) {
            // Fall through to the non-atomic replacement below.
        }
        try {
            Files.move(temp, targetPath, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw TagException("无法替换 FLAC 文件：${target.name}", e)
        }
    }
}
