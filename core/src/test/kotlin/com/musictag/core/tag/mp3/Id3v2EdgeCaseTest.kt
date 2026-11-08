package com.musictag.core.tag.mp3

import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte level edge cases of the ID3v2 parser/writer: v2.2 upgrade,
 * unsynchronisation (tag level and per frame), extended headers, the v2.4
 * footer, duplicated leading tags and corrupt/truncated input.
 */
class Id3v2EdgeCaseTest {

    private val ISO = StandardCharsets.ISO_8859_1
    private val UTF8 = StandardCharsets.UTF_8

    private val audio: ByteArray by lazy {
        Mp3TestSupport.audioBytes(Mp3TestSupport.fixture("bare.mp3").readBytes())
    }

    @After
    fun tearDown() {
        Mp3TestSupport.cleanup()
    }

    // ------------------------------------------------------------------
    // ID3v2.2
    // ------------------------------------------------------------------

    @Test
    fun `reads id3v2_2 tag and upgrades it to v2_3 on write`() {
        val tssePayload = "TEST".toByteArray(ISO) + byteArrayOf(0)
        val body = Mp3TestSupport.concat(
            Mp3TestSupport.frameV22("TT2", Mp3TestSupport.textPayloadV23Latin("Two Two Title")),
            Mp3TestSupport.frameV22("TP1", Mp3TestSupport.textPayloadV23Latin("Two Two Artist")),
            Mp3TestSupport.frameV22("TAL", Mp3TestSupport.textPayloadV23Latin("Two Two Album")),
            Mp3TestSupport.frameV22("TYE", Mp3TestSupport.textPayloadV23Latin("1998")),
            Mp3TestSupport.frameV22("TRK", Mp3TestSupport.textPayloadV23Latin("7")),
            Mp3TestSupport.frameV22("TCO", Mp3TestSupport.textPayloadV23Latin("Rock")),
            // TSS maps to TSSE and must survive the upgrade ...
            Mp3TestSupport.frameV22("TSS", tssePayload),
            // ... while an unknown v2.2-only ID has no v2.3 equivalent.
            Mp3TestSupport.frameV22("XYZ", byteArrayOf(1, 2, 3)),
        )
        val file = Mp3TestSupport.tempFile(
            "v22.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.tag(body, 2), audio),
        )

        val document = Mp3Format.read(file)
        assertEquals("Two Two Title", document.title)
        assertEquals("Two Two Artist", document.artist)
        assertEquals("Two Two Album", document.album)
        assertEquals("1998", document.year)
        assertEquals("7", document.trackNumber)
        assertEquals("Rock", document.genre)

        val replacement = TagDocument(title = "升级标题", artist = "升级歌手")
        Mp3Format.write(file, replacement)

        val written = file.readBytes()
        assertEquals("v2.2 must be upgraded to v2.3", 3, Mp3TestSupport.majorVersion(written))
        assertArrayEquals("audio must stay byte identical", audio, Mp3TestSupport.audioBytes(written))
        assertEquals(replacement.normalized(), Mp3Format.read(file))

        val tsse = Mp3TestSupport.rawFrames(written).firstOrNull { it.id == "TSSE" }
        assertNotNull("v2.2 TSS frame was not mapped to TSSE", tsse)
        assertArrayEquals("mapped v2.2 frame payload changed", tssePayload, tsse!!.payload)
        assertFalse("unknown v2.2 frame ids cannot be upgraded", Mp3TestSupport.rawFrames(written).any { it.id == "XYZ" })
    }

    // ------------------------------------------------------------------
    // Unsynchronisation
    // ------------------------------------------------------------------

