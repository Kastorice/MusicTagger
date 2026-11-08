package com.musictag.core.tag.mp3

import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import java.io.File
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
 * End to end tests for [Mp3Format]: reading every fixture, rewriting tags with
 * Chinese text and cover art, and verifying the result with ffprobe/ffmpeg.
 */
class Mp3FormatTest {

    private val lyrics = "故事的小黄花\n从出生那年就飘着\n童年的荡秋千\n随记忆一直晃到现在"

    @After
    fun tearDown() {
        Mp3TestSupport.cleanup()
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    @Test
    fun `reads every mp3 fixture without throwing`() {
        for (name in listOf("bare.mp3", "tagged_v24.mp3", "tagged_v23_cover.mp3", "id3v1.mp3")) {
            Mp3Format.read(Mp3TestSupport.fixture(name))
        }
    }

    @Test
    fun `empty id3v2 tag reads as empty document`() {
        // bare.mp3 only carries a TSSE (encoder) frame, which is not modeled.
        assertEquals(TagDocument(), Mp3Format.read(Mp3TestSupport.fixture("bare.mp3")))
    }

    @Test
    fun `reads id3v2_4 text frames`() {
        val document = Mp3Format.read(Mp3TestSupport.fixture("tagged_v24.mp3"))
        assertEquals("Old Title", document.title)
        assertEquals("Old Artist", document.artist)
        assertEquals("Old Album", document.album)
        assertEquals("2019", document.year)
        assertEquals("Pop", document.genre)
        assertNull(document.trackNumber)
        assertNull(document.artwork)
    }

    @Test
    fun `reads id3v2_3 cover artwork and text frames`() {
        val document = Mp3Format.read(Mp3TestSupport.fixture("tagged_v23_cover.mp3"))
        assertEquals("Sunny Day", document.title)
        assertEquals("Jay", document.artist)
        assertEquals("Ye Huimei", document.album)
        assertEquals("2003", document.year)
        assertEquals("3", document.trackNumber)
        assertEquals("Pop", document.genre)
        val artwork = document.artwork
        assertNotNull("cover art missing", artwork)
        assertEquals("image/jpeg", artwork!!.mimeType)
        assertArrayEquals(Mp3TestSupport.coverJpeg, artwork.bytes)
    }

    @Test
    fun `reads id3v1 fixture preferring the id3v2 tag`() {
        val document = Mp3Format.read(Mp3TestSupport.fixture("id3v1.mp3"))
        assertEquals("Legacy", document.title)
        assertEquals("Someone", document.artist)
        assertEquals("Old", document.album)
    }

    @Test
    fun `reads id3v1 trailer when there is no id3v2 tag`() {
        val audio = Mp3TestSupport.audioBytes(Mp3TestSupport.fixture("bare.mp3").readBytes())
        val id3v1 = ByteArray(128)
        "TAG".toByteArray(StandardCharsets.ISO_8859_1).copyInto(id3v1, 0)
        "Old School".toByteArray(StandardCharsets.ISO_8859_1).copyInto(id3v1, 3)
        "Legacy Artist".toByteArray(StandardCharsets.ISO_8859_1).copyInto(id3v1, 33)
        "Legacy Album".toByteArray(StandardCharsets.ISO_8859_1).copyInto(id3v1, 63)
        "1999".toByteArray(StandardCharsets.ISO_8859_1).copyInto(id3v1, 93)
        "A comment".toByteArray(StandardCharsets.ISO_8859_1).copyInto(id3v1, 97)
        id3v1[125] = 0
        id3v1[126] = 4 // ID3v1.1 track number
        id3v1[127] = 13 // Pop
        val file = Mp3TestSupport.tempFile("id3v1_fallback.mp3", Mp3TestSupport.concat(audio, id3v1))

        val document = Mp3Format.read(file)
        assertEquals("Old School", document.title)
        assertEquals("Legacy Artist", document.artist)
        assertEquals("Legacy Album", document.album)
        assertEquals("1999", document.year)
        assertEquals("A comment", document.comment)
        assertEquals("4", document.trackNumber)
        assertEquals("Pop", document.genre)
    }

    @Test
    fun `supports mp3 and aac but never other container formats`() {
        assertTrue(Mp3Format.supports(Mp3TestSupport.fixture("bare.mp3")))
        assertTrue(Mp3Format.supports(Mp3TestSupport.fixture("tagged_v24.mp3")))
        assertTrue(Mp3Format.supports(Mp3TestSupport.fixture("bare.aac")))
        assertFalse("FLAC must not be claimed", Mp3Format.supports(Mp3TestSupport.fixture("bare.flac")))
        assertFalse("OGG must not be claimed", Mp3Format.supports(Mp3TestSupport.fixture("bare.ogg")))
        assertFalse("Opus must not be claimed", Mp3Format.supports(Mp3TestSupport.fixture("bare.opus")))
        assertFalse("M4A must not be claimed", Mp3Format.supports(Mp3TestSupport.fixture("bare.m4a")))

        // Wrong extension must not win over foreign magic bytes.
        val flacBytes = Mp3TestSupport.fixture("bare.flac").readBytes()
        val disguisedFlac = Mp3TestSupport.tempFile("disguised.mp3", flacBytes)
        assertFalse(Mp3Format.supports(disguisedFlac))

        // Files without a useful extension are detected from the magic bytes.
        val mp3Bytes = Mp3TestSupport.fixture("bare.mp3").readBytes()
        val extensionless = Mp3TestSupport.tempFile("musicfile", mp3Bytes)
        assertTrue(Mp3Format.supports(extensionless))

        assertFalse(Mp3Format.supports(File(Mp3TestSupport.newTempDir(), "missing.mp3")))
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    @Test
    fun `writes chinese metadata and jpeg cover into bare mp3`() {
        val file = Mp3TestSupport.copyFixture("bare.mp3")
        val original = file.readBytes()
        val document = chineseDocument(withCover = true)

        Mp3Format.write(file, document)

        val written = file.readBytes()
        assertEquals("v2.3 must be used for untagged files", 3, Mp3TestSupport.majorVersion(written))
        assertEquals("exactly one front cover", 1, Mp3TestSupport.countFrames(written, "APIC"))
        assertArrayEquals("audio payload must be byte identical", Mp3TestSupport.audioBytes(original), Mp3TestSupport.audioBytes(written))

        // Unmodeled frames (TSSE written by the fixture generator) survive byte-for-byte.
        val tsseBefore = Mp3TestSupport.findFrameBlock(original, "TSSE")
        val tsseAfter = Mp3TestSupport.findFrameBlock(written, "TSSE")
        assertNotNull("TSSE frame lost", tsseAfter)
        assertArrayEquals(tsseBefore!!, tsseAfter!!)

        val reread = Mp3Format.read(file)
        assertEquals(document.normalized(), reread)
        assertArrayEquals(Mp3TestSupport.coverJpeg, reread.artwork!!.bytes)

        // External verification.
        val tags = Mp3TestSupport.formatTags(file)
        assertTrue("format_name mp3 missing:\n$tags", tags.contains("format_name=mp3"))
        for (expected in listOf(
            "TAG:title=晴天",
            "TAG:artist=周杰伦",
            "TAG:album=叶惠美",
            "TAG:album_artist=周杰伦",
            "TAG:date=2003",
            "TAG:track=3/10",
            "TAG:disc=1/1",
            "TAG:genre=流行",
        )) {
            assertTrue("ffprobe missing $expected:\n$tags", tags.contains(expected))
        }
        assertTrue("lyrics tag missing:\n$tags", tags.contains("故事的小黄花"))
        Mp3TestSupport.assertHasAttachedPicture(file, "mjpeg")
        Mp3TestSupport.assertDecodesCleanly(file)
        val duration = Mp3TestSupport.duration(file)
        assertTrue("duration changed after tagging: $duration", Math.abs(duration - 2.0) < 0.05)
    }

    @Test
    fun `writes chinese metadata into v2_4 file and keeps the version`() {
        val file = Mp3TestSupport.copyFixture("tagged_v24.mp3")
        val original = file.readBytes()
        assertEquals(4, Mp3TestSupport.majorVersion(original))
        val document = chineseDocument(withCover = true)

        Mp3Format.write(file, document)

        val written = file.readBytes()
        assertEquals("v2.4 input must stay v2.4", 4, Mp3TestSupport.majorVersion(written))
        assertEquals(1, Mp3TestSupport.countFrames(written, "APIC"))
        assertArrayEquals(Mp3TestSupport.audioBytes(original), Mp3TestSupport.audioBytes(written))
        assertEquals(document.normalized(), Mp3Format.read(file))

        val tags = Mp3TestSupport.formatTags(file)
        assertTrue(tags.contains("TAG:title=晴天"))
        assertTrue(tags.contains("TAG:artist=周杰伦"))
        assertTrue(tags.contains("TAG:date=2003"))
        Mp3TestSupport.assertHasAttachedPicture(file, "mjpeg")
        Mp3TestSupport.assertDecodesCleanly(file)
    }

    @Test
    fun `replaces the old v2_3 cover with a png cover`() {
        val file = Mp3TestSupport.copyFixture("tagged_v23_cover.mp3")
        val original = file.readBytes()

        Mp3Format.write(file, chineseDocument(withCover = true, pngCover = true))

        val written = file.readBytes()
        assertEquals(3, Mp3TestSupport.majorVersion(written))
        assertEquals("old cover must be replaced, not duplicated", 1, Mp3TestSupport.countFrames(written, "APIC"))
        assertArrayEquals(Mp3TestSupport.audioBytes(original), Mp3TestSupport.audioBytes(written))

        val artwork = Mp3Format.read(file).artwork!!
        assertEquals("image/png", artwork.mimeType)
        assertArrayEquals(Mp3TestSupport.coverPng, artwork.bytes)
        Mp3TestSupport.assertHasAttachedPicture(file, "png")
        Mp3TestSupport.assertDecodesCleanly(file)
    }

    @Test
    fun `null document clears every modeled field`() {
        val file = Mp3TestSupport.copyFixture("tagged_v23_cover.mp3")
        val original = file.readBytes()
        Mp3Format.write(file, chineseDocument(withCover = true))
        assertNotNull(Mp3Format.read(file).artwork)

        Mp3Format.write(file, TagDocument())

        val cleared = Mp3Format.read(file)
        assertEquals(TagDocument(), cleared)
        assertArrayEquals(Mp3TestSupport.audioBytes(original), Mp3TestSupport.audioBytes(file.readBytes()))

        val tags = Mp3TestSupport.formatTags(file)
        assertFalse("title was not removed:\n$tags", tags.contains("TAG:title"))
        assertFalse("artist was not removed:\n$tags", tags.contains("TAG:artist"))
        assertFalse("cover was not removed:\n${Mp3TestSupport.streamInfo(file)}",
            Mp3TestSupport.streamInfo(file).contains("codec_type=video"))
        Mp3TestSupport.assertDecodesCleanly(file)
    }

    @Test
    fun `write is idempotent`() {
        val file = Mp3TestSupport.copyFixture("bare.mp3")
        val document = chineseDocument(withCover = true)
        Mp3Format.write(file, document)
        val first = file.readBytes()
        Mp3Format.write(file, document)
        val second = file.readBytes()
        assertArrayEquals("second write changed the file again", first, second)
    }

    @Test
    fun `raw adts aac round trip prepends an id3v2 tag`() {
        val file = Mp3TestSupport.copyFixture("bare.aac")
        val original = file.readBytes()
        val baselineDuration = Mp3TestSupport.duration(file)
        val document = chineseDocument(withCover = false)

        Mp3Format.write(file, document)

        val written = file.readBytes()
        assertEquals(0x49, written[0].toInt() and 0xFF) // 'I'
        assertEquals(0x44, written[1].toInt() and 0xFF) // 'D'
        assertEquals(0x33, written[2].toInt() and 0xFF) // '3'
        assertEquals(3, Mp3TestSupport.majorVersion(written))
        assertArrayEquals("ADTS stream must stay byte identical", original, Mp3TestSupport.audioBytes(written))
        assertEquals(document.normalized(), Mp3Format.read(file))

        val tags = Mp3TestSupport.formatTags(file)
        assertTrue("raw aac must still be detected as aac:\n$tags", tags.contains("format_name=aac"))
        assertTrue(tags.contains("TAG:title=晴天"))
        assertTrue(tags.contains("TAG:artist=周杰伦"))
        Mp3TestSupport.assertDecodesCleanly(file)
        val duration = Mp3TestSupport.duration(file)
        assertEquals("aac duration changed", baselineDuration, duration, 0.02)
    }

    @Test
    fun `unmodeled TXXX frame is preserved byte for byte`() {
        val ffmpeg = Mp3TestSupport.requireFfmpeg()
        val source = Mp3TestSupport.copyFixture("bare.mp3")
        val file = File(source.parentFile, "custom.mp3")
        val result = Mp3TestSupport.run(
            ffmpeg, "-nostdin", "-y", "-v", "error",
            "-i", source.absolutePath, "-c", "copy",
            "-metadata", "custom_key=custom_value",
            file.absolutePath,
        )
        assertEquals("ffmpeg failed to create the TXXX fixture:\n${result.output}", 0, result.exitCode)

        val original = file.readBytes()
        val txxxBefore = Mp3TestSupport.findFrameBlock(original, "TXXX")
        assertNotNull("ffmpeg did not write a TXXX frame", txxxBefore)
        val versionBefore = Mp3TestSupport.majorVersion(original)
        val document = Mp3Format.read(file)
        assertNull("ffmpeg file should have no modeled tags", document.title)

        Mp3Format.write(file, TagDocument(title = "新标题", artist = "新歌手"))

        val written = file.readBytes()
        val txxxAfter = Mp3TestSupport.findFrameBlock(written, "TXXX")
        assertNotNull("TXXX frame was dropped", txxxAfter)
        assertArrayEquals("TXXX frame changed", txxxBefore, txxxAfter)
        assertEquals("tag major version changed", versionBefore, Mp3TestSupport.majorVersion(written))
        assertEquals("新标题", Mp3Format.read(file).title)

        val tags = Mp3TestSupport.formatTags(file)
        assertTrue("custom TXXX value lost:\n$tags", tags.contains("TAG:custom_key=custom_value"))
        Mp3TestSupport.assertDecodesCleanly(file)
    }

    @Test
    fun `id3v1 trailer is discarded on write`() {
        val file = Mp3TestSupport.copyFixture("id3v1.mp3")
        val original = file.readBytes()
        assertTrue("fixture must end with an ID3v1 tag", endsWithId3v1(original))
        assertEquals("Legacy", Mp3Format.read(file).title)

        Mp3Format.write(file, TagDocument(title = "新标题", artist = "新歌手"))

        val written = file.readBytes()
        assertFalse("ID3v1 trailer must be removed", endsWithId3v1(written))
        assertArrayEquals(Mp3TestSupport.audioBytes(original), Mp3TestSupport.audioBytes(written))
        val reread = Mp3Format.read(file)
        assertEquals("新标题", reread.title)
        assertEquals("新歌手", reread.artist)
    }

    @Test
    fun `read and write reject files that are not mpeg audio`() {
        val junk = Mp3TestSupport.tempFile("junk.mp3", ByteArray(2048) { 0x41 })
        val readError = assertThrows(TagException::class.java) { Mp3Format.read(junk) }
        assertTrue("message should be Chinese and mention the audio frames: ${readError.message}",
            readError.message!!.contains("音频"))
        assertThrows(TagException::class.java) {
            Mp3Format.write(junk, TagDocument(title = "x"))
        }

        val empty = Mp3TestSupport.tempFile("empty.mp3", ByteArray(0))
        assertThrows(TagException::class.java) { Mp3Format.read(empty) }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun chineseDocument(
        withCover: Boolean,
        pngCover: Boolean = false,
    ): TagDocument = TagDocument(
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        albumArtist = "周杰伦",
        year = "2003",
        trackNumber = "3/10",
        discNumber = "1/1",
        genre = "流行",
        comment = "第一张专辑的主打歌",
        lyrics = lyrics,
        artwork = when {
            !withCover -> null
            pngCover -> Artwork(Mp3TestSupport.coverPng, "image/png")
            else -> Artwork(Mp3TestSupport.coverJpeg, "image/jpeg")
        },
    )

    private fun endsWithId3v1(bytes: ByteArray): Boolean {
        if (bytes.size < 128) return false
        val offset = bytes.size - 128
        return bytes[offset] == 'T'.code.toByte() &&
            bytes[offset + 1] == 'A'.code.toByte() &&
            bytes[offset + 2] == 'G'.code.toByte()
    }
}
