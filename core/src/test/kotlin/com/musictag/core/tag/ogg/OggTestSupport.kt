package com.musictag.core.tag.ogg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Independent Ogg test helper: it re-parses files with its own page reader,
 * checksum implementation and packet reassembler so tests do not simply trust
 * the production code they are validating.
 */
internal object OggTestSupport {

    private const val OGG_CRC_POLYNOMIAL = 0x04C11DB7

    // ------------------------------------------------------------- fixtures

    fun fixture(name: String, targetDir: File): File {
        val stream = OggTestSupport::class.java.getResourceAsStream("/fixtures/$name")
            ?: throw AssertionError("missing test fixture: $name")
        val target = File(targetDir, name)
        stream.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    fun fixtureBytes(name: String): ByteArray {
        val stream = OggTestSupport::class.java.getResourceAsStream("/fixtures/$name")
            ?: throw AssertionError("missing test fixture: $name")
        return stream.use { it.readBytes() }
    }

    // ----------------------------------------------------------- ogg parser

    internal class TestPage(
        val version: Int,
        val headerType: Int,
        val granulePosition: Long,
        val serialNumber: Long,
        val sequenceNumber: Long,
        val lacingValues: IntArray,
        val payload: ByteArray,
        val raw: ByteArray,
    ) {
        val isBos: Boolean get() = headerType and 0x02 != 0
        val isEos: Boolean get() = headerType and 0x04 != 0
        val isContinuation: Boolean get() = headerType and 0x01 != 0
    }

    internal class TestOggFile(
        val pages: List<TestPage>,
        val packets: List<ByteArray>,
        val headerPacketCount: Int,
        val headerPageCount: Int,
    ) {
        val audioPackets: List<ByteArray> get() = packets.drop(headerPacketCount)
        val audioPages: List<TestPage> get() = pages.drop(headerPageCount)
    }

    /** Non-reflected Ogg CRC-32, written independently of the production code. */
    fun crc32(bytes: ByteArray, start: Int, end: Int): Int {
        var crc = 0
        for (index in start until end) {
            crc = crc xor ((bytes[index].toInt() and 0xFF) shl 24)
            repeat(8) {
                crc = if (crc and Int.MIN_VALUE != 0) {
                    (crc shl 1) xor OGG_CRC_POLYNOMIAL
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }

    fun parsePages(bytes: ByteArray): List<TestPage> {
        val pages = ArrayList<TestPage>()
        var offset = 0
        while (offset < bytes.size) {
            assertTrue("truncated page header at offset $offset", bytes.size - offset >= 27)
            assertEquals(
                "capture pattern at offset $offset",
                "OggS",
                String(bytes, offset, 4, Charsets.US_ASCII),
            )
            val version = bytes[offset + 4].toInt() and 0xFF
            assertEquals("page version at offset $offset", 0, version)
            val headerType = bytes[offset + 5].toInt() and 0xFF
            val granulePosition = readInt64Le(bytes, offset + 6)
            val serialNumber = readUInt32Le(bytes, offset + 14)
            val sequenceNumber = readUInt32Le(bytes, offset + 18)
            val storedCrc = readUInt32Le(bytes, offset + 22)
            val segmentCount = bytes[offset + 26].toInt() and 0xFF
            assertTrue(
                "truncated lacing table at offset $offset",
                bytes.size - offset >= 27 + segmentCount,
            )
            val lacingValues = IntArray(segmentCount) { bytes[offset + 27 + it].toInt() and 0xFF }
            val payloadSize = lacingValues.sum()
            val payloadStart = offset + 27 + segmentCount
            assertTrue(
                "truncated page payload at offset $offset",
                bytes.size >= payloadStart + payloadSize,
            )
            val pageEnd = payloadStart + payloadSize
            val raw = bytes.copyOfRange(offset, pageEnd)

            val crcInput = raw.copyOf()
            crcInput[22] = 0
            crcInput[23] = 0
            crcInput[24] = 0
            crcInput[25] = 0
            val computedCrc = crc32(crcInput, 0, crcInput.size).toLong() and 0xFFFFFFFFL
            assertEquals("page crc at offset $offset", storedCrc, computedCrc)

            pages.add(
                TestPage(
                    version = version,
                    headerType = headerType,
                    granulePosition = granulePosition,
                    serialNumber = serialNumber,
                    sequenceNumber = sequenceNumber,
                    lacingValues = lacingValues,
                    payload = bytes.copyOfRange(payloadStart, pageEnd),
                    raw = raw,
                ),
            )
            offset = pageEnd
        }
        return pages
    }

    fun extractPackets(pages: List<TestPage>): List<ByteArray> {
        val packets = ArrayList<ByteArray>()
        val current = ByteArrayOutputStream()
        var continuationExpected = false
        pages.forEachIndexed { index, page ->
            assertEquals(
                "continuation flag of page $index",
                continuationExpected,
                page.isContinuation,
            )
            var position = 0
            for (lacingValue in page.lacingValues) {
                current.write(page.payload, position, lacingValue)
                position += lacingValue
                if (lacingValue < 255) {
                    packets.add(current.toByteArray())
                    current.reset()
                }
            }
            continuationExpected = page.lacingValues.isNotEmpty() && page.lacingValues.last() == 255
        }
        assertEquals("unfinished packet at end of stream", 0, current.size())
        return packets
    }

    private fun headerPacketCountOf(identificationPacket: ByteArray): Int = when {
        identificationPacket.size >= 7 &&
            identificationPacket[0] == 0x01.toByte() &&
            String(identificationPacket, 1, 6, Charsets.US_ASCII) == "vorbis" -> 3

        identificationPacket.size >= 8 &&
            String(identificationPacket, 0, 8, Charsets.US_ASCII) == "OpusHead" -> 2

        else -> throw AssertionError("unknown Ogg codec in fixture")
    }

    /** Parses a file and verifies CRC, sequence continuity, stream identity and packet framing. */
    fun assertIntegrity(file: File): TestOggFile {
        val bytes = file.readBytes()
        val pages = parsePages(bytes)
        assertTrue("${file.name}: file contains no Ogg pages", pages.isNotEmpty())
        assertTrue("${file.name}: first page must be BOS", pages.first().isBos)
        val firstPage = pages.first()
        assertEquals(
            "${file.name}: the BOS page must contain only the identification packet",
            1,
            firstPage.lacingValues.count { it < 255 },
        )
        assertTrue(
            "${file.name}: the identification packet must finish on the BOS page",
            firstPage.lacingValues.isNotEmpty() && firstPage.lacingValues.last() < 255,
        )
        assertEquals("${file.name}: BOS page granule position", 0L, firstPage.granulePosition)
        val serialNumber = firstPage.serialNumber
        pages.forEachIndexed { index, page ->
            assertEquals("${file.name}: serial of page $index", serialNumber, page.serialNumber)
            assertEquals("${file.name}: sequence of page $index", index.toLong(), page.sequenceNumber)
        }
        assertTrue("${file.name}: last page must be EOS", pages.last().isEos)

        val packets = extractPackets(pages)
        assertTrue("${file.name}: no packets", packets.isNotEmpty())
        val headerPacketCount = headerPacketCountOf(packets.first())
        assertTrue(
            "${file.name}: expected at least $headerPacketCount header packets, got ${packets.size}",
            packets.size >= headerPacketCount,
        )

        var completedPackets = 0
        var headerPageCount = -1
        for ((pageIndex, page) in pages.withIndex()) {
            for ((segmentIndex, lacingValue) in page.lacingValues.withIndex()) {
                if (lacingValue < 255) {
                    completedPackets++
                    if (completedPackets == headerPacketCount) {
                        headerPageCount = pageIndex + 1
                        assertEquals(
                            "${file.name}: last header packet must finish its page",
                            page.lacingValues.size - 1,
                            segmentIndex,
                        )
                    }
                }
                if (headerPageCount > 0) break
            }
            if (headerPageCount > 0) break
        }
        assertTrue("${file.name}: header packets were not found", headerPageCount in 1..pages.size)

        // Header pages that finish a packet carry granule position 0; pages that
        // end in the middle of a (multi-page) header packet carry -1.
        for (index in 1 until headerPageCount) {
            val page = pages[index]
            val expectedGranule = if (page.lacingValues.isNotEmpty() && page.lacingValues.last() == 255) -1L else 0L
            assertEquals(
                "${file.name}: granule position of header page $index",
                expectedGranule,
                page.granulePosition,
            )
        }
        if (headerPageCount < pages.size) {
            assertTrue(
                "${file.name}: first audio page must not continue a header packet",
                !pages[headerPageCount].isContinuation,
            )
        }
        return TestOggFile(pages, packets, headerPacketCount, headerPageCount)
    }

    fun commentPacketOf(file: File): ByteArray = assertIntegrity(file).packets[1]

    fun commentText(file: File, charset: java.nio.charset.Charset = Charsets.UTF_8): String =
        String(commentPacketOf(file), charset)

    // ------------------------------------------- synthetic muxer (test only)

    /**
     * Rebuilds [source] with a different comment header packet while keeping the
     * identification/setup packets and all audio packets. Used to create legacy
     * `COVERART` fixtures and header/audio entanglement cases.
     */
    fun rewriteComment(source: File, target: File, commentPacket: ByteArray, extraHeaderPackets: List<ByteArray> = emptyList()) {
        val parsed = assertIntegrity(source)
        val identification = parsed.packets[0]
        val setup = if (parsed.headerPacketCount == 3) parsed.packets[2] else null
        val packets = ArrayList<ByteArray>()
        packets.add(identification)
        packets.add(commentPacket)
        if (setup != null) packets.add(setup)
        packets.addAll(extraHeaderPackets)

        val headerPages = buildPages(parsed.pages.first().serialNumber, packets)
        target.outputStream().use { output ->
            for (page in headerPages) output.write(page)
            parsed.pages.drop(parsed.headerPageCount).forEachIndexed { index, page ->
                output.write(patchSequence(page.raw, headerPages.size.toLong() + index))
            }
        }
    }

    /** Builds header pages following the Ogg lacing/continuation rules. */
    fun buildPages(serialNumber: Long, packets: List<ByteArray>): List<ByteArray> {
        require(packets.isNotEmpty()) { "at least the identification packet is required" }
        val pages = ArrayList<ByteArray>()
        // The identification header sits alone on the BOS page.
        val firstLacing = lacingOf(packets[0])
        pages.add(buildPage(0x02, 0L, serialNumber, 0L, firstLacing.toIntArray(), packets[0]))

        val rest = packets.drop(1)
        if (rest.isEmpty()) return pages
        val lacingValues = ArrayList<Int>()
        val payloadCollector = ByteArrayOutputStream()
        for (packet in rest) {
            payloadCollector.write(packet)
            lacingValues.addAll(lacingOf(packet))
        }
        val payload = payloadCollector.toByteArray()
        var segmentIndex = 0
        var payloadOffset = 0
        var sequenceNumber = 1L
        var previousEndedMidPacket = false
        while (segmentIndex < lacingValues.size) {
            val count = minOf(255, lacingValues.size - segmentIndex)
            val pageLacing = IntArray(count) { lacingValues[segmentIndex + it] }
            val pagePayloadSize = pageLacing.sum()
            val pagePayload = payload.copyOfRange(payloadOffset, payloadOffset + pagePayloadSize)
            var headerType = 0
            if (previousEndedMidPacket) headerType = headerType or 0x01
            val granule = if (pageLacing[count - 1] == 255) -1L else 0L
            pages.add(buildPage(headerType, granule, serialNumber, sequenceNumber, pageLacing, pagePayload))
            previousEndedMidPacket = pageLacing[count - 1] == 255
            segmentIndex += count
            payloadOffset += pagePayloadSize
            sequenceNumber++
        }
        return pages
    }

    private fun lacingOf(packet: ByteArray): ArrayList<Int> {
        val lacingValues = ArrayList<Int>(packet.size / 255 + 1)
        var remaining = packet.size
        while (remaining >= 255) {
            lacingValues.add(255)
            remaining -= 255
        }
        lacingValues.add(remaining)
        return lacingValues
    }

    fun buildPage(
        headerType: Int,
        granulePosition: Long,
        serialNumber: Long,
        sequenceNumber: Long,
        lacingValues: IntArray,
        payload: ByteArray,
    ): ByteArray {
        val headerSize = 27 + lacingValues.size
        val raw = ByteArray(headerSize + payload.size)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(raw, 0)
        raw[4] = 0
        raw[5] = headerType.toByte()
        writeInt64Le(raw, 6, granulePosition)
        writeUInt32Le(raw, 14, serialNumber and 0xFFFFFFFFL)
        writeUInt32Le(raw, 18, sequenceNumber and 0xFFFFFFFFL)
        writeUInt32Le(raw, 22, 0)
        raw[26] = lacingValues.size.toByte()
        for (index in lacingValues.indices) {
            raw[27 + index] = lacingValues[index].toByte()
        }
        payload.copyInto(raw, headerSize)
        val crc = crc32(raw, 0, raw.size).toLong() and 0xFFFFFFFFL
        writeUInt32Le(raw, 22, crc)
        return raw
    }

    fun patchSequence(raw: ByteArray, newSequenceNumber: Long): ByteArray {
        val copy = raw.copyOf()
        writeUInt32Le(copy, 18, newSequenceNumber and 0xFFFFFFFFL)
        writeUInt32Le(copy, 22, 0)
        val crc = crc32(copy, 0, copy.size).toLong() and 0xFFFFFFFFL
        writeUInt32Le(copy, 22, crc)
        return copy
    }

    // ---------------------------------------------------- external tools

    internal class ToolResult(val exitCode: Int, val output: String)

    fun ffmpegPath(): String? =
        System.getProperty("ffmpeg.path")?.takeIf { it.isNotBlank() && File(it).canExecute() }

    fun ffprobePath(): String? =
        System.getProperty("ffprobe.path")?.takeIf { it.isNotBlank() && File(it).canExecute() }

    fun runTool(executable: String, vararg arguments: String): ToolResult {
        val command = ArrayList<String>(arguments.size + 1)
        command.add(executable)
        command.addAll(arguments)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        val exitCode = process.waitFor()
        return ToolResult(exitCode, output)
    }

    /** Runs `ffmpeg -v error -i file -f null -` and asserts a zero exit status. */
    fun assertDecodes(file: File) {
        val ffmpeg = ffmpegPath()
        assertNotNull("ffmpeg.path system property is not available", ffmpeg)
        val result = runTool(ffmpeg!!, "-v", "error", "-i", file.absolutePath, "-f", "null", "-")
        assertEquals("ffmpeg failed to decode ${file.name}:\n${result.output}", 0, result.exitCode)
    }

    fun probeDurationSeconds(file: File): Double {
        val ffprobe = ffprobePath()
        assertNotNull("ffprobe.path system property is not available", ffprobe)
        val result = runTool(
            ffprobe!!,
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            file.absolutePath,
        )
        assertEquals("ffprobe failed for ${file.name}:\n${result.output}", 0, result.exitCode)
        return result.output.trim().toDouble()
    }

    /**
     * Returns the format and (first) audio-stream tags reported by ffprobe, keyed
     * by lower-case tag name.
     *
     * The audio stream is selected explicitly because ffmpeg exposes an embedded
     * Ogg cover as an additional picture stream, and the picture description is
     * reported as that stream's `title` tag.
     */
    fun probeTags(file: File): Map<String, String> {
        val ffprobe = ffprobePath()
        assertNotNull("ffprobe.path system property is not available", ffprobe)
        val result = runTool(
            ffprobe!!,
            "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "format_tags:stream_tags",
            "-of", "default=noprint_wrappers=1",
            file.absolutePath,
        )
        assertEquals("ffprobe failed for ${file.name}:\n${result.output}", 0, result.exitCode)
        val tags = LinkedHashMap<String, String>()
        for (rawLine in result.output.lineSequence()) {
            val line = rawLine.trim()
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            var key = line.substring(0, separator)
            if (key.startsWith("TAG:")) key = key.removePrefix("TAG:")
            tags[key.lowercase()] = line.substring(separator + 1)
        }
        return tags
    }

    fun probeStreams(file: File): String {
        val ffprobe = ffprobePath()
        assertNotNull("ffprobe.path system property is not available", ffprobe)
        val result = runTool(ffprobe!!, "-v", "error", "-show_streams", file.absolutePath)
        assertEquals("ffprobe failed for ${file.name}:\n${result.output}", 0, result.exitCode)
        return result.output
    }
}

// ------------------------------------------------------------- binary helpers

internal fun readUInt32Le(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

internal fun readInt64Le(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (index in 7 downTo 0) value = (value shl 8) or (bytes[offset + index].toLong() and 0xFFL)
    return value
}

internal fun writeUInt32Le(bytes: ByteArray, offset: Int, value: Long) {
    bytes[offset] = (value and 0xFFL).toByte()
    bytes[offset + 1] = ((value ushr 8) and 0xFFL).toByte()
    bytes[offset + 2] = ((value ushr 16) and 0xFFL).toByte()
    bytes[offset + 3] = ((value ushr 24) and 0xFFL).toByte()
}

internal fun writeInt64Le(bytes: ByteArray, offset: Int, value: Long) {
    var remaining = value
    for (index in 0 until 8) {
        bytes[offset + index] = (remaining and 0xFFL).toByte()
        remaining = remaining ushr 8
    }
}
