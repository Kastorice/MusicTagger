package com.musictag.core.tag.ogg

import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/** Writing Ogg Vorbis / Ogg Opus tags: round trips, pagination, audio preservation and external verification. */
class OggFormatWriteTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val jpegBytes: ByteArray by lazy { OggTestSupport.fixtureBytes("cover.jpg") }

    private val chineseLyrics = "故事的小黄花，从出生那年就飘着。童年的荡秋千，随记忆一直晃到现在。"

    private fun coverArtwork(): Artwork = Artwork.fromBytes(jpegBytes, "封面")

    private fun sampleDocument(withArtwork: Boolean = true, lyrics: String? = chineseLyrics): TagDocument =
        TagDocument(
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            albumArtist = "周杰伦",
            year = "2003",
            trackNumber = "3/12",
            discNumber = "1",
            genre = "流行",
            comment = "备注：测试标签",
            lyrics = lyrics,
            artwork = if (withArtwork) coverArtwork() else null,
        )

    private fun copyFixture(name: String): File = OggTestSupport.fixture(name, temporaryFolder.root)

    /**
     * Verifies the written file: read-back equality, CRC/sequence/framing
     * integrity, byte-identical audio packets and preserved audio page metadata.
     */
    private fun assertRoundTrip(file: File, expected: TagDocument, audioSource: File) {
        val readBack = OggFormat.read(file)
        assertEquals("read back document of ${file.name}", expected.normalized(), readBack)

        val written = OggTestSupport.assertIntegrity(file)
        val theSource = OggTestSupport.assertIntegrity(audioSource)

        assertEquals(
            "serial number must be preserved",
            theSource.pages.first().serialNumber,
            written.pages.first().serialNumber,
        )
        assertEquals(
            "audio packet count of ${file.name}",
            theSource.audioPackets.size,
            written.audioPackets.size,
        )
        theSource.audioPackets.indices.forEach { index ->
            assertArrayEquals(
                "audio packet $index must stay byte identical",
                theSource.audioPackets[index],
                written.audioPackets[index],
            )
        }
        assertEquals("audio page count", theSource.audioPages.size, written.audioPages.size)
        theSource.audioPages.indices.forEach { index ->
            val before = theSource.audioPages[index]
            val after = written.audioPages[index]
            assertEquals("audio page $index granule position", before.granulePosition, after.granulePosition)
            assertEquals("audio page $index header flags", before.headerType, after.headerType)
            assertArrayEquals("audio page $index lacing", before.lacingValues, after.lacingValues)
            assertArrayEquals("audio page $index payload", before.payload, after.payload)        }
    }

    private fun ffmpegAvailable(): Boolean = OggTestSupport.ffmpegPath() != null

    private fun ffprobeAvailable(): Boolean = OggTestSupport.ffprobePath() != null

    private fun assertDuration(file: File, expectedSeconds: Double) {
        if (!ffprobeAvailable()) return
        val duration = OggTestSupport.probeDurationSeconds(file)
        assertEquals("duration of ${file.name}", expectedSeconds, duration, 0.1)
    }

    private fun assertDecodesAndDuration(file: File, expectedSeconds: Double) {
        assumeTrue("ffmpeg not available", ffmpegAvailable())
        OggTestSupport.assertDecodes(file)
        assertDuration(file, expectedSeconds)
    }

    // ---------------------------------------------------------------- vorbis

    @Test
    fun `writes chinese tags and jpeg cover into bare ogg vorbis`() {
        val file = copyFixture("bare.ogg")
        val audioSource = copyFixture("bare.ogg")
        val document = sampleDocument()

        OggFormat.write(file, document)

        assertEquals("file must still start with OggS", "OggS", String(file.readBytes(), 0, 4, Charsets.US_ASCII))
        assertRoundTrip(file, document, audioSource)

        val commentText = OggTestSupport.commentText(file)
        assertTrue("cover must be written as METADATA_BLOCK_PICTURE", commentText.contains("METADATA_BLOCK_PICTURE="))
        assertTrue("title comment", commentText.contains("TITLE=晴天"))
        assertTrue("track total comment", commentText.contains("TRACKTOTAL=12"))

        if (ffprobeAvailable()) {
            val tags = OggTestSupport.probeTags(file)
            assertEquals("晴天", tags["title"])
            assertEquals("周杰伦", tags["artist"])
            assertEquals("叶惠美", tags["album"])
            assertEquals("周杰伦", tags["album_artist"] ?: tags["albumartist"])
            assertEquals("2003", tags["date"])
            assertEquals("流行", tags["genre"])
            val streams = OggTestSupport.probeStreams(file)
            assertTrue(
                "ffprobe must expose the embedded front cover as an attached picture stream:\n$streams",
                streams.contains("attached_pic=1"),
            )
        }
        assertDecodesAndDuration(file, 2.0)
    }

    @Test
    fun `replaces existing vorbis tags and preserves unmodeled comments`() {
        val file = copyFixture("tagged.ogg")
        val audioSource = copyFixture("tagged.ogg")
        val originalComment = OggTestSupport.commentText(file)
        assertTrue("fixture should carry an encoder comment", originalComment.contains("encoder="))

        val document = sampleDocument()
        OggFormat.write(file, document)

        assertRoundTrip(file, document, audioSource)
        val commentText = OggTestSupport.commentText(file)
        assertTrue("unmodeled encoder comment must be preserved", commentText.contains("encoder="))
        assertFalse("old title must be gone", commentText.contains("Rice Field"))
        assertFalse("old artist must be gone", commentText.contains("Jay"))

        if (ffprobeAvailable()) {
            assertEquals("晴天", OggTestSupport.probeTags(file)["title"])
        }
        assertDecodesAndDuration(file, 2.0)
    }

    @Test
    fun `keeps identification and setup packets byte identical in vorbis`() {
        val file = copyFixture("tagged.ogg")
        val before = OggTestSupport.assertIntegrity(file)
        OggFormat.write(file, sampleDocument())
        val after = OggTestSupport.assertIntegrity(file)

        assertArrayEquals("identification header must not change", before.packets[0], after.packets[0])
        assertArrayEquals("setup header must not change", before.packets[2], after.packets[2])
    }

    // ------------------------------------------------------------------ opus

    @Test
    fun `writes chinese tags and jpeg cover into bare opus`() {
        val file = copyFixture("bare.opus")
        val audioSource = copyFixture("bare.opus")
        val document = sampleDocument()

        OggFormat.write(file, document)

        assertRoundTrip(file, document, audioSource)
        val commentText = OggTestSupport.commentText(file)
        assertTrue("OpusTags packet must stay in place", commentText.startsWith("OpusTags"))
        assertTrue("cover must be written as METADATA_BLOCK_PICTURE", commentText.contains("METADATA_BLOCK_PICTURE="))

        if (ffprobeAvailable()) {
            val tags = OggTestSupport.probeTags(file)
            assertEquals("晴天", tags["title"])
            assertEquals("周杰伦", tags["artist"])
            assertEquals("叶惠美", tags["album"])
            val streams = OggTestSupport.probeStreams(file)
            assertTrue(
                "ffprobe must expose the embedded front cover as an attached picture stream:\n$streams",
                streams.contains("attached_pic=1"),
            )
        }
        assertDecodesAndDuration(file, 2.0)
    }

    @Test
    fun `keeps opus identification packet byte identical`() {
        val file = copyFixture("bare.opus")
        val before = OggTestSupport.assertIntegrity(file)
        OggFormat.write(file, sampleDocument())
        val after = OggTestSupport.assertIntegrity(file)

        assertArrayEquals("OpusHead must not change", before.packets[0], after.packets[0])
        assertEquals(2, after.headerPacketCount)
    }

    // ------------------------------------------------------- multi-page path

    private fun longLyrics(): String {
        val builder = StringBuilder("副歌：")
        builder.append("故事的小黄花 从出生那年就飘着\n")
        val line = "The little yellow flower of the story has been drifting since the year I was born. "
        while (builder.length < 100 * 1024) {
            builder.append(line)
        }
        return builder.toString()
    }

    @Test
    fun `long lyrics force a multi page comment header in vorbis`() {
        val file = copyFixture("tagged.ogg")
        val audioSource = copyFixture("tagged.ogg")
        val before = OggTestSupport.assertIntegrity(file)
        val document = sampleDocument(lyrics = longLyrics())

        OggFormat.write(file, document)

        val after = OggTestSupport.assertIntegrity(file)
        assertTrue(
            "header should use more pages after adding large lyrics (before=${before.headerPageCount}, after=${after.headerPageCount})",
            after.headerPageCount > before.headerPageCount,
        )
        assertTrue("comment packet must span at least two pages", after.headerPageCount >= 3)

        assertEquals("lyrics must round trip", document.normalized(), OggFormat.read(file))
        // Audio pages keep granule positions and flags; only sequence numbers change.
        assertEquals(before.audioPages.size, after.audioPages.size)
        before.audioPages.indices.forEach { index ->
            assertEquals(before.audioPages[index].granulePosition, after.audioPages[index].granulePosition)
            assertEquals(before.audioPages[index].headerType, after.audioPages[index].headerType)
            assertArrayEquals(before.audioPages[index].payload, after.audioPages[index].payload)
        }
        assertDecodesAndDuration(file, 2.0)
    }

    @Test
    fun `long lyrics force a multi page comment header in opus`() {
        val file = copyFixture("bare.opus")
        val audioSource = copyFixture("bare.opus")
        val before = OggTestSupport.assertIntegrity(file)
        val document = sampleDocument(lyrics = longLyrics())

        OggFormat.write(file, document)

        val after = OggTestSupport.assertIntegrity(file)
        assertTrue(
            "comment header should use more pages (before=${before.headerPageCount}, after=${after.headerPageCount})",
            after.headerPageCount > before.headerPageCount,
        )
        assertTrue("OpusTags must span at least two pages", after.headerPageCount >= 3)
        assertEquals(document.normalized(), OggFormat.read(file))
        assertDecodesAndDuration(file, 2.0)
    }

    // ---------------------------------------------------- removal, idempotency

    @Test
    fun `removes all modeled tags and the cover`() {
        val file = copyFixture("tagged.ogg")
        OggFormat.write(file, sampleDocument())
        assertTrue(OggTestSupport.commentText(file).contains("METADATA_BLOCK_PICTURE="))

        OggFormat.write(file, TagDocument())

        assertTrue("empty document means every modeled field is gone", OggFormat.read(file).isEmpty)
        val commentText = OggTestSupport.commentText(file)
        assertFalse(commentText.contains("METADATA_BLOCK_PICTURE="))
        assertFalse(commentText.contains("TITLE="))
        assertFalse(commentText.contains("TRACKNUMBER="))
        assertTrue("unmodeled encoder comment survives", commentText.contains("encoder="))
        assertDecodesAndDuration(file, 2.0)
    }

    @Test
    fun `removes only the cover when artwork becomes null`() {
        val file = copyFixture("bare.ogg")
        val withCover = sampleDocument()
        OggFormat.write(file, withCover)
        val withoutCover = withCover.copy(artwork = null)

        OggFormat.write(file, withoutCover)

        val readBack = OggFormat.read(file)
        assertNull("cover must be removed", readBack.artwork)
        assertEquals(withoutCover.normalized(), readBack)
        assertFalse(OggTestSupport.commentText(file).contains("METADATA_BLOCK_PICTURE="))
    }

    @Test
    fun `writing the same document twice is byte identical`() {
        val file = copyFixture("bare.opus")
        OggFormat.write(file, sampleDocument())
        val first = file.readBytes()
        OggFormat.write(file, sampleDocument())
        val second = file.readBytes()
        assertArrayEquals("second write must be a no-op", first, second)
        OggTestSupport.assertIntegrity(file)
    }

    // --------------------------------------------------------- legacy coverart

    @Test
    fun `reads legacy coverart comments and converts them on write`() {
        val base = copyFixture("tagged.ogg")
        val legacyCommentPacket = CommentBlock(
            vendor = "legacy vendor",
            comments = listOf(
                "TITLE=遗留标题",
                "COVERART=" + Base64.getEncoder().encodeToString(jpegBytes),
                "COVERARTMIME=image/jpeg",
            ),
            trailingData = ByteArray(0),
        ).encode(OggCodec.VORBIS)
        val legacyFile = File(temporaryFolder.root, "legacy.ogg")
        OggTestSupport.rewriteComment(base, legacyFile, legacyCommentPacket)

        val document = OggFormat.read(legacyFile)
        assertEquals("遗留标题", document.title)
        val artwork = document.artwork
        assertNotNull("legacy COVERART must be read", artwork)
        assertEquals("image/jpeg", artwork!!.mimeType)
        assertArrayEquals("legacy cover bytes", jpegBytes, artwork.bytes)

        OggFormat.write(legacyFile, document)
        val commentText = OggTestSupport.commentText(legacyFile)
        assertTrue(
            "legacy cover should be converted to METADATA_BLOCK_PICTURE",
            commentText.contains("METADATA_BLOCK_PICTURE="),
        )
        assertFalse("legacy COVERART must not be duplicated", commentText.contains("COVERART="))
        assertFalse(commentText.contains("COVERARTMIME="))
        assertEquals(document.normalized(), OggFormat.read(legacyFile))
    }
}