    @Test
    fun `reads unsynchronised id3v2_3 tag and writes a plain tag`() {
        val plainBody = Mp3TestSupport.concat(
            Mp3TestSupport.frameV23("TIT2", Mp3TestSupport.textPayloadV23Utf16("Unsync Title")),
            Mp3TestSupport.frameV23("TPE1", Mp3TestSupport.textPayloadV23Latin("Unsync Artist")),
            Mp3TestSupport.frameV23("TSSE", Mp3TestSupport.textPayloadV23Latin("Lavf")),
        )
        val unsynchronisedBody = Mp3TestSupport.unsynchronise(plainBody)
        assertTrue("test data must actually need unsynchronisation",
            unsynchronisedBody.size > plainBody.size)
        val file = Mp3TestSupport.tempFile(
            "unsync.mp3",
            Mp3TestSupport.concat(
                Mp3TestSupport.header(3, 0x80, unsynchronisedBody.size),
                unsynchronisedBody,
                audio,
            ),
        )

        val document = Mp3Format.read(file)
        assertEquals("Unsync Title", document.title)
        assertEquals("Unsync Artist", document.artist)

        val replacement = TagDocument(title = "同步标题", artist = "同步歌手")
        Mp3Format.write(file, replacement)

        val written = file.readBytes()
        assertEquals(3, Mp3TestSupport.majorVersion(written))
        assertEquals("written tag must not carry the unsynchronisation flag",
            0, written[5].toInt() and 0x80)
        assertArrayEquals(audio, Mp3TestSupport.audioBytes(written))
        assertEquals(replacement.normalized(), Mp3Format.read(file))
        assertNotNull("TSSE frame should be preserved", Mp3TestSupport.findFrameBlock(written, "TSSE"))
    }

    @Test
    fun `reads id3v2_4 tag level unsynchronisation`() {
        val plainBody = Mp3TestSupport.frameV24("TIT2", Mp3TestSupport.textPayloadV23Utf16("Tag Unsync"))
        val unsynchronisedBody = Mp3TestSupport.unsynchronise(plainBody)
        val file = Mp3TestSupport.tempFile(
            "unsync24.mp3",
            Mp3TestSupport.concat(
                Mp3TestSupport.header(4, 0x80, unsynchronisedBody.size),
                unsynchronisedBody,
                audio,
            ),
        )
        assertEquals("Tag Unsync", Mp3Format.read(file).title)
    }

    @Test
    fun `reads id3v2_4 per frame unsynchronisation and preserves the frame`() {
        val logicalTxxx = Mp3TestSupport.concat(
            byteArrayOf(0), // ISO-8859-1
            "desc".toByteArray(ISO),
            byteArrayOf(0),
            byteArrayOf(0xFF.toByte(), 0xE0.toByte()),
        )
        val storedTxxx = Mp3TestSupport.unsynchronise(logicalTxxx)
        assertTrue(storedTxxx.size > logicalTxxx.size)
        val body = Mp3TestSupport.concat(
            Mp3TestSupport.frameV24("TIT2", Mp3TestSupport.textPayloadV24Utf8("Per Frame Title")),
            Mp3TestSupport.frameV24("TXXX", storedTxxx, flags = 0x0002),
        )
        val file = Mp3TestSupport.tempFile(
            "frame_unsync.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.tag(body, 4), audio),
        )
        assertEquals("Per Frame Title", Mp3Format.read(file).title)

        Mp3Format.write(file, TagDocument(title = "逐帧标题"))

        val written = file.readBytes()
        val txxx = Mp3TestSupport.rawFrames(written).firstOrNull { it.id == "TXXX" }
        assertNotNull("TXXX frame lost", txxx)
        assertFalse("per frame unsync bit must be cleared after de-unsynchronisation",
            (txxx!!.flags and 0x0002) != 0)
        assertArrayEquals("unsynchronised payload was not restored", logicalTxxx, txxx.payload)
        assertArrayEquals(audio, Mp3TestSupport.audioBytes(written))
        assertEquals("逐帧标题", Mp3Format.read(file).title)
    }

    // ------------------------------------------------------------------
    // Extended headers and footer
    // ------------------------------------------------------------------

