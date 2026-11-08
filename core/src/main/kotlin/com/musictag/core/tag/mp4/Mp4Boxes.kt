package com.musictag.core.tag.mp4

import com.musictag.core.tag.TagException
import java.io.RandomAccessFile

/**
 * ISO-BMFF (MP4/M4A) box primitives.
 *
 * All parsing is defensive: sizes are validated against the enclosing region, the
 * number of boxes and the nesting depth are capped and no allocation exceeds
 * [Mp4Limits.MAX_MOOV_BYTES]. Malformed input always produces a [TagException]
 * with a Chinese user-facing message.
 */
internal object Mp4Limits {
    const val MAX_TOP_LEVEL_BOXES = 100_000
    const val MAX_BOXES = 200_000
    const val MAX_NESTING_DEPTH = 64

    /** Largest `moov` box we are willing to load into memory (64 MiB). */
    const val MAX_MOOV_BYTES = 64L * 1024 * 1024

    /** Largest box header that can still be represented with a 32-bit size field. */
    const val MAX_BOX_BYTES = 0xFFFF_FFF0L
}

/** Big-endian integer helpers for ISO-BMFF structures. */
internal object Mp4Bytes {
    fun u32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    fun u64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        if (value < 0) {
            throw TagException("M4A 文件结构损坏：64 位盒子大小超出支持范围")
        }
        return value
    }

    fun putU64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            bytes[offset + i] = (value ushr ((7 - i) * 8)).toByte()
        }
    }

    fun u32Bytes(value: Long): ByteArray = ByteArray(4).also { putU32(it, 0, value) }

    fun readU32(file: RandomAccessFile): Long = file.readInt().toLong() and 0xFFFF_FFFFL

    fun readU64(file: RandomAccessFile): Long {
        val value = file.readLong()
        if (value < 0) throw TagException("M4A 文件结构损坏：64 位盒子大小超出支持范围")
        return value
    }

    fun readType(file: RandomAccessFile): String {
        val bytes = ByteArray(4)
        file.readFully(bytes)
        return String(bytes, Charsets.ISO_8859_1)
    }
}

/** Human readable form of a four-character box type for error messages. */
internal fun displayBoxType(type: String): String = buildString {
    for (ch in type) {
        append(
            when {
                ch.code in 32..126 -> ch
                ch == '\u00A9' -> ch
                else -> '?'
            }
        )
    }
}

/** A box at the top level of the file. */
internal class TopLevelBox(
    val type: String,
    val offset: Long,
    val headerSize: Int,
    val size: Long,
) {
    val endOffset: Long get() = offset + size
}

/** Result of walking the top level box list. [trailingStart] marks unparsed tail padding. */
internal class TopLevelLayout(
    val boxes: List<TopLevelBox>,
    val trailingStart: Long,
    val fileLength: Long,
)

/** Shared counter that caps the total number of parsed boxes. */
internal class BoxCounter {
    private var count = 0

    fun next() {
        count += 1
        if (count > Mp4Limits.MAX_BOXES) {
            throw TagException("M4A 文件结构损坏：盒子数量过多")
        }
    }
}

/**
 * A parsed box inside an in-memory region.
 *
 * Child boxes share the parent [region]: [startInRegion] is absolute inside that
 * array so all box positions can be derived without extra copies. Raw bytes are
 * only materialised through [rawBytes] when a box has to be preserved verbatim.
 */
internal class Mp4Node(
    val type: String,
    val headerSize: Int,
    val size: Int,
    val startInRegion: Int,
    val region: ByteArray,
    /** Offset of the child area relative to the start of this box (meta: header + 4). */
    val contentOffset: Int,
    val children: List<Mp4Node>,
) {
    fun rawBytes(): ByteArray = region.copyOfRange(startInRegion, startInRegion + size)

    fun payloadBytes(): ByteArray = region.copyOfRange(startInRegion + headerSize, startInRegion + size)

    /** Bytes between the box header and its first child (the meta version/flags field). */
    fun prefixBytes(): ByteArray =
        if (contentOffset > headerSize) {
            region.copyOfRange(startInRegion + headerSize, startInRegion + contentOffset)
        } else {
            ByteArray(0)
        }

    /** Children of a box that is normally a leaf, e.g. an `ilst` item wrapping `data` boxes. */
    fun childBoxes(): List<Mp4Node> {
        if (children.isNotEmpty()) return children
        return parseNodes(region, startInRegion + headerSize, startInRegion + size, 0, BoxCounter())
    }
}

internal const val TYPE_MOOV = "moov"
internal const val TYPE_META = "meta"
internal const val TYPE_ILST = "ilst"
internal const val TYPE_UDTA = "udta"

/** Pure container boxes that can be entered safely. Full boxes like `dref`/`stsd` stay opaque. */
private val CONTAINER_TYPES = setOf(
    TYPE_MOOV,
    "trak",
    "mdia",
    "minf",
    "stbl",
    TYPE_UDTA,
    TYPE_ILST,
    "edts",
    "dinf",
    "mvex",
    "moof",
    "traf",
    "mfra",
)

