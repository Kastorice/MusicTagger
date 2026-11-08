package com.musictag.core.tag.flac

import org.junit.Assume
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Test-only helpers: an independent FLAC metadata parser/serializer (so assertions do not reuse
 * the production parser), fixture copying and ffmpeg/ffprobe wrappers.
 */
internal object FlacTestSupport {

    private const val FIXTURE_DIR = "/fixtures"

    class Block(val type: Int, val data: ByteArray)

    class Structure(val blocks: List<Block>, val audioOffset: Int)

    class ParsedComment(val vendor: String, val entries: List<String>) {
        fun values(key: String): List<String> = entries.mapNotNull { entry ->
            val equals = entry.indexOf('=')
            if (equals > 0 && entry.substring(0, equals).equals(key, ignoreCase = true)) {
                entry.substring(equals + 1)
            } else {
                null
            }
        }

        fun value(key: String): String? = values(key).firstOrNull()
    }

    class ParsedPicture(
        val type: Int,
        val mimeType: String,
        val description: String,
        val width: Int,
        val height: Int,
        val depth: Int,
        val colors: Int,
        val data: ByteArray,
    )

    class ProcessResult(val exitCode: Int, val output: String)

    // ---------------------------------------------------------------- fixtures

    fun fixtureBytes(name: String): ByteArray {
        val stream = FlacTestSupport::class.java.getResourceAsStream("$FIXTURE_DIR/$name")
            ?: throw AssertionError("missing test fixture: $name")
        return stream.use { it.readBytes() }
    }

    fun copyFixture(name: String, destination: File): File {
        destination.parentFile?.mkdirs()
        destination.writeBytes(fixtureBytes(name))
        return destination
    }

    // ------------------------------------------------------------ FLAC parsing

    fun parse(bytes: ByteArray): Structure {
        require(bytes.size >= 4) { "file too small to be FLAC" }
        require(String(bytes, 0, 4, StandardCharsets.US_ASCII) == "fLaC") { "missing fLaC magic" }
        var position = 4
        val blocks = ArrayList<Block>()
        var last = false
        while (!last) {
            require(position + 4 <= bytes.size) { "truncated metadata block header at $position" }
            val header = bytes[position].toInt() and 0xFF
            last = (header and 0x80) != 0
            val type = header and 0x7F
            val size = ((bytes[position + 1].toInt() and 0xFF) shl 16) or
                ((bytes[position + 2].toInt() and 0xFF) shl 8) or
                (bytes[position + 3].toInt() and 0xFF)
            require(position + 4 + size <= bytes.size) { "truncated metadata block at $position" }
            blocks.add(Block(type, bytes.copyOfRange(position + 4, position + 4 + size)))
            position += 4 + size
        }
        return Structure(blocks, position)
    }

    fun build(blocks: List<Block>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
        blocks.forEachIndexed { index, block ->
            val last = index == blocks.lastIndex
            val size = block.data.size
            out.write((if (last) 0x80 else 0) or (block.type and 0x7F))
            out.write((size ushr 16) and 0xFF)
            out.write((size ushr 8) and 0xFF)
            out.write(size and 0xFF)
            out.write(block.data)
        }
        return out.toByteArray()
    }

    fun audioTail(bytes: ByteArray): ByteArray {
        val structure = parse(bytes)
        return bytes.copyOfRange(structure.audioOffset, bytes.size)
    }

    fun insertAfterStreamInfo(bytes: ByteArray, extra: List<Block>): ByteArray {
        val structure = parse(bytes)
        val metadata = structure.blocks.toMutableList()
        metadata.addAll(1, extra)
        return build(metadata) + audioTail(bytes)
    }

    /** Replaces the first block of [type] with [newData] (other blocks of that type are dropped). */
    fun replaceBlock(bytes: ByteArray, type: Int, newData: ByteArray): ByteArray {
        val structure = parse(bytes)
        val replaced = ArrayList<Block>(structure.blocks.size)
        var done = false
        for (block in structure.blocks) {
            if (block.type == type) {
                if (!done) {
                    replaced.add(Block(type, newData))
                    done = true
                }
            } else {
                replaced.add(block)
            }
        }
        require(done) { "file has no block of type $type" }
        return build(replaced) + audioTail(bytes)
    }