    @Test
    fun `reads id3v2_3 extended header`() {
        // v2.3 extended header: size (excluding itself) = 6, flags, padding size.
        val extendedHeader = Mp3TestSupport.concat(
            Mp3TestSupport.be32(6),
            byteArrayOf(0, 0),
            Mp3TestSupport.be32(0),
        )
        val body = Mp3TestSupport.concat(
            extendedHeader,
            Mp3TestSupport.frameV23("TIT2", Mp3TestSupport.textPayloadV23Latin("Extended Title")),
            Mp3TestSupport.frameV23("TPE1", Mp3TestSupport.textPayloadV23Latin("Extended Artist")),
        )
        val file = Mp3TestSupport.tempFile(
            "ext23.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.header(3, 0x40, body.size), body, audio),
        )
        val document = Mp3Format.read(file)
        assertEquals("Extended Title", document.title)
        assertEquals("Extended Artist", document.artist)
    }

    @Test
    fun `reads id3v2_4 extended header`() {
        // v2.4 extended header: syncsafe size including itself = 6, flag byte count, flags.
        val extendedHeader = Mp3TestSupport.concat(
            Mp3TestSupport.syncSafe(6),
            byteArrayOf(1, 0),
        )
        val body = Mp3TestSupport.concat(
            extendedHeader,
            Mp3TestSupport.frameV24("TIT2", Mp3TestSupport.textPayloadV24Utf8("Ext24 Title")),
        )
        val file = Mp3TestSupport.tempFile(
            "ext24.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.header(4, 0x40, body.size), body, audio),
        )
        assertEquals("Ext24 Title", Mp3Format.read(file).title)
    }

    @Test
    fun `reads id3v2_4 footer and keeps audio intact on write`() {
        val body = Mp3TestSupport.concat(
            Mp3TestSupport.frameV24("TIT2", Mp3TestSupport.textPayloadV24Utf8("Footer Title")),
            Mp3TestSupport.frameV24("TSSE", Mp3TestSupport.textPayloadV24Utf8("Lavf")),
        )
        val file = Mp3TestSupport.tempFile(
            "footer.mp3",
            Mp3TestSupport.concat(
                Mp3TestSupport.header(4, 0x10, body.size),
                body,
                Mp3TestSupport.footer(4, 0x10, body.size),
                audio,
            ),
        )
        assertEquals("Footer Title", Mp3Format.read(file).title)

        Mp3Format.write(file, TagDocument(title = "页脚标题"))
        val written = file.readBytes()
        assertEquals(4, Mp3TestSupport.majorVersion(written))
        assertArrayEquals(audio, Mp3TestSupport.audioBytes(written))
        assertEquals("页脚标题", Mp3Format.read(file).title)
        assertNotNull(Mp3TestSupport.findFrameBlock(written, "TSSE"))
    }

    // ------------------------------------------------------------------
    // Duplicated tags
    // ------------------------------------------------------------------

    @Test
    fun `stale duplicate leading id3v2 tag is dropped on write`() {
        val first = Mp3TestSupport.tag(
            Mp3TestSupport.frameV23("TIT2", Mp3TestSupport.textPayloadV23Latin("First Tag")),
            3,
        )
        val second = Mp3TestSupport.tag(
            Mp3TestSupport.frameV23("TIT2", Mp3TestSupport.textPayloadV23Latin("Second Tag")),
            3,
        )
        val file = Mp3TestSupport.tempFile(
            "doubletag.mp3",
            Mp3TestSupport.concat(first, second, audio),
        )
        assertEquals("First Tag", Mp3Format.read(file).title)

        Mp3Format.write(file, TagDocument(title = "唯一标题"))

        val written = file.readBytes()
        assertEquals(1, Mp3TestSupport.countFrames(written, "TIT2"))
        assertArrayEquals(audio, Mp3TestSupport.audioBytes(written))
        assertEquals("唯一标题", Mp3Format.read(file).title)
    }

    // ------------------------------------------------------------------
    // Corrupt input
    // ------------------------------------------------------------------

    @Test
    fun `tag size larger than the file is rejected`() {
        val file = Mp3TestSupport.tempFile(
            "size_overflow.mp3",
            Mp3TestSupport.header(3, 0, 0x0FFFFFFF),
        )
        val error = assertThrows(TagException::class.java) { Mp3Format.read(file) }
        assertTrue("unexpected message: ${error.message}", error.message!!.contains("ID3"))
        assertThrows(TagException::class.java) { Mp3Format.write(file, TagDocument(title = "x")) }
    }