/** Reads the box list at the top level of the file. */
internal fun parseTopLevel(file: RandomAccessFile): TopLevelLayout {
    val length = file.length()
    if (length < 8L) throw TagException("文件太小，不是有效的 M4A/MP4 文件")
    val boxes = ArrayList<TopLevelBox>()
    var pos = 0L
    while (pos < length) {
        if (boxes.size >= Mp4Limits.MAX_TOP_LEVEL_BOXES) {
            throw TagException("M4A 文件结构损坏：顶层盒子数量过多")
        }
        // A few bytes of trailing padding are tolerated; they are copied unchanged.
        if (length - pos < 8L) break
        file.seek(pos)
        val size32 = Mp4Bytes.readU32(file)
        val type = Mp4Bytes.readType(file)
        var headerSize = 8
        var size = size32
        if (size32 == 1L) {
            if (length - pos < 16L) {
                throw TagException("M4A 文件结构损坏：盒子「${displayBoxType(type)}」头部不完整")
            }
            size = Mp4Bytes.readU64(file)
            headerSize = 16
        } else if (size32 == 0L) {
            size = length - pos
        }
        if (size < headerSize) {
            throw TagException(
                "M4A 文件结构损坏：盒子「${displayBoxType(type)}」大小无效（$size 字节）"
            )
        }
        if (size > length - pos) {
            throw TagException(
                "M4A 文件结构损坏：盒子「${displayBoxType(type)}」超出文件末尾" +
                    "（需要 $size 字节，仅剩 ${length - pos} 字节）"
            )
        }
        boxes += TopLevelBox(type, pos, headerSize, size)
        pos += size
    }
    return TopLevelLayout(boxes, pos, length)
}

/** Reads the complete bytes of a top level box, enforcing a size cap. */
internal fun readBoxBytes(
    file: RandomAccessFile,
    box: TopLevelBox,
    maxBytes: Long,
    label: String,
): ByteArray {
    if (box.size > maxBytes) {
        throw TagException("M4A 文件的 $label 盒子过大（${box.size} 字节），无法处理")
    }
    val data = ByteArray(box.size.toInt())
    file.seek(box.offset)
    file.readFully(data)
    return data
}

/** Parses a complete `moov` box (including its header) held in [region]. */
internal fun parseMoov(region: ByteArray, counter: BoxCounter = BoxCounter()): Mp4Node {
    if (region.size < 8) throw TagException("M4A 文件结构损坏：moov 盒子头部不完整")
    val size32 = Mp4Bytes.u32(region, 0)
    var headerSize = 8
    val declared: Long = when (size32) {
        1L -> {
            if (region.size < 16) throw TagException("M4A 文件结构损坏：moov 盒子头部不完整")
            headerSize = 16
            Mp4Bytes.u64(region, 8)
        }
        0L -> region.size.toLong()
        else -> size32
    }
    if (declared != region.size.toLong()) {
        throw TagException(
            "M4A 文件结构损坏：moov 盒子大小（$declared 字节）与实际（${region.size} 字节）不符"
        )
    }
    val children = parseNodes(region, headerSize, region.size, 1, counter)
    return Mp4Node(TYPE_MOOV, headerSize, region.size, 0, region, headerSize, children)
}

/**
 * Parses the sibling boxes occupying `region[start, end)`.
 *
 * [startInRegion] of the returned nodes is absolute inside [region] so callers can
 * patch 32/64-bit tables in place. Less than eight trailing bytes are ignored: a
 * rebuilt container simply drops that (normally zero) padding.
 */
internal fun parseNodes(
    region: ByteArray,
    start: Int,
    end: Int,
    depth: Int,
    counter: BoxCounter,
): List<Mp4Node> {
    if (depth > Mp4Limits.MAX_NESTING_DEPTH) {
        throw TagException("M4A 文件结构损坏：盒子嵌套层级过深")
    }
    val nodes = ArrayList<Mp4Node>()
    var pos = start
    while (end - pos >= 8) {
        counter.next()
        val size32 = Mp4Bytes.u32(region, pos)
        val type = String(region, pos + 4, 4, Charsets.ISO_8859_1)
        var headerSize = 8
        var size = size32
        if (size32 == 1L) {
            if (end - pos < 16) {
                throw TagException("M4A 文件结构损坏：盒子「${displayBoxType(type)}」头部不完整")
            }
            size = Mp4Bytes.u64(region, pos + 8)
            headerSize = 16
        } else if (size32 == 0L) {
            size = (end - pos).toLong()
        }
        if (size < headerSize) {
            throw TagException(
                "M4A 文件结构损坏：盒子「${displayBoxType(type)}」大小无效（$size 字节）"
            )
        }
        if (size > (end - pos).toLong()) {
            throw TagException("M4A 文件结构损坏：盒子「${displayBoxType(type)}」超出父盒子边界")
        }
        if (size > Int.MAX_VALUE) {
            throw TagException("M4A 文件结构损坏：盒子「${displayBoxType(type)}」过大")
        }
        val sizeInt = size.toInt()
        val contentOffset = if (type == TYPE_META) headerSize + 4 else headerSize
        val children: List<Mp4Node> = when {
            type == TYPE_META -> {
                if (sizeInt < contentOffset) {
                    throw TagException("M4A 文件结构损坏：meta 盒子过小")
                }
                parseNodes(region, pos + contentOffset, pos + sizeInt, depth + 1, counter)
            }
            type in CONTAINER_TYPES -> parseNodes(region, pos + headerSize, pos + sizeInt, depth + 1, counter)
            else -> emptyList()
        }
        nodes += Mp4Node(type, headerSize, sizeInt, pos, region, contentOffset, children)
        pos += sizeInt
    }
    return nodes
}

