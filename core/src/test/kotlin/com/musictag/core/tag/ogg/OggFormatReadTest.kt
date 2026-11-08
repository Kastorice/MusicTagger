package com.musictag.core.tag.ogg

import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Reading, detection and structural validation of Ogg Vorbis / Ogg Opus files. */
class OggFormatReadTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun copyFixture(name: String): File = OggTestSupport.fixture(name, temporaryFolder.root)

    private fun assertChineseMessage(throwable: TagException) {
        val message = throwable.message
        assertNotNull("TagException must carry a message", message)
        assertTrue(
            "TagException message must be Chinese and user facing, was: $message",
            message!!.any { it in '\u4e00'..'\u9fff' },
        )
    }

    @Test
    fun `reads vorbis comments`() {
        val file = copyFixture("tagged.ogg")
        val document = OggFormat.read(file)

        assertEquals("Rice Field", document.title)
        assertEquals("Jay", document.artist)
        assertEquals("Capricorn", document.album)
        assertEquals("2008", document.year)
        assertNull("fixture has no cover", document.artwork)
        assertFalse(document.isEmpty)
    }

    @Test
    fun `reads bare files as empty documents`() {
        val bareVorbis = copyFixture("bare.ogg")
        val bareOpus = copyFixture("bare.opus")

        assertTrue("bare.ogg should have no modeled tags", OggFormat.read(bareVorbis).isEmpty)
        assertTrue("bare.opus should have no modeled tags", OggFormat.read(bareOpus).isEmpty)
        assertEquals(TagDocument(), OggFormat.read(bareOpus))
    }

    @Test
    fun `supports ogg extensions and magic bytes`() {
        assertTrue("ogg extension", OggFormat.supports(copyFixture("bare.ogg")))
        assertTrue("opus extension", OggFormat.supports(copyFixture("bare.opus")))

        val disguised = File(temporaryFolder.root, "audio.bin")
        disguised.writeBytes(OggTestSupport.fixtureBytes("bare.ogg"))
        assertTrue("OggS magic must be detected for unknown extensions", OggFormat.supports(disguised))

        assertFalse("mp3 is not an ogg file", OggFormat.supports(copyFixture("bare.mp3")))
        assertFalse("flac is not an ogg file", OggFormat.supports(copyFixture("bare.flac")))
        assertFalse("m4a is not an ogg file", OggFormat.supports(copyFixture("bare.m4a")))

        val oga = OggTestSupport.fixture("bare.ogg", temporaryFolder.root)
        val renamed = File(temporaryFolder.root, "renamed.oga")
        assertTrue(oga.renameTo(renamed))
        assertTrue("oga extension", OggFormat.supports(renamed))
    }

    @Test
    fun `rejects a file that is not ogg`() {
        val file = File(temporaryFolder.root, "notes.ogg")
        file.writeText("this is definitely not an ogg stream")

        try {
            OggFormat.read(file)
            fail("reading a non-Ogg file must throw TagException")
        } catch (e: TagException) {
            assertChineseMessage(e)
        }
    }

    @Test
    fun `rejects truncated header with chinese message`() {
        val complete = OggTestSupport.fixtureBytes("tagged.ogg")
        val truncated = File(temporaryFolder.root, "truncated.ogg")
        truncated.writeBytes(complete.copyOfRange(0, 100))

        try {
            OggFormat.read(truncated)
            fail("reading a truncated Ogg file must throw TagException")
        } catch (e: TagException) {
            assertChineseMessage(e)
        }
    }

    @Test
    fun `rejects corrupted header checksum`() {
        val bytes = OggTestSupport.fixtureBytes("tagged.ogg")
        // Flip a byte inside the comment packet of the second page (page one starts at 58).
        bytes[80] = (bytes[80].toInt() xor 0xFF).toByte()
        val corrupted = File(temporaryFolder.root, "corrupt.ogg")
        corrupted.writeBytes(bytes)

        try {
            OggFormat.read(corrupted)
            fail("a corrupted header page must be rejected")
        } catch (e: TagException) {
            assertChineseMessage(e)
        }
    }

    @Test
    fun `rejects chained ogg streams on write and keeps the file intact`() {
        val chainedBytes = OggTestSupport.fixtureBytes("bare.ogg") + OggTestSupport.fixtureBytes("tagged.ogg")
        val chained = File(temporaryFolder.root, "chained.ogg")
        chained.writeBytes(chainedBytes)

        try {
            OggFormat.write(chained, TagDocument(title = "新标题"))
            fail("chained streams are not supported and must be rejected")
        } catch (e: TagException) {
            assertChineseMessage(e)
            assertTrue(
                "message should explain that chained/multiplexed streams are unsupported: ${e.message}",
                e.message!!.contains("串联") || e.message!!.contains("复用"),
            )
        }
        assertArrayEqualsMessage(
            "failed write must leave the original file untouched",
            chainedBytes,
            chained.readBytes(),
        )
    }

    @Test
    fun `integrity of untouched fixtures is verifiable`() {
        val vorbis = OggTestSupport.assertIntegrity(copyFixture("tagged.ogg"))
        assertEquals(2, vorbis.headerPageCount)
        assertEquals(3, vorbis.headerPacketCount)
        assertTrue("fixture must have audio packets", vorbis.audioPackets.isNotEmpty())

        val opus = OggTestSupport.assertIntegrity(copyFixture("bare.opus"))
        assertEquals(2, opus.headerPageCount)
        assertEquals(2, opus.headerPacketCount)
        assertTrue("fixture must have audio packets", opus.audioPackets.isNotEmpty())
    }

    private fun assertArrayEqualsMessage(message: String, expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) fail(message)
    }
}