    // ------------------------------------------------------------ synthetic data

    /** A valid 18 byte seek-point table with one real seek point and placeholders. */
    fun seekTable(entries: Int = 8): ByteArray {
        require(entries > 0)
        val out = ByteArrayOutputStream()
        writeBe64(out, 0L) // sample number of the first frame
        writeBe64(out, 0L) // stream offset
        writeBe16(out, 4096) // samples in the frame
        repeat(entries - 1) {
            writeBe64(out, -1L) // placeholder seek point
            writeBe64(out, 0L)
            writeBe16(out, 0)
        }
        return out.toByteArray()
    }

    fun applicationBlock(payload: ByteArray = ByteArray(12) { (0x40 + it).toByte() }): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("TEST".toByteArray(StandardCharsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    fun unknownBlockData(): ByteArray = ByteArray(24) { (0x11 + it).toByte() }

    fun commentBytes(vendor: String, entries: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        val vendorBytes = vendor.toByteArray(StandardCharsets.UTF_8)
        writeLe32(out, vendorBytes.size)
        out.write(vendorBytes)
        writeLe32(out, entries.size)
        for (entry in entries) {
            val bytes = entry.toByteArray(StandardCharsets.UTF_8)
            writeLe32(out, bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    fun parseComment(data: ByteArray): ParsedComment {
        var position = 0

        fun readLe32(): Int {
            require(position + 4 <= data.size) { "comment length field out of bounds" }
            val value = (data[position].toInt() and 0xFF) or
                ((data[position + 1].toInt() and 0xFF) shl 8) or
                ((data[position + 2].toInt() and 0xFF) shl 16) or
                ((data[position + 3].toInt() and 0xFF) shl 24)
            position += 4
            return value
        }

        val vendorLength = readLe32()
        require(vendorLength >= 0 && position + vendorLength <= data.size) { "vendor out of bounds" }
        val vendor = String(data, position, vendorLength, StandardCharsets.UTF_8)
        position += vendorLength
        val count = readLe32()
        require(count >= 0) { "negative comment count" }
        val entries = ArrayList<String>(minOf(count, 1024))
        repeat(count) {
            val length = readLe32()
            require(length >= 0 && position + length <= data.size) { "comment entry out of bounds" }
            entries.add(String(data, position, length, StandardCharsets.UTF_8))
            position += length
        }
        require(position == data.size) { "trailing bytes in comment payload" }
        return ParsedComment(vendor, entries)
    }

    fun parsePicture(data: ByteArray): ParsedPicture {
        var position = 0

        fun readBe32(): Int {
            require(position + 4 <= data.size) { "picture field out of bounds" }
            val value = ((data[position].toInt() and 0xFF) shl 24) or
                ((data[position + 1].toInt() and 0xFF) shl 16) or
                ((data[position + 2].toInt() and 0xFF) shl 8) or
                (data[position + 3].toInt() and 0xFF)
            position += 4
            return value
        }

        fun readBytes(length: Int): ByteArray {
            require(length >= 0 && position + length <= data.size) { "picture bytes out of bounds" }
            val value = data.copyOfRange(position, position + length)
            position += length
            return value
        }

        val type = readBe32()
        val mime = String(readBytes(readBe32()), StandardCharsets.UTF_8)
        val description = String(readBytes(readBe32()), StandardCharsets.UTF_8)
        val width = readBe32()
        val height = readBe32()
        val depth = readBe32()
        val colors = readBe32()
        val imageData = readBytes(readBe32())
        require(position == data.size) { "trailing bytes in picture payload" }
        return ParsedPicture(type, mime, description, width, height, depth, colors, imageData)
    }

    fun buildPicture(
        type: Int,
        mimeType: String,
        description: String,
        width: Int,
        height: Int,
        depth: Int,
        colors: Int,
        imageData: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val mimeBytes = mimeType.toByteArray(StandardCharsets.UTF_8)
        val descriptionBytes = description.toByteArray(StandardCharsets.UTF_8)
        writeBe32(out, type)
        writeBe32(out, mimeBytes.size)
        out.write(mimeBytes)
        writeBe32(out, descriptionBytes.size)
        out.write(descriptionBytes)
        writeBe32(out, width)
        writeBe32(out, height)
        writeBe32(out, depth)
        writeBe32(out, colors)
        writeBe32(out, imageData.size)
        out.write(imageData)
        return out.toByteArray()
    }

    // ------------------------------------------------------------ ffmpeg tools

    fun ffprobePath(): String? =
        System.getProperty("ffprobe.path")?.takeIf { it.isNotBlank() }
            ?.let { File(it) }?.takeIf { it.canExecute() }?.absolutePath

    fun ffmpegPath(): String? =
        System.getProperty("ffmpeg.path")?.takeIf { it.isNotBlank() }
            ?.let { File(it) }?.takeIf { it.canExecute() }?.absolutePath

    fun assumeFfprobe(): String {
        val path = ffprobePath()
        Assume.assumeTrue("ffprobe 不可用，跳过外部校验（-Dffprobe.path）", path != null)
        return path!!
    }

    fun assumeFfmpeg(): String {
        val path = ffmpegPath()
        Assume.assumeTrue("ffmpeg 不可用，跳过外部校验（-Dffmpeg.path）", path != null)
        return path!!
    }

    fun run(exe: String, vararg arguments: String): ProcessResult {
        val command = listOf(exe) + arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.use { it.readBytes() }
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw AssertionError("command timed out: ${command.joinToString(" ")}")
        }
        return ProcessResult(process.exitValue(), String(output, StandardCharsets.UTF_8))
    }

    fun formatTags(file: File): Map<String, String> {
        val exe = assumeFfprobe()
        val result = run(
            exe,
            "-v", "error",
            "-show_entries", "format_tags",
            "-of", "default=noprint_wrappers=1",
            file.absolutePath,
        )
        require(result.exitCode == 0) { "ffprobe failed: ${result.output}" }
        val tags = LinkedHashMap<String, String>()
        for (line in result.output.lineSequence()) {
            if (!line.startsWith("TAG:")) continue
            val pair = line.substring(4)
            val equals = pair.indexOf('=')
            if (equals > 0) tags[pair.substring(0, equals).lowercase()] = pair.substring(equals + 1)
        }
        return tags
    }

    fun durationSeconds(file: File): Double {
        val exe = assumeFfprobe()
        val result = run(
            exe,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath,
        )
        require(result.exitCode == 0) { "ffprobe failed: ${result.output}" }
        return result.output.trim().toDouble()
    }

    fun attachedPictureCodecs(file: File): List<String> {
        val exe = assumeFfprobe()
        val result = run(
            exe,
            "-v", "error",
            "-show_entries", "stream=codec_name,codec_type:stream_disposition=attached_pic",
            "-of", "default=noprint_wrappers=1",
            file.absolutePath,
        )
        require(result.exitCode == 0) { "ffprobe failed: ${result.output}" }
        val codecs = ArrayList<String>()
        var currentCodec = ""
        for (line in result.output.lineSequence()) {
            if (line.startsWith("codec_name=")) {
                currentCodec = line.substring("codec_name=".length)
            } else if (line.contains("attached_pic=1")) {
                codecs.add(currentCodec)
            }
        }
        return codecs
    }

    fun decodesWithoutErrors(file: File): Boolean {
        val exe = assumeFfmpeg()
        val result = run(exe, "-v", "error", "-i", file.absolutePath, "-f", "null", "-")
        return result.exitCode == 0 && result.output.isBlank()
    }

    // ------------------------------------------------------------ byte helpers

    private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeBe32(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeBe64(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 56) and 0xFF).toInt())
        out.write(((value ushr 48) and 0xFF).toInt())
        out.write(((value ushr 40) and 0xFF).toInt())
        out.write(((value ushr 32) and 0xFF).toInt())
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    private fun writeBe16(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