    @Test
    fun `frame length beyond the tag body is rejected`() {
        val body = Mp3TestSupport.concat(
            "TIT2".toByteArray(ISO),
            Mp3TestSupport.be32(4096),
            byteArrayOf(0, 0),
            "abc".toByteArray(ISO),
        )
        val file = Mp3TestSupport.tempFile(
            "frame_overflow.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.header(3, 0, body.size), body, audio),
        )
        val error = assertThrows(TagException::class.java) { Mp3Format.read(file) }
        assertTrue("unexpected message: ${error.message}", error.message!!.contains("长度"))
    }

    @Test
    fun `id3v2_4 frame length with a high bit set is rejected`() {
        val body = Mp3TestSupport.concat(
            "TIT2".toByteArray(ISO),
            byteArrayOf(0x80.toByte(), 0, 0, 6),
            byteArrayOf(0, 0),
            "abcdef".toByteArray(ISO),
        )
        val file = Mp3TestSupport.tempFile(
            "bad_syncsafe.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.header(4, 0, body.size), body, audio),
        )
        val error = assertThrows(TagException::class.java) { Mp3Format.read(file) }
        assertTrue("unexpected message: ${error.message}", error.message!!.contains("长度字段无效"))
    }

    @Test
    fun `unsupported id3v2 major version is rejected`() {
        val file = Mp3TestSupport.tempFile(
            "v25.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.header(5, 0, 0), audio),
        )
        val error = assertThrows(TagException::class.java) { Mp3Format.read(file) }
        assertTrue("unexpected message: ${error.message}", error.message!!.contains("版本"))
    }

    @Test
    fun `id3v2_2 compressed tag is rejected`() {
        val file = Mp3TestSupport.tempFile(
            "v22compressed.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.header(2, 0x40, 0), audio),
        )
        val error = assertThrows(TagException::class.java) { Mp3Format.read(file) }
        assertTrue("unexpected message: ${error.message}", error.message!!.contains("压缩"))
    }

    // ------------------------------------------------------------------
    // No-op writes
    // ------------------------------------------------------------------

    @Test
    fun `writing an empty document to untagged audio does not touch the file`() {
        val file = Mp3TestSupport.copyFixture("bare.aac")
        val original = file.readBytes()
        Mp3Format.write(file, TagDocument())
        assertArrayEquals("file should not change", original, file.readBytes())
    }

    // ------------------------------------------------------------------
    // Text encodings
    // ------------------------------------------------------------------

    @Test
    fun `encodings follow the v2_3 latin1 utf16 and v2_4 utf8 policy`() {
        // v2.3 + ASCII: encoding 0 (ISO-8859-1).
        val v23File = Mp3TestSupport.copyFixture("bare.mp3")
        Mp3Format.write(v23File, TagDocument(title = "Plain Title", artist = "Plain Artist"))
        val v23Title = Mp3TestSupport.rawFrames(v23File.readBytes()).first { it.id == "TIT2" }
        assertEquals("v2.3 ASCII must use ISO-8859-1", 0, v23Title.payload[0].toInt())
        assertEquals("Plain Title", Mp3Format.read(v23File).title)

        // v2.3 + Chinese: encoding 1 (UTF-16 with BOM).
        val v23Chinese = Mp3TestSupport.copyFixture("bare.mp3")
        Mp3Format.write(v23Chinese, TagDocument(title = "中文标题"))
        val utf16Title = Mp3TestSupport.rawFrames(v23Chinese.readBytes()).first { it.id == "TIT2" }
        assertEquals("v2.3 non latin1 must use UTF-16", 1, utf16Title.payload[0].toInt())
        assertEquals(0xFF, utf16Title.payload[1].toInt() and 0xFF)
        assertEquals(0xFE, utf16Title.payload[2].toInt() and 0xFF)
        assertEquals("中文标题", Mp3Format.read(v23Chinese).title)

        // v2.4 always uses UTF-8 (encoding 3), even for ASCII.
        val v24File = Mp3TestSupport.copyFixture("tagged_v24.mp3")
        Mp3Format.write(v24File, TagDocument(title = "Plain Title"))
        val v24Title = Mp3TestSupport.rawFrames(v24File.readBytes()).first { it.id == "TIT2" }
        assertEquals("v2.4 must use UTF-8", 3, v24Title.payload[0].toInt())
        assertEquals("Plain Title", Mp3Format.read(v24File).title)
    }

