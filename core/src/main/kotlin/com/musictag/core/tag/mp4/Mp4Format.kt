package com.musictag.core.tag.mp4

import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import com.musictag.core.tag.TagFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * MP4 / M4A (AAC) tag engine.
 *
 * Reading walks the top level ISO-BMFF boxes, enters `moov` and finds
 * `udta/meta/ilst` (or `moov/meta/ilst`). Writing rebuilds only the boxes on the
 * path to `ilst` and streams every other box through untouched, so `ftyp`, `mdat`
 * and all technical headers stay byte-identical.
 *
 * Offset strategy: the rebuilt `moov` replaces the old one at the same file
 * position, therefore every byte following it shifts by `newMoovSize - oldMoovSize`.
 * All `stco`/`co64` chunk offsets that address data after the old `moov` are
 * patched by that delta; offsets before `moov` (the "moov after mdat" layout) are
 * left alone. This works for both faststart and classic layouts.
 *
 * The rewritten file is produced in a sibling temp file, fsynced and then moved
 * atomically over the original, so a crash can never leave a half-written file.
 */
object Mp4Format : TagFormat {
    override val displayName: String = "M4A / MP4"
    override val extensions: Set<String> = setOf("m4a", "m4b", "mp4")

    private const val TYPE_FTYP = "ftyp"
    private const val TYPE_STCO = "stco"
    private const val TYPE_CO64 = "co64"

    override fun supports(file: File): Boolean {
        if (!file.isFile) return false
        val length = file.length()
        if (length < 8L) return false
        val header = ByteArray(8)
        try {
            RandomAccessFile(file, "r").use { it.readFully(header) }
        } catch (e: IOException) {
            return false
        }
        val firstType = String(header, 4, 4, Charsets.ISO_8859_1)
        if (firstType == TYPE_FTYP) return true
        if (file.extension.lowercase() !in extensions) return false
        // A foreign format with a renamed extension must never be claimed.
        if (hasForeignSignature(header)) return false
        val firstSize = Mp4Bytes.u32(header, 0)
        return firstSize == 1L || (firstSize >= 8L && firstSize <= length)
    }

    override fun read(file: File): TagDocument {
        requireReadable(file)
        return try {
            val ilst = RandomAccessFile(file, "r").use { raf ->
                val layout = parseTopLevel(raf)
                requireFtyp(layout)
                val moovBox = layout.boxes.firstOrNull { it.type == TYPE_MOOV }
                    ?: throw TagException("不是有效的 M4A/MP4 文件：缺少 moov 盒子")
                val moovBytes = readBoxBytes(raf, moovBox, Mp4Limits.MAX_MOOV_BYTES, "moov")
                findIlist(parseMoov(moovBytes))
            }
            Mp4Ilst.read(ilst)
        } catch (e: TagException) {
            throw e
        } catch (e: Exception) {
            throw TagException("读取 M4A 文件失败：${e.message}", e)
        }
    }

    override fun write(file: File, document: TagDocument) {
        requireReadable(file)
        val doc = document.normalized()
        try {
            val prepared = RandomAccessFile(file, "r").use { raf ->
                val layout = parseTopLevel(raf)
                requireFtyp(layout)
                val moovBox = layout.boxes.firstOrNull { it.type == TYPE_MOOV }
                    ?: throw TagException("不是有效的 M4A/MP4 文件：缺少 moov 盒子")
                val moovBytes = readBoxBytes(raf, moovBox, Mp4Limits.MAX_MOOV_BYTES, "moov")
                val newMoov = buildNewMoov(moovBytes, doc)
                val delta = newMoov.size.toLong() - moovBox.size
                if (delta != 0L) {
                    // Old absolute end of moov: everything from here on moves by delta.
                    patchChunkOffsets(newMoov, moovBox.offset + moovBox.size, delta)
                }
                PreparedRewrite(layout, moovBox, newMoov)
            }
            writeAtomically(file, prepared)
        } catch (e: TagException) {
            throw e
        } catch (e: Exception) {
            throw TagException("写入 M4A 标签失败：${e.message}", e)
        }
    }

    // ------------------------------------------------------------------ reading

    /** Metadata ilst inside `udta/meta` (iTunes layout) or, as a fallback, `moov/meta`. */
    private fun findIlist(moov: Mp4Node): Mp4Node? {
        val childPath = findPath(moov.children, listOf(TYPE_UDTA, TYPE_META, TYPE_ILST))
            ?: findPath(moov.children, listOf(TYPE_META, TYPE_ILST))
        return childPath?.last()
    }

