package com.musictag.core.tag.mp3

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert
import org.junit.Assume

/**
 * Shared helpers for the MP3 tag engine tests: fixture copying, synthetic tag
 * construction, byte level tag inspection and ffmpeg/ffprobe invocation.
 *
 * Fixtures are always copied to a temp directory first: tests never modify
 * `src/test/resources`.
 */
internal object Mp3TestSupport {

    private val ISO = StandardCharsets.ISO_8859_1
    private val UTF8 = StandardCharsets.UTF_8
    private val UTF16LE = StandardCharsets.UTF_16LE

    val fixturesDir: File by lazy {
        val url = Mp3TestSupport::class.java.getResource("/fixtures")
            ?: throw IllegalStateException("fixtures resource directory is missing")
        File(url.toURI())
    }

    fun fixture(name: String): File = File(fixturesDir, name).also {
        Assert.assertTrue("fixture missing: $name", it.isFile)
    }

    val coverJpeg: ByteArray by lazy { File(fixturesDir, "cover.jpg").readBytes() }
    val coverPng: ByteArray by lazy { File(fixturesDir, "cover.png").readBytes() }

    private val tempDirs = ArrayList<File>()

    fun newTempDir(): File = Files.createTempDirectory("musictag-mp3-").toFile().also { tempDirs.add(it) }

    fun copyFixture(name: String): File {
        val target = File(newTempDir(), name)
        Files.copy(fixture(name).toPath(), target.toPath())
        return target
    }

    fun tempFile(name: String, bytes: ByteArray): File = File(newTempDir(), name).also { it.writeBytes(bytes) }

    fun cleanup() {
        for (directory in tempDirs) {
            directory.deleteRecursively()
        }
        tempDirs.clear()
    }

    // ------------------------------------------------------------------
    // Synthetic ID3v2 construction
    // ------------------------------------------------------------------

    fun syncSafe(value: Int): ByteArray {
        Assert.assertTrue("syncsafe out of range: $value", value in 0..0x0FFFFFFF)
        return byteArrayOf(
            ((value ushr 21) and 0x7F).toByte(),
            ((value ushr 14) and 0x7F).toByte(),
            ((value ushr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )
    }

    fun be32(value: Int): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        (value and 0xFF).toByte(),
    )

    fun header(major: Int, flags: Int, bodySize: Int): ByteArray =
        byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            major.toByte(), 0, flags.toByte(),
        ) + syncSafe(bodySize)

    fun tag(body: ByteArray, major: Int, flags: Int = 0): ByteArray =
        header(major, flags, body.size) + body

    /** v2.4 footer (`3DI` + version/flags/size) for tags with the footer flag set. */
    fun footer(major: Int, flags: Int, bodySize: Int): ByteArray =
        byteArrayOf('3'.code.toByte(), 'D'.code.toByte(), 'I'.code.toByte(), major.toByte(), 0, flags.toByte()) +
            syncSafe(bodySize)

    fun frameV22(id: String, payload: ByteArray): ByteArray {
        Assert.assertEquals("v2.2 frame id must be 3 chars", 3, id.length)
        val size = payload.size
        return id.toByteArray(ISO) + byteArrayOf(
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        ) + payload
    }

    fun frameV23(id: String, payload: ByteArray, flags: Int = 0): ByteArray {
        Assert.assertEquals("frame id must be 4 chars", 4, id.length)
        return id.toByteArray(ISO) + be32(payload.size) +
            byteArrayOf(((flags ushr 8) and 0xFF).toByte(), (flags and 0xFF).toByte()) + payload
    }

    fun frameV24(id: String, payload: ByteArray, flags: Int = 0): ByteArray {
        Assert.assertEquals("frame id must be 4 chars", 4, id.length)
        return id.toByteArray(ISO) + syncSafe(payload.size) +
            byteArrayOf(((flags ushr 8) and 0xFF).toByte(), (flags and 0xFF).toByte()) + payload
    }