/** Depth-first visit of [nodes] and all nested containers. */
internal fun walkBoxes(nodes: List<Mp4Node>, visitor: (Mp4Node) -> Unit) {
    for (node in nodes) {
        visitor(node)
        if (node.children.isNotEmpty()) walkBoxes(node.children, visitor)
    }
}

/**
 * Finds the first path of boxes matching [typePath], e.g. `udta/meta/ilst`.
 *
 * Every candidate box of the current level is tried so a `udta` without `meta`
 * does not hide a later `udta` that has one. Returns the path including the root
 * node, or null when the chain does not exist.
 */
internal fun findPath(nodes: List<Mp4Node>, typePath: List<String>): List<Mp4Node>? {
    if (typePath.isEmpty()) return emptyList()
    val head = typePath[0]
    val rest = typePath.subList(1, typePath.size)
    for (candidate in nodes) {
        if (candidate.type != head) continue
        val tail = findPath(candidate.children, rest) ?: continue
        return listOf(candidate) + tail
    }
    return null
}

/**
 * Rebuilds the path from `path[0]` down to `path[last]`, replacing the last box
 * with [newNodeBytes] and recomputing every ancestor size on the way up.
 *
 * Boxes that are not on the path are copied byte-for-byte. The original header
 * style (32-bit vs 64-bit size) of every rebuilt ancestor is preserved.
 */
internal fun rebuildPath(path: List<Mp4Node>, newNodeBytes: ByteArray): ByteArray {
    if (path.isEmpty()) throw IllegalStateException("empty path")
    var bytes = newNodeBytes
    for (i in path.size - 1 downTo 1) {
        val parent = path[i - 1]
        val child = path[i]
        val parts = ArrayList<ByteArray>(parent.children.size)
        for (existing in parent.children) {
            parts += if (existing === child) bytes else existing.rawBytes()
        }
        bytes = buildBoxWithHeaderStyle(parent.type, parent.headerSize, parent.prefixBytes(), parts)
    }
    return bytes
}

internal fun buildBox(type: String, content: ByteArray): ByteArray =
    buildBoxWithHeaderStyle(type, 8, ByteArray(0), listOf(content))

internal fun buildBox(type: String, parts: List<ByteArray>): ByteArray =
    buildBoxWithHeaderStyle(type, 8, ByteArray(0), parts)

/** Builds a complete box with an explicit header style and optional prefix area. */
internal fun buildBoxWithHeaderStyle(
    type: String,
    headerSize: Int,
    prefix: ByteArray,
    parts: List<ByteArray>,
): ByteArray {
    if (headerSize != 8 && headerSize != 16) {
        throw TagException("M4A 标签写入失败：不支持的盒子头部大小（$headerSize）")
    }
    var contentSize = prefix.size.toLong()
    for (part in parts) contentSize += part.size.toLong()
    val total = contentSize + headerSize
    if (total > Mp4Limits.MAX_BOX_BYTES || total > Int.MAX_VALUE.toLong()) {
        throw TagException("M4A 标签数据过大，无法写入")
    }
    val out = ByteArray(total.toInt())
    val typeBytes = boxTypeBytes(type)
    if (headerSize == 16) {
        Mp4Bytes.putU32(out, 0, 1L)
        typeBytes.copyInto(out, 4)
        Mp4Bytes.putU64(out, 8, total)
    } else {
        Mp4Bytes.putU32(out, 0, total)
        typeBytes.copyInto(out, 4)
    }
    var pos = headerSize
    prefix.copyInto(out, pos)
    pos += prefix.size
    for (part in parts) {
        part.copyInto(out, pos)
        pos += part.size
    }
    return out
}

private fun boxTypeBytes(type: String): ByteArray {
    val bytes = ByteArray(4)
    for (i in 0 until 4) {
        val code = if (i < type.length) type[i].code else 0
        bytes[i] = code.toByte()
    }
    return bytes
}
