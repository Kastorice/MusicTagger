package com.musictag.core.tag.mp4

import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Shared fixtures and external tool helpers for the MP4 tag tests.
 *
 * Fixtures are copied into per-test temp folders; the ones in `src/test/resources`
 * are never modified. ffmpeg/ffprobe are optional (system properties) and every
 * test that needs them degrades to skipped through JUnit [Assume].
 */
internal object Mp4TestSupport {

    private val fixtureDirectory: File by lazy {
        val resource = Mp4TestSupport::class.java.getResource("/fixtures/bare.m4a")
            ?: error("fixture directory missing from test classpath")
        File(resource.toURI()).parentFile
    }

    fun fixture(name: String): File {
        val file = File(fixtureDirectory, name)
        check(file.isFile) { "fixture $name is missing" }
        return file
    }

    fun copyFixture(name: String, targetDirectory: File): File {
        val target = File(targetDirectory, name)
        Files.copy(fixture(name).toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    fun bytesOf(file: File): ByteArray = Files.readAllBytes(file.toPath())

    val ffprobeBinary: File?
        get() = System.getProperty("ffprobe.path")
            ?.let { File(it) }
            ?.takeIf { it.isFile && it.canExecute() }

    val ffmpegBinary: File?
        get() = System.getProperty("ffmpeg.path")
            ?.let { File(it) }
            ?.takeIf { it.isFile && it.canExecute() }

    fun requireFfprobe(): File {
        val binary = ffprobeBinary
        Assume.assumeTrue("ffprobe is not available; skipping external verification", binary != null)
        return binary!!
    }

    fun requireFfmpeg(): File {
        val binary = ffmpegBinary
        Assume.assumeTrue("ffmpeg is not available; skipping external verification", binary != null)
        return binary!!
    }

    fun containsChinese(text: String): Boolean = text.any { it.code in 0x4E00..0x9FFF }

    fun pngCover(): Artwork = Artwork(bytesOf(fixture("cover.png")), "image/png")

    fun jpegCover(): Artwork = Artwork(bytesOf(fixture("cover.jpg")), "image/jpeg")

    fun sampleDocument(): TagDocument = TagDocument(
        title = "七里香",
        artist = "周杰倫",
        album = "七里香",
        albumArtist = "周杰倫",
        year = "2004",
        trackNumber = "3",
        discNumber = "1",
        genre = "華語流行",
        comment = "測試註解",
        lyrics = "秋刀魚的滋味\n貓跟你都想了解",
    )

    /** Lyrics big enough to grow moov by well over 100 KiB. */
    fun largeLyrics(): String = (1..2000).joinToString("\n") {
        "第${it}行歌詞：窗外的麻雀在電線桿上多嘴，你說這一句很有夏天的感覺。"
    }

    // ------------------------------------------------------------- external tools

    private class RunResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun run(vararg command: String): RunResult {
        val stdoutFile = File.createTempFile("mp4test-", ".out")
        val stderrFile = File.createTempFile("mp4test-", ".err")
        try {
            val process = ProcessBuilder(*command)
                .redirectOutput(stdoutFile)
                .redirectError(stderrFile)
                .start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
                throw AssertionError("external command timed out: ${command.joinToString(" ")}")
            }
            return RunResult(
                process.exitValue(),
                stdoutFile.readText(StandardCharsets.UTF_8),
                stderrFile.readText(StandardCharsets.UTF_8),
            )
        } finally {
            stdoutFile.delete()
            stderrFile.delete()
        }
    }

    /** `format_tags` as a name -> value map (including `encoder`, `major_brand`, ...). */
    fun probeTags(file: File): Map<String, String> {
        val ffprobe = requireFfprobe()
        val result = run(
            ffprobe.absolutePath,
            "-v", "error",
            "-show_entries", "format_tags",
            "-of", "default=noprint_wrappers=1",
            file.absolutePath,
        )
        assertEquals("ffprobe failed: ${result.stderr}", 0, result.exitCode)
        val tags = LinkedHashMap<String, String>()
        for (line in result.stdout.lineSequence()) {
            if (!line.startsWith("TAG:")) continue
            val body = line.substring(4)
            val separator = body.indexOf('=')
            if (separator <= 0) continue
            tags[body.substring(0, separator)] = body.substring(separator + 1)
        }
        return tags
    }

    /** (codec_name, codec_type) for every stream reported by ffprobe. */
    fun probeStreams(file: File): List<Pair<String, String>> {
        val ffprobe = requireFfprobe()
        val result = run(
            ffprobe.absolutePath,
            "-v", "error",
            "-show_entries", "stream=codec_name,codec_type",
            "-of", "default=noprint_wrappers=1",
            file.absolutePath,
        )
        assertEquals("ffprobe failed: ${result.stderr}", 0, result.exitCode)
        val streams = ArrayList<Pair<String, String>>()
        var codecName: String? = null
        for (line in result.stdout.lineSequence()) {
            when {
                line.startsWith("codec_name=") -> codecName = line.substring("codec_name=".length)
                line.startsWith("codec_type=") -> {
                    streams += (codecName ?: "") to line.substring("codec_type=".length)
                    codecName = null
                }
            }
        }
        return streams
    }

    fun durationSeconds(file: File): Double {
        val ffprobe = requireFfprobe()
        val result = run(
            ffprobe.absolutePath,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath,
        )
        assertEquals("ffprobe failed: ${result.stderr}", 0, result.exitCode)
        return result.stdout.trim().toDoubleOrNull()
            ?: throw AssertionError("unreadable duration for ${file.name}: ${result.stdout}")
    }

    fun audioPacketCount(file: File): Int {
        val ffprobe = requireFfprobe()
        val result = run(
            ffprobe.absolutePath,
            "-v", "error",
            "-count_packets",
            "-select_streams", "a:0",
            "-show_entries", "stream=nb_read_packets",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath,
        )
        assertEquals("ffprobe failed: ${result.stderr}", 0, result.exitCode)
        return result.stdout.trim().toIntOrNull()
            ?: throw AssertionError("unreadable packet count for ${file.name}: ${result.stdout}")
    }

    /**
     * Decodes the whole file to /dev/null. Any wrong `stco`/`co64` offset makes
     * the demuxer read garbage and report an error, so exit code 0 with an empty
     * stderr proves the patched offset table is correct.
     */
    fun assertAudioDecodes(file: File) {
        val ffmpeg = requireFfmpeg()
        val result = run(
            ffmpeg.absolutePath,
            "-v", "error",
            "-i", file.absolutePath,
            "-f", "null", "-",
        )
        assertEquals(
            "ffmpeg could not decode ${file.name}: ${result.stderr}",
            0,
            result.exitCode,
        )
        assertTrue(
            "ffmpeg reported errors for ${file.name}: ${result.stderr}",
            result.stderr.isBlank(),
        )
    }

    // ------------------------------------------------------- structural helpers

    internal class TestBox(val type: String, val offset: Long, val headerSize: Int, val size: Long) {
        val payloadOffset: Long get() = offset + headerSize
    }

    fun topLevelBoxes(file: File): List<TestBox> {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            var position = 0L
            val boxes = ArrayList<TestBox>()
            while (position + 8 <= length) {
                raf.seek(position)
                var size = raf.readInt().toLong() and 0xFFFF_FFFFL
                val typeBytes = ByteArray(4)
                raf.readFully(typeBytes)
                val type = String(typeBytes, StandardCharsets.ISO_8859_1)
                var headerSize = 8
                if (size == 1L) {
                    size = raf.readLong()
                    headerSize = 16
                } else if (size == 0L) {
                    size = length - position
                }
                if (size < headerSize) break
                boxes += TestBox(type, position, headerSize, size)
                position += size
            }
            return boxes
        }
    }