    fun textPayloadV23Latin(text: String): ByteArray =
        byteArrayOf(0) + text.toByteArray(ISO) + byteArrayOf(0)

    fun textPayloadV23Utf16(text: String): ByteArray =
        byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(UTF16LE) + byteArrayOf(0, 0)

    fun textPayloadV24Utf8(text: String): ByteArray =
        byteArrayOf(3) + text.toByteArray(UTF8) + byteArrayOf(0)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) out.write(part)
        return out.toByteArray()
    }

    /** Applies ID3 unsynchronisation: inserts 0x00 after 0xFF when required. */
    fun unsynchronise(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size + 16)
        for (index in input.indices) {
            val value = input[index].toInt() and 0xFF
            out.write(value)
            if (value == 0xFF && index + 1 < input.size) {
                val next = input[index + 1].toInt() and 0xFF
                if (next == 0x00 || next >= 0xE0) out.write(0)
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Byte level inspection
    // ------------------------------------------------------------------

    private fun startsWithId3(bytes: ByteArray, offset: Int): Boolean =
        offset + 3 <= bytes.size &&
            bytes[offset] == 'I'.code.toByte() &&
            bytes[offset + 1] == 'D'.code.toByte() &&
            bytes[offset + 2] == '3'.code.toByte()

    private fun syncSafeAt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)

    private fun be32At(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** Offset of the first audio byte (skips ID3v2 tags, including v2.4 footers). */
    fun audioStart(bytes: ByteArray): Int {
        var start = 0
        var guard = 0
        while (guard < 8 && startsWithId3(bytes, start) && start + 10 <= bytes.size) {
            val major = bytes[start + 3].toInt() and 0xFF
            val flags = bytes[start + 5].toInt() and 0xFF
            val size = syncSafeAt(bytes, start + 6)
            val footer = if (major == 4 && (flags and 0x10) != 0) 10 else 0
            val next = start + 10 + size + footer
            if (next <= start || next > bytes.size) break
            start = next
            guard++
        }
        return start
    }

    /** Audio payload only: skips ID3v2 tags and a trailing ID3v1 tag. */
    fun audioBytes(bytes: ByteArray): ByteArray {
        val start = audioStart(bytes)
        var end = bytes.size
        if (end - start >= 128 &&
            bytes[end - 128] == 'T'.code.toByte() &&
            bytes[end - 127] == 'A'.code.toByte() &&
            bytes[end - 126] == 'G'.code.toByte()
        ) {
            end -= 128
        }
        return bytes.copyOfRange(start, end)
    }

    class RawFrame(val id: String, val flags: Int, val block: ByteArray, val payload: ByteArray)

    /**
     * Lists the raw frames of the first ID3v2 tag. Plain (non-unsynchronised)
     * tags only, which is enough for ffmpeg produced files and our own output.
     */
    fun rawFrames(bytes: ByteArray): List<RawFrame> {
        if (!startsWithId3(bytes, 0)) return emptyList()
        val major = bytes[3].toInt() and 0xFF
        val flags = bytes[5].toInt() and 0xFF
        val tagEnd = 10 + syncSafeAt(bytes, 6)
        if (tagEnd > bytes.size) return emptyList()
        var position = 10
        if ((flags and 0x40) != 0) {
            position = when (major) {
                3 -> 10 + 4 + be32At(bytes, 10)
                4 -> 10 + syncSafeAt(bytes, 10)
                else -> position
            }
        }
        val headerLength = if (major == 2) 6 else 10
        val result = ArrayList<RawFrame>()
        while (position + headerLength <= tagEnd) {
            if ((bytes[position].toInt() and 0xFF) == 0) break
            val frameId = String(bytes, position, if (major == 2) 3 else 4, ISO)
            val size = when (major) {
                2 -> ((bytes[position + 3].toInt() and 0xFF) shl 16) or
                    ((bytes[position + 4].toInt() and 0xFF) shl 8) or
                    (bytes[position + 5].toInt() and 0xFF)
                3 -> be32At(bytes, position + 4)
                else -> syncSafeAt(bytes, position + 4)
            }
            if (size < 0 || position + headerLength + size > bytes.size) break
            val frameFlags = if (major == 2) 0 else
                (((bytes[position + headerLength - 2].toInt() and 0xFF) shl 8) or
                    (bytes[position + headerLength - 1].toInt() and 0xFF))
            val block = bytes.copyOfRange(position, position + headerLength + size)
            result.add(RawFrame(frameId, frameFlags, block, block.copyOfRange(headerLength, block.size)))
            position += headerLength + size
        }
        return result
    }

    fun findFrameBlock(bytes: ByteArray, id: String): ByteArray? =
        rawFrames(bytes).firstOrNull { it.id == id }?.block

    fun countFrames(bytes: ByteArray, id: String): Int = rawFrames(bytes).count { it.id == id }

    fun majorVersion(bytes: ByteArray): Int = bytes[3].toInt() and 0xFF

    // ------------------------------------------------------------------
    // External tools (ffmpeg / ffprobe), guarded with JUnit Assume
    // ------------------------------------------------------------------

    private fun toolProperty(name: String): File? =
        System.getProperty(name)?.let { File(it) }?.takeIf { it.isFile }

    val ffmpeg: File? by lazy { toolProperty("ffmpeg.path") }
    val ffprobe: File? by lazy { toolProperty("ffprobe.path") }

    fun requireFfmpeg(): File {
        val tool = ffmpeg
        Assume.assumeTrue("ffmpeg not available (ffmpeg.path)", tool != null)
        return tool!!
    }

    fun requireFfprobe(): File {
        val tool = ffprobe
        Assume.assumeTrue("ffprobe not available (ffprobe.path)", tool != null)
        return tool!!
    }

    class ToolResult(val exitCode: Int, val output: String)

    fun run(exe: File, vararg args: String): ToolResult {
        val command = ArrayList<String>()
        command.add(exe.absolutePath)
        command.addAll(args)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.use { it.readBytes() }
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            Assert.fail("external tool timed out: ${command.joinToString(" ")}")
        }
        return ToolResult(process.exitValue(), String(output, UTF8))
    }

    fun ffprobe(file: File, vararg args: String): ToolResult {
        val probe = requireFfprobe()
        return run(probe, "-v", "error", *args, file.absolutePath)
    }

    fun formatTags(file: File): String {
        val result = ffprobe(
            file,
            "-show_entries", "format=format_name:format_tags",
            "-of", "default=noprint_wrappers=1",
        )
        Assert.assertEquals("ffprobe failed for ${file.name}:\n${result.output}", 0, result.exitCode)
        return result.output
    }

    fun streamInfo(file: File): String {
        val result = ffprobe(
            file,
            "-show_entries", "stream=codec_name,codec_type",
            "-of", "default=noprint_wrappers=1",
        )
        Assert.assertEquals("ffprobe failed for ${file.name}:\n${result.output}", 0, result.exitCode)
        return result.output
    }

    fun duration(file: File): Double {
        val result = ffprobe(
            file,
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
        )
        Assert.assertEquals("ffprobe duration failed for ${file.name}:\n${result.output}", 0, result.exitCode)
        return result.output.trim().toDouble()
    }

    fun assertDecodesCleanly(file: File) {
        val decoder = requireFfmpeg()
        val result = run(
            decoder, "-nostdin", "-v", "error", "-i", file.absolutePath, "-f", "null", "-",
        )
        Assert.assertEquals(
            "ffmpeg could not decode ${file.name} after tagging:\n${result.output}",
            0,
            result.exitCode,
        )
    }

    fun assertHasAttachedPicture(file: File, expectedCodec: String) {
        val streams = streamInfo(file)
        Assert.assertTrue("no attached picture stream in ${file.name}:\n$streams", streams.contains("codec_type=video"))
        Assert.assertTrue(
            "expected $expectedCodec cover in ${file.name}:\n$streams",
            streams.contains("codec_name=$expectedCodec"),
        )
    }
}
