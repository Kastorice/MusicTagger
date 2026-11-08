package com.musictag.core.tag.mp4

import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class Mp4FormatReadTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reads text tags and jpeg cover from tagged m4a`() {
        val document = Mp4Format.read(Mp4TestSupport.fixture("tagged.m4a"))
        assertEquals("Qilixiang", document.title)
        assertEquals("Jay", document.artist)
        assertEquals("Qilixiang", document.album)
        assertEquals("2004", document.year)
        assertEquals("Pop", document.genre)
        assertEquals("1", document.trackNumber)
        assertNull(document.albumArtist)
        val artwork = document.artwork
        assertNotNull("cover art was not read", artwork)
        assertEquals("image/jpeg", artwork!!.mimeType)
        assertArrayEquals(
            "cover bytes differ from cover.jpg",
            Mp4TestSupport.bytesOf(Mp4TestSupport.fixture("cover.jpg")),
            artwork.bytes,
        )
    }

    @Test
    fun `bare m4a yields an empty document`() {
        assertEquals(TagDocument(), Mp4Format.read(Mp4TestSupport.fixture("bare.m4a")))
    }

    @Test
    fun `faststart m4a yields an empty document`() {
        assertEquals(TagDocument(), Mp4Format.read(Mp4TestSupport.fixture("faststart.m4a")))
    }

    @Test
    fun `supports m4a files and rejects other formats`() {
        for (name in listOf("bare.m4a", "tagged.m4a", "faststart.m4a")) {
            assertTrue("Mp4Format should support $name", Mp4Format.supports(Mp4TestSupport.fixture(name)))
        }
        for (name in listOf(
            "bare.mp3", "tagged_v24.mp3", "tagged_v23_cover.mp3", "id3v1.mp3",
            "bare.flac", "tagged.flac", "bare.ogg", "tagged.ogg", "bare.opus", "bare.aac",
        )) {
            assertFalse("Mp4Format must not claim $name", Mp4Format.supports(Mp4TestSupport.fixture(name)))
        }
        assertEquals(setOf("m4a", "m4b", "mp4"), Mp4Format.extensions)
        assertEquals("M4A / MP4", Mp4Format.displayName)
    }

    @Test
    fun `garbage input throws a chinese tag exception`() {
        val file = File(temporaryFolder.root, "garbage.m4a")
        file.writeBytes(ByteArray(64) { index -> (index * 7 + 1).toByte() })
        val error = assertThrows(TagException::class.java) { Mp4Format.read(file) }
        val message = error.message ?: ""
        assertTrue("message should be Chinese but was: $message", Mp4TestSupport.containsChinese(message))
    }

    @Test
    fun `truncated m4a throws a chinese tag exception`() {
        val source = Mp4TestSupport.bytesOf(Mp4TestSupport.fixture("bare.m4a"))
        val file = File(temporaryFolder.root, "truncated.m4a")
        file.writeBytes(source.copyOf(source.size / 2))
        val error = assertThrows(TagException::class.java) { Mp4Format.read(file) }
        assertTrue(
            "message should be Chinese but was: ${error.message}",
            Mp4TestSupport.containsChinese(error.message ?: ""),
        )
    }

    @Test
    fun `file without moov throws a chinese tag exception`() {
        val source = Mp4TestSupport.fixture("bare.m4a")
        val ftyp = Mp4TestSupport.boxOf(source, "ftyp")!!
        val file = File(temporaryFolder.root, "no-moov.m4a")
        Files.write(file.toPath(), Mp4TestSupport.boxBytes(source, ftyp))
        val error = assertThrows(TagException::class.java) { Mp4Format.read(file) }
        assertTrue(
            "message should be Chinese but was: ${error.message}",
            Mp4TestSupport.containsChinese(error.message ?: ""),
        )
    }

    @Test
    fun `missing file throws a chinese tag exception`() {
        val file = File(temporaryFolder.root, "does-not-exist.m4a")
        val error = assertThrows(TagException::class.java) { Mp4Format.read(file) }
        assertTrue(
            "message should be Chinese but was: ${error.message}",
            Mp4TestSupport.containsChinese(error.message ?: ""),
        )
    }
}