    private fun requireReadable(file: File) {
        if (!file.exists() || !file.isFile) throw TagException("文件不存在：${file.name}")
        if (!file.canRead()) throw TagException("文件不可读：${file.name}")
    }

    private fun requireFtyp(layout: TopLevelLayout) {
        if (layout.boxes.none { it.type == TYPE_FTYP }) {
            throw TagException("不是有效的 M4A/MP4 文件：缺少 ftyp 盒子")
        }
    }

    // ------------------------------------------------------------------ writing

    private class PreparedRewrite(
        val layout: TopLevelLayout,
        val moovBox: TopLevelBox,
        val newMoov: ByteArray,
    )

    /** Rebuilds `moov` with the requested metadata, creating `udta/meta/ilst` when absent. */
    private fun buildNewMoov(moovBytes: ByteArray, document: TagDocument): ByteArray {
        val moov = parseMoov(moovBytes)
        val ilstChildPath = findPath(moov.children, listOf(TYPE_UDTA, TYPE_META, TYPE_ILST))
            ?: findPath(moov.children, listOf(TYPE_META, TYPE_ILST))
        if (ilstChildPath != null) {
            val ilstPath = listOf(moov) + ilstChildPath
            return rebuildPath(ilstPath, Mp4Ilst.build(ilstPath.last(), document))
        }
        // Nothing to remove and no metadata container to update: keep moov untouched.
        if (document.isEmpty) return moovBytes

        val newIlst = Mp4Ilst.build(null, document)
        val metaChildPath = findPath(moov.children, listOf(TYPE_UDTA, TYPE_META))
            ?: findPath(moov.children, listOf(TYPE_META))
        if (metaChildPath != null) {
            val metaPath = listOf(moov) + metaChildPath
            val meta = metaPath.last()
            val parts = ArrayList<ByteArray>(meta.children.size + 2)
            for (child in meta.children) parts += child.rawBytes()
            if (meta.children.none { it.type == "hdlr" }) parts += Mp4Ilst.METADATA_HDLR
            parts += newIlst
            return rebuildPath(
                metaPath,
                buildBoxWithHeaderStyle(TYPE_META, meta.headerSize, meta.prefixBytes(), parts),
            )
        }

        val newMeta = buildBoxWithHeaderStyle(
            TYPE_META,
            8,
            ByteArray(4), // meta is a full box: version/flags
            listOf(Mp4Ilst.METADATA_HDLR, newIlst),
        )
        val udtaChildPath = findPath(moov.children, listOf(TYPE_UDTA))
        if (udtaChildPath != null) {
            val udtaPath = listOf(moov) + udtaChildPath
            val udta = udtaPath.last()
            val parts = ArrayList<ByteArray>(udta.children.size + 1)
            for (child in udta.children) parts += child.rawBytes()
            parts += newMeta
            return rebuildPath(
                udtaPath,
                buildBoxWithHeaderStyle(TYPE_UDTA, udta.headerSize, udta.prefixBytes(), parts),
            )
        }

        val newUdta = buildBox(TYPE_UDTA, newMeta)
        val parts = ArrayList<ByteArray>(moov.children.size + 1)
        for (child in moov.children) parts += child.rawBytes()
        parts += newUdta
        return buildBoxWithHeaderStyle(TYPE_MOOV, moov.headerSize, ByteArray(0), parts)
    }

    /**
     * Patches all `stco`/`co64` chunk offsets inside [moovBytes] that point at data
     * located after [oldMoovEnd] by [delta]. Patching values never changes box
     * sizes, so the moov computed before this call stays structurally valid.
     */
    private fun patchChunkOffsets(moovBytes: ByteArray, oldMoovEnd: Long, delta: Long) {
        val moov = parseMoov(moovBytes)
        walkBoxes(moov.children) { box ->
            when (box.type) {
                TYPE_STCO -> patchStco(box, oldMoovEnd, delta)
                TYPE_CO64 -> patchCo64(box, oldMoovEnd, delta)
            }
        }
    }