    @Test
    fun `reads UTF-16BE text encoding 2`() {
        val payload = Mp3TestSupport.concat(
            byteArrayOf(2), // UTF-16BE without BOM
            "Big Endian".toByteArray(StandardCharsets.UTF_16BE),
            byteArrayOf(0, 0),
        )
        val body = Mp3TestSupport.frameV23("TIT2", payload)
        val file = Mp3TestSupport.tempFile(
            "utf16be.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.tag(body, 3), audio),
        )
        assertEquals("Big Endian", Mp3Format.read(file).title)
    }

    @Test
    fun `genre string with parentheses is kept as is`() {
        val body = Mp3TestSupport.frameV23("TCON", Mp3TestSupport.textPayloadV23Latin("(13)"))
        val file = Mp3TestSupport.tempFile(
            "genre13.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.tag(body, 3), audio),
        )
        assertEquals("(13)", Mp3Format.read(file).genre)
    }

    // ------------------------------------------------------------------
    // Artwork selection
    // ------------------------------------------------------------------

    @Test
    fun `front cover picture type 3 is preferred over other pictures`() {
        fun apic(pictureType: Int, mime: String, bytes: ByteArray): ByteArray = Mp3TestSupport.concat(
            byteArrayOf(0),
            mime.toByteArray(ISO),
            byteArrayOf(0),
            byteArrayOf(pictureType.toByte()),
            byteArrayOf(0),
            bytes,
        )
        val body = Mp3TestSupport.concat(
            // A non front-cover image comes first ...
            Mp3TestSupport.frameV23("APIC", apic(0, "image/png", Mp3TestSupport.coverPng)),
            // ... but the front cover must win.
            Mp3TestSupport.frameV23("APIC", apic(3, "image/jpeg", Mp3TestSupport.coverJpeg)),
        )
        val file = Mp3TestSupport.tempFile(
            "two_covers.mp3",
            Mp3TestSupport.concat(Mp3TestSupport.tag(body, 3), audio),
        )
        val artwork = Mp3Format.read(file).artwork
        assertNotNull(artwork)
        assertEquals("image/jpeg", artwork!!.mimeType)
        assertArrayEquals(Mp3TestSupport.coverJpeg, artwork.bytes)
    }

    // ------------------------------------------------------------------
    // Failure safety
    // ------------------------------------------------------------------

    @Test
    fun `failed write leaves the original file untouched and no temp file`() {
        val file = Mp3TestSupport.copyFixture("bare.mp3")
        val original = file.readBytes()
        file.setReadOnly()
        try {
            assertThrows(TagException::class.java) {
                Mp3Format.write(file, TagDocument(title = "不会写入"))
            }
            assertArrayEquals("original file must stay intact", original, file.readBytes())
            val leftovers = file.parentFile!!.listFiles()!!.filter { it.name.startsWith(".musictag-") }
            assertTrue("temporary file was left behind: $leftovers", leftovers.isEmpty())
        } finally {
            file.setWritable(true)
        }
    }

    @Test
    fun `v2_4 utf8 text payload helper is well formed`() {
        // Guards the test helper itself: frameV24 + syncsafe size parse back.
        val payload = Mp3TestSupport.textPayloadV24Utf8("测试")
        assertEquals(3, payload[0].toInt())
        assertEquals("测试", String(payload, 1, payload.size - 2, UTF8))
        assertNull(Mp3TestSupport.findFrameBlock(ByteArray(0), "TIT2"))
    }
}