    fun boxOf(file: File, type: String): TestBox? = topLevelBoxes(file).firstOrNull { it.type == type }

    fun boxBytes(file: File, box: TestBox): ByteArray {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(box.offset)
            val data = ByteArray(box.size.toInt())
            raf.readFully(data)
            return data
        }
    }

    /** First `stco` entry read straight from the file (independent of the engine). */
    fun firstStcoEntry(file: File): Long {
        val moov = boxOf(file, "moov") ?: throw AssertionError("moov box missing in ${file.name}")
        val moovBytes = boxBytes(file, moov)
        val index = indexOfBytes(moovBytes, "stco".toByteArray(StandardCharsets.ISO_8859_1))
        if (index < 0) throw AssertionError("stco box missing in ${file.name}")
        // fourcc at index, then version/flags (4) and entry_count (4), then first entry.
        return bigEndianU32(moovBytes, index + 4 + 8)
    }

    /** True when the `moov` payload contains [text] (used to prove hdlr creation). */
    fun moovContainsAscii(file: File, text: String): Boolean {
        val moov = boxOf(file, "moov") ?: return false
        return indexOfBytes(boxBytes(file, moov), text.toByteArray(StandardCharsets.ISO_8859_1)) >= 0
    }

    // ------------------------------------------------- synthetic fixture helpers

    private class RawBox(val type: String, val raw: ByteArray)

    /**
     * Writes a copy of [source] whose moov subtree is missing the box chain [chain],
     * e.g. `listOf("udta")`, `listOf("udta", "meta")` or
     * `listOf("udta", "meta", "ilst")`.
     *
     * Only valid when `moov` is the last top-level box (bare.m4a / tagged.m4a):
     * all media data then precedes moov, so removing boxes cannot invalidate the
     * chunk offset table. This lets tests exercise the "create metadata
     * container" code paths that the shipped fixtures do not cover.
     */
    fun copyWithoutBoxChain(source: File, target: File, chain: List<String>) {
        require(chain.isNotEmpty()) { "chain must not be empty" }
        val boxes = topLevelBoxes(source)
        require(boxes.lastOrNull()?.type == "moov") { "${source.name} must end with moov" }
        val moovBytes = boxBytes(source, boxes.last())
        val rebuilt = removeBoxChain(moovBytes, chain)
            ?: throw AssertionError("box chain $chain not found in ${source.name}")
        val output = java.io.ByteArrayOutputStream()
        for (box in boxes.dropLast(1)) output.write(boxBytes(source, box))
        output.write(rebuilt)
        Files.write(target.toPath(), output.toByteArray())
    }

    private fun removeBoxChain(boxBytes: ByteArray, chain: List<String>): ByteArray? {
        val headerSize = if (bigEndianU32(boxBytes, 0) == 1L) 16 else 8
        val type = String(boxBytes, 4, 4, StandardCharsets.ISO_8859_1)
        val prefixLength = if (type == "meta") 4 else 0
        val children = childBoxesRaw(boxBytes, headerSize + prefixLength, boxBytes.size)
        val targetIndex = children.indexOfFirst { it.type == chain.first() }
        if (targetIndex < 0) return null
        val kept = ArrayList<ByteArray>(children.size)
        var removed = false
        for ((index, child) in children.withIndex()) {
            if (index != targetIndex) {
                kept += child.raw
                continue
            }
            if (chain.size == 1) {
                removed = true
                continue
            }
            val rebuiltChild = removeBoxChain(child.raw, chain.drop(1))
            if (rebuiltChild == null) kept += child.raw else { kept += rebuiltChild; removed = true }
        }
        if (!removed) return null
        return buildRawBox(
            type,
            headerSize,
            boxBytes.copyOfRange(headerSize, headerSize + prefixLength),
            kept,
        )
    }

    private fun childBoxesRaw(region: ByteArray, start: Int, end: Int): List<RawBox> {
        val children = ArrayList<RawBox>()
        var position = start
        while (end - position >= 8) {
            var size = bigEndianU32(region, position)
            val type = String(region, position + 4, 4, StandardCharsets.ISO_8859_1)
            if (size == 1L) {
                size = bigEndianU64(region, position + 8)
            } else if (size == 0L) {
                size = (end - position).toLong()
            }
            if (size < 8L || position + size > end) break
            children += RawBox(type, region.copyOfRange(position, position + size.toInt()))
            position += size.toInt()
        }
        return children
    }

    private fun buildRawBox(
        type: String,
        headerSize: Int,
        prefix: ByteArray,
        children: List<ByteArray>,
    ): ByteArray {
        var contentSize = prefix.size
        for (child in children) contentSize += child.size
        val total = headerSize + contentSize
        val output = ByteArray(total)
        if (headerSize == 16) {
            bigEndianPutU32(output, 0, 1L)
            type.toByteArray(StandardCharsets.ISO_8859_1).copyInto(output, 4)
            bigEndianPutU64(output, 8, total.toLong())
        } else {
            bigEndianPutU32(output, 0, total.toLong())
            type.toByteArray(StandardCharsets.ISO_8859_1).copyInto(output, 4)
        }
        var position = headerSize
        prefix.copyInto(output, position)
        position += prefix.size
        for (child in children) {
            child.copyInto(output, position)
            position += child.size
        }
        return output
    }

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun bigEndianU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun bigEndianU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private fun bigEndianPutU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun bigEndianPutU64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) bytes[offset + i] = (value ushr ((7 - i) * 8)).toByte()
    }
}
