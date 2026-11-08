package com.musictag.core.tag.mp4

import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class Mp4FormatWriteTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val layouts = listOf("bare.m4a", "tagged.m4a", "faststart.m4a")

    @Test
    fun `writes chinese tags and png cover in every layout`() {
        for (fixtureName in layouts) {
            val target = Mp4TestSupport.copyFixture(fixtureName, temporaryFolder.root)
            val document = Mp4TestSupport.sampleDocument().copy(artwork = Mp4TestSupport.pngCover())
            Mp4Format.write(target, document)

            assertEquals("read back mismatch for $fixtureName", document, Mp4Format.read(target))

            val tags = Mp4TestSupport.probeTags(target)
            assertEquals("title ($fixtureName)", document.title, tags["title"])
            assertEquals("artist ($fixtureName)", document.artist, tags["artist"])
            assertEquals("album ($fixtureName)", document.album, tags["album"])
            assertEquals("date ($fixtureName)", document.year, tags["date"])
            assertEquals("track ($fixtureName)", document.trackNumber, tags["track"])
            assertEquals("genre ($fixtureName)", document.genre, tags["genre"])
            assertTrue(
                "disc ($fixtureName) was ${tags["disc"]}",
                tags["disc"]?.startsWith("1") == true,
            )

            val videoStreams = Mp4TestSupport.probeStreams(target).filter { it.second == "video" }
            assertEquals("exactly one attached picture expected ($fixtureName)", 1, videoStreams.size)
            assertEquals("attached picture codec ($fixtureName)", "png", videoStreams[0].first)

            assertEquals("duration changed ($fixtureName)", 2.0, Mp4TestSupport.durationSeconds(target), 0.1)
            Mp4TestSupport.assertAudioDecodes(target)
        }
    }

    @Test
    fun `write preserves ftyp and mdat bytes and top level box order`() {
        for (fixtureName in layouts) {
            val original = Mp4TestSupport.fixture(fixtureName)
            val target = Mp4TestSupport.copyFixture(fixtureName, temporaryFolder.root)
            val originalBoxes = Mp4TestSupport.topLevelBoxes(original)
            val originalFtyp = Mp4TestSupport.boxBytes(original, originalBoxes.first { it.type == "ftyp" })
            val originalMdat = Mp4TestSupport.boxBytes(original, originalBoxes.first { it.type == "mdat" })

            Mp4Format.write(target, Mp4TestSupport.sampleDocument().copy(artwork = Mp4TestSupport.pngCover()))

            val newBoxes = Mp4TestSupport.topLevelBoxes(target)
            assertEquals(
                "top level box order changed ($fixtureName)",
                originalBoxes.map { it.type },
                newBoxes.map { it.type },
            )
            assertArrayEquals(
                "ftyp changed ($fixtureName)",
                originalFtyp,
                Mp4TestSupport.boxBytes(target, newBoxes.first { it.type == "ftyp" }),
            )
            assertArrayEquals(
                "mdat changed ($fixtureName)",
                originalMdat,
                Mp4TestSupport.boxBytes(target, newBoxes.first { it.type == "mdat" }),
            )
        }
    }

    @Test
    fun `writing the same document twice is byte identical`() {
        for (fixtureName in layouts) {
            val target = Mp4TestSupport.copyFixture(fixtureName, temporaryFolder.root)
            val document = Mp4TestSupport.sampleDocument().copy(artwork = Mp4TestSupport.jpegCover())
            Mp4Format.write(target, document)
            val firstPass = Mp4TestSupport.bytesOf(target)
            Mp4Format.write(target, document)
            val secondPass = Mp4TestSupport.bytesOf(target)
            assertArrayEquals("second write was not idempotent ($fixtureName)", firstPass, secondPass)
        }
    }

    @Test
    fun `empty document removes all fields and cover`() {
        val target = Mp4TestSupport.copyFixture("tagged.m4a", temporaryFolder.root)
        Mp4Format.write(target, Mp4TestSupport.sampleDocument().copy(artwork = Mp4TestSupport.pngCover()))
        Mp4Format.write(target, TagDocument())

        assertEquals(TagDocument(), Mp4Format.read(target))
        val tags = Mp4TestSupport.probeTags(target)
        for (key in listOf("title", "artist", "album", "album_artist", "date", "track", "disc", "genre", "comment", "lyrics")) {
            assertNull("ffprobe still reports $key=${tags[key]}", tags[key])
        }
        assertTrue(
            "attached picture should be removed",
            Mp4TestSupport.probeStreams(target).none { it.second == "video" },
        )
        Mp4TestSupport.assertAudioDecodes(target)
    }

    @Test
    fun `large lyrics in faststart layout keeps chunk offsets correct`() {
        val target = Mp4TestSupport.copyFixture("faststart.m4a", temporaryFolder.root)
        val originalMoovSize = Mp4TestSupport.boxOf(target, "moov")!!.size
        val packetsBefore = Mp4TestSupport.audioPacketCount(target)
        assertEquals(
            "fixture sanity: faststart stco should point at the mdat payload",
            Mp4TestSupport.boxOf(target, "mdat")!!.payloadOffset,
            Mp4TestSupport.firstStcoEntry(target),
        )

        val document = Mp4TestSupport.sampleDocument().copy(
            lyrics = Mp4TestSupport.largeLyrics(),
            artwork = Mp4TestSupport.pngCover(),
        )
        Mp4Format.write(target, document)

        assertEquals("large lyrics read back mismatch", document, Mp4Format.read(target))
        assertTrue(
            "moov should have grown: ${Mp4TestSupport.boxOf(target, "moov")!!.size}",
            Mp4TestSupport.boxOf(target, "moov")!!.size > originalMoovSize,
        )
        assertEquals(
            "stco must point at the shifted mdat payload",
            Mp4TestSupport.boxOf(target, "mdat")!!.payloadOffset,
            Mp4TestSupport.firstStcoEntry(target),
        )
        assertEquals("packet count changed", packetsBefore, Mp4TestSupport.audioPacketCount(target))
        assertEquals("duration changed", 2.0, Mp4TestSupport.durationSeconds(target), 0.1)
        Mp4TestSupport.assertAudioDecodes(target)
        assertEquals(document.title, Mp4TestSupport.probeTags(target)["title"])
    }

    @Test
    fun `large lyrics in moov after mdat layout leaves chunk offsets untouched`() {
        val target = Mp4TestSupport.copyFixture("bare.m4a", temporaryFolder.root)
        val originalStco = Mp4TestSupport.firstStcoEntry(target)
        assertEquals(
            "fixture sanity: bare.m4a stco should point at the mdat payload",
            Mp4TestSupport.boxOf(target, "mdat")!!.payloadOffset,
            originalStco,
        )
        val packetsBefore = Mp4TestSupport.audioPacketCount(target)

        val document = Mp4TestSupport.sampleDocument().copy(
            lyrics = Mp4TestSupport.largeLyrics(),
            artwork = Mp4TestSupport.pngCover(),
        )
        Mp4Format.write(target, document)

        assertEquals("large lyrics read back mismatch", document, Mp4Format.read(target))
        assertEquals(
            "chunk offsets must stay unchanged when moov follows mdat",
            originalStco,
            Mp4TestSupport.firstStcoEntry(target),
        )
        assertEquals("packet count changed", packetsBefore, Mp4TestSupport.audioPacketCount(target))
        assertEquals("duration changed", 2.0, Mp4TestSupport.durationSeconds(target), 0.1)
        Mp4TestSupport.assertAudioDecodes(target)
    }

    @Test
    fun `unknown ilst atoms are preserved`() {
        val target = Mp4TestSupport.copyFixture("tagged.m4a", temporaryFolder.root)
        assertEquals("Lavf62.12.100", Mp4TestSupport.probeTags(target)["encoder"])
        Mp4Format.write(target, Mp4TestSupport.sampleDocument().copy(artwork = Mp4TestSupport.pngCover()))
        assertEquals(
            "unknown ©too item must survive a rewrite",
            "Lavf62.12.100",
            Mp4TestSupport.probeTags(target)["encoder"],
        )
    }

    @Test
    fun `cover art is replaced with a single png picture`() {
        val target = Mp4TestSupport.copyFixture("tagged.m4a", temporaryFolder.root)
        Mp4Format.write(target, TagDocument(title = "封面測試", artwork = Mp4TestSupport.pngCover()))

        val document = Mp4Format.read(target)
        assertEquals("封面測試", document.title)
        val artwork = document.artwork
        assertNotNull("cover art was not written", artwork)
        assertEquals("image/png", artwork!!.mimeType)
        assertArrayEquals(
            "cover bytes differ from cover.png",
            Mp4TestSupport.bytesOf(Mp4TestSupport.fixture("cover.png")),
            artwork.bytes,
        )
        val videoStreams = Mp4TestSupport.probeStreams(target).filter { it.second == "video" }
        assertEquals("old JPEG cover should be gone", 1, videoStreams.size)
        assertEquals("png", videoStreams[0].first)
        Mp4TestSupport.assertAudioDecodes(target)
    }

    @Test
    fun `second write replaces stale fields and cover`() {
        val target = Mp4TestSupport.copyFixture("bare.m4a", temporaryFolder.root)
        Mp4Format.write(target, Mp4TestSupport.sampleDocument().copy(artwork = Mp4TestSupport.jpegCover()))

        val replacement = TagDocument(
            title = "New Title",
            artist = "New Artist",
            artwork = Mp4TestSupport.pngCover(),
        )
        Mp4Format.write(target, replacement)

        assertEquals(replacement, Mp4Format.read(target))
        val tags = Mp4TestSupport.probeTags(target)
        assertEquals("New Title", tags["title"])
        assertEquals("New Artist", tags["artist"])
        assertNull(tags["album"])
        assertNull(tags["date"])
        assertNull(tags["track"])
        assertNull(tags["genre"])
        val videoStreams = Mp4TestSupport.probeStreams(target).filter { it.second == "video" }
        assertEquals(1, videoStreams.size)
        assertEquals("png", videoStreams[0].first)
        Mp4TestSupport.assertAudioDecodes(target)
    }

    @Test
    fun `creates udta meta and ilst when the metadata container is missing`() {
        // bare.m4a has moov as its last box, so removing udta keeps all chunk offsets valid.
        val target = File(temporaryFolder.root, "no-udta.m4a")
        Mp4TestSupport.copyWithoutBoxChain(Mp4TestSupport.fixture("bare.m4a"), target, listOf("udta"))
        assertEquals("synthetic fixture must have no metadata", TagDocument(), Mp4Format.read(target))

        // Writing an empty document must not create a pointless metadata container.
        val strippedBytes = Mp4TestSupport.bytesOf(target)
        Mp4Format.write(target, TagDocument())
        assertArrayEquals(
            "empty write should leave a metadata-free file untouched",
            strippedBytes,
            Mp4TestSupport.bytesOf(target),
        )

        val document = Mp4TestSupport.sampleDocument().copy(artwork = Mp4TestSupport.pngCover())
        Mp4Format.write(target, document)

        assertEquals(document, Mp4Format.read(target))
        assertTrue("mdir metadata handler was not created", Mp4TestSupport.moovContainsAscii(target, "mdir"))
        assertTrue("hdlr box was not created", Mp4TestSupport.moovContainsAscii(target, "hdlr"))
        assertEquals(
            "moov after mdat: chunk offsets must stay unchanged",
            44L,
            Mp4TestSupport.firstStcoEntry(target),
        )
        assertEquals(
            listOf("ftyp", "free", "mdat", "moov"),
            Mp4TestSupport.topLevelBoxes(target).map { it.type },
        )
        assertEquals(document.title, Mp4TestSupport.probeTags(target)["title"])
        assertEquals(2.0, Mp4TestSupport.durationSeconds(target), 0.1)
        Mp4TestSupport.assertAudioDecodes(target)
    }

    @Test
    fun `creates meta when udta exists without meta`() {
        val target = File(temporaryFolder.root, "no-meta.m4a")
        Mp4TestSupport.copyWithoutBoxChain(
            Mp4TestSupport.fixture("bare.m4a"),
            target,
            listOf("udta", "meta"),
        )
        assertEquals(TagDocument(), Mp4Format.read(target))

        val document = Mp4TestSupport.sampleDocument().copy(artwork = Mp4TestSupport.jpegCover())
        Mp4Format.write(target, document)

        assertEquals(document, Mp4Format.read(target))
        assertTrue("mdir metadata handler was not created", Mp4TestSupport.moovContainsAscii(target, "mdir"))
        assertEquals(document.title, Mp4TestSupport.probeTags(target)["title"])
        val videoStreams = Mp4TestSupport.probeStreams(target).filter { it.second == "video" }
        assertEquals(1, videoStreams.size)
        assertEquals("mjpeg", videoStreams[0].first)
        Mp4TestSupport.assertAudioDecodes(target)
    }

    @Test
    fun `creates ilst inside an existing meta box`() {
        val target = File(temporaryFolder.root, "no-ilst.m4a")
        Mp4TestSupport.copyWithoutBoxChain(
            Mp4TestSupport.fixture("bare.m4a"),
            target,
            listOf("udta", "meta", "ilst"),
        )
        assertEquals(TagDocument(), Mp4Format.read(target))

        val document = Mp4TestSupport.sampleDocument().copy(artwork = Mp4TestSupport.pngCover())
        Mp4Format.write(target, document)

        assertEquals(document, Mp4Format.read(target))
        assertEquals(document.title, Mp4TestSupport.probeTags(target)["title"])
        // The existing metadata handler must be reused instead of duplicated. (Note: the
        // moov also contains the audio track's mdia/hdlr, so look for the 'mdir' signature.)
        val moovBytes = Mp4TestSupport.boxBytes(target, Mp4TestSupport.boxOf(target, "moov")!!)
        assertEquals("expected exactly one metadata handler", 1, countAscii(moovBytes, "mdir"))
        Mp4TestSupport.assertAudioDecodes(target)
    }

    private fun countAscii(haystack: ByteArray, text: String): Int {
        val needle = text.toByteArray(Charsets.ISO_8859_1)
        var count = 0
        var index = 0
        while (index <= haystack.size - needle.size) {
            var match = true
            for (j in needle.indices) {
                if (haystack[index + j] != needle[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                count += 1
                index += needle.size
            } else {
                index += 1
            }
        }
        return count
    }

    @Test
    fun `write to a file without moov throws and leaves the file intact`() {
        val source = Mp4TestSupport.fixture("bare.m4a")
        val ftypBytes = Mp4TestSupport.boxBytes(source, Mp4TestSupport.boxOf(source, "ftyp")!!)
        val target = File(temporaryFolder.root, "no-moov.m4a")
        Files.write(target.toPath(), ftypBytes)

        val error = assertThrows(TagException::class.java) {
            Mp4Format.write(target, Mp4TestSupport.sampleDocument())
        }
        assertTrue(
            "message should be Chinese but was: ${error.message}",
            Mp4TestSupport.containsChinese(error.message ?: ""),
        )
        assertArrayEquals("failed write must not modify the original", ftypBytes, Mp4TestSupport.bytesOf(target))
    }
}