    private fun patchStco(box: Mp4Node, oldMoovEnd: Long, delta: Long) {
        val payloadStart = box.startInRegion + box.headerSize
        val count = Mp4Bytes.u32(box.region, payloadStart + 4)
        val tableStart = payloadStart + 8L
        if (tableStart + count * 4L > (box.startInRegion + box.size).toLong()) {
            throw TagException("M4A 文件结构损坏：stco 盒子长度无效")
        }
        var index = 0L
        while (index < count) {
            val at = tableStart.toInt() + (index * 4L).toInt()
            val value = Mp4Bytes.u32(box.region, at)
            if (value >= oldMoovEnd) {
                val patched = value + delta
                if (patched < 0L || patched > 0xFFFF_FFFFL) {
                    throw TagException("M4A 文件过大：无法修补音频数据偏移")
                }
                Mp4Bytes.putU32(box.region, at, patched)
            }
            index++
        }
    }

    private fun patchCo64(box: Mp4Node, oldMoovEnd: Long, delta: Long) {
        val payloadStart = box.startInRegion + box.headerSize
        val count = Mp4Bytes.u32(box.region, payloadStart + 4)
        val tableStart = payloadStart + 8L
        if (tableStart + count * 8L > (box.startInRegion + box.size).toLong()) {
            throw TagException("M4A 文件结构损坏：co64 盒子长度无效")
        }
        var index = 0L
        while (index < count) {
            val at = tableStart.toInt() + (index * 8L).toInt()
            val value = Mp4Bytes.u64(box.region, at)
            if (value >= oldMoovEnd) {
                val patched = value + delta
                if (patched < 0L) throw TagException("M4A 文件过大：无法修补音频数据偏移")
                Mp4Bytes.putU64(box.region, at, patched)
            }
            index++
        }
    }

    // --------------------------------------------------------------- file rewrite

    private fun writeAtomically(target: File, prepared: PreparedRewrite) {
        val directory = target.absoluteFile.parentFile
            ?: throw TagException("无法确定 M4A 文件所在目录：${target.name}")
        val temp = File.createTempFile("musictag-mp4-", ".tmp", directory)
        var replaced = false
        try {
            FileChannel.open(target.toPath(), StandardOpenOption.READ).use { source ->
                FileChannel.open(
                    temp.toPath(),
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { destination ->
                    for (box in prepared.layout.boxes) {
                        if (box.type == TYPE_MOOV && box.offset == prepared.moovBox.offset) {
                            writeFully(destination, prepared.newMoov)
                        } else {
                            copyRange(source, destination, box.offset, box.size)
                        }
                    }
                    val trailing = prepared.layout.fileLength - prepared.layout.trailingStart
                    if (trailing > 0L) {
                        copyRange(source, destination, prepared.layout.trailingStart, trailing)
                    }
                    destination.force(true)
                }
            }
            replaceAtomically(temp, target)
            replaced = true
        } catch (e: TagException) {
            throw e
        } catch (e: Exception) {
            throw TagException("写入 M4A 文件失败：${e.message}", e)
        } finally {
            if (!replaced) temp.delete()
        }
    }

    private fun replaceAtomically(temp: File, target: File) {
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun copyRange(source: FileChannel, destination: FileChannel, position: Long, count: Long) {
        var offset = position
        var remaining = count
        while (remaining > 0L) {
            val copied = source.transferTo(offset, remaining, destination)
            if (copied <= 0L) throw TagException("写入 M4A 文件失败：读取源文件不完整")
            offset += copied
            remaining -= copied
        }
    }

    private fun writeFully(destination: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (destination.write(buffer) <= 0) {
                throw TagException("写入 M4A 文件失败：磁盘写入不完整")
            }
        }
    }

    // ------------------------------------------------------------------ detection

    private fun hasForeignSignature(header: ByteArray): Boolean {
        fun matches(vararg expected: Int): Boolean {
            if (header.size < expected.size) return false
            return expected.indices.all { header[it] == expected[it].toByte() }
        }
        if (matches(0x66, 0x4C, 0x61, 0x43)) return true // fLaC
        if (matches(0x4F, 0x67, 0x67, 0x53)) return true // OggS
        if (matches(0x49, 0x44, 0x33)) return true // ID3
        if (matches(0x52, 0x49, 0x46, 0x46)) return true // RIFF
        if (matches(0x1A, 0x45, 0xDF, 0xA3)) return true // Matroska / WebM
        // MPEG audio frame sync.
        if (header.size >= 2 && (header[0].toInt() and 0xFF) == 0xFF &&
            (header[1].toInt() and 0xE0) == 0xE0
        ) {
            return true
        }
        return false
    }
}
