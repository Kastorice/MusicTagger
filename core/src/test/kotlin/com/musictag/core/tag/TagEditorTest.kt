package com.musictag.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity test for the public tag library surface (exists before format implementations land). */
class TagEditorTest {

    @Test
    fun `supported extensions cover the requested formats`() {
        val extensions = TagEditor.supportedExtensions
        listOf("mp3", "flac", "ogg", "opus", "m4a", "aac").forEach {
            assertTrue("missing extension: $it", it in extensions)
        }
    }

    @Test
    fun `tag document normalizes blank strings to null`() {
        val doc = TagDocument(title = "  ", artist = "周杰伦", lyrics = "").normalized()
        assertEquals(null, doc.title)
        assertEquals("周杰伦", doc.artist)
        assertEquals(null, doc.lyrics)
        assertTrue(doc.artwork == null)
    }

    @Test
    fun `artwork sniffs jpeg and png magic`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1)
        assertEquals("image/jpeg", Artwork.sniffMimeType(jpeg))
        assertEquals("image/png", Artwork.sniffMimeType(png))
    }
}
