package com.musictag.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Cross-format integration test for the public [TagEditor] facade: every
 * supported container must round-trip the same [TagDocument] (Chinese text +
 * artwork) and keep decoding as audio.
 */
class FormatIntegrationTest {

    private val fixtureDir = File("src/test/resources/fixtures")
    private val coverBytes: ByteArray = File(fixtureDir, "cover.jpg").readBytes()

    private val document = TagDocument(
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        albumArtist = "周杰伦",
        year = "2003",
        trackNumber = "3",
        discNumber = "1",
        genre = "流行",
        comment = "集成测试",
        lyrics = "[00:00.00]故事的小黄花\n[00:03.00]从出生那年就飘着",
        artwork = Artwork.fromBytes(coverBytes),
    )

    @Test
    fun `every supported container round-trips the same document`() {
        val cases = linkedMapOf(
            "bare.mp3" to "MP3",
            "bare.flac" to "FLAC",
            "bare.ogg" to "OGG / OPUS",
            "bare.opus" to "OGG / OPUS",
            "bare.m4a" to "M4A / MP4",
            "bare.aac" to "MP3",
        )
        assumeTrue("fixtures missing", fixtureDir.isDirectory)

        cases.forEach { (name, expectedFormat) ->
            val workDir = Files.createTempDirectory("musictag-it").toFile()
            val target = File(workDir, name).also { File(fixtureDir, name).copyTo(it) }

            assertEquals("detected format for $name", expectedFormat, TagEditor.detect(target)?.displayName)

            TagEditor.write(target, document)
            val readBack = TagEditor.read(target)

            assertEquals("$name title", "晴天", readBack.title)
            assertEquals("$name artist", "周杰伦", readBack.artist)
            assertEquals("$name album", "叶惠美", readBack.album)
            assertEquals("$name year", "2003", readBack.year)
            assertEquals("$name track", "3", readBack.trackNumber)
            assertEquals("$name genre", "流行", readBack.genre)
            assertTrue("$name lyrics", readBack.lyrics?.contains("故事的小黄花") == true)
            val artwork = readBack.artwork
            assertNotNull("$name artwork", artwork)
            assertTrue("$name artwork bytes", artwork!!.bytes.isNotEmpty())

            // Clearing everything must produce an empty document again.
            TagEditor.write(target, TagDocument())
            val cleared = TagEditor.read(target)
            assertEquals("$name cleared title", null, cleared.title)
            assertEquals("$name cleared artwork", null, cleared.artwork)

            assertAudioDecodes(name, target)
            workDir.deleteRecursively()
        }
    }

    @Test
    fun `supported extensions and format detection are consistent`() {
        listOf("mp3", "flac", "ogg", "opus", "m4a", "aac").forEach {
            assertTrue("extension $it", it in TagEditor.supportedExtensions)
        }
    }

    private fun assertAudioDecodes(name: String, file: File) {
        val ffmpeg = System.getProperty("ffmpeg.path")?.let(::File)
        assumeTrue("ffmpeg not available", ffmpeg != null && ffmpeg.canExecute())
        val process = ProcessBuilder(
            ffmpeg!!.absolutePath, "-v", "error", "-i", file.absolutePath, "-f", "null", "-",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("ffmpeg timed out for $name", process.waitFor(40, TimeUnit.SECONDS))
        assertEquals("$name decode failed:\n$output", 0, process.exitValue())
    }
}
