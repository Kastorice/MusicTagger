package com.musictag.core.tag.flac

import com.musictag.core.tag.Artwork
import com.musictag.core.tag.TagDocument
import com.musictag.core.tag.TagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlacFormatTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workDir: File

    @Before
    fun setUp() {
        workDir = temp.newFolder("work")
    }

    private fun copyFixture(name: String, targetName: String = name): File =
        FlacTestSupport.copyFixture(name, File(workDir, targetName))

    private fun chineseDocument(): TagDocument = TagDocument(
        title = "晴天",
        artist = "周杰伦",
        album = "叶惠美",
        albumArtist = "周杰伦",
        year = "2003",
        trackNumber = "3",
        discNumber = "1",
        genre = "流行",
        comment = "一段测试评论",
        lyrics = "第一行歌词\n第二行歌词",
        artwork = Artwork.fromBytes(FlacTestSupport.fixtureBytes("cover.jpg"), "封面描述"),
    )

    @Test
    fun readBareFlacReturnsEmptyDocument() {
        val file = copyFixture("bare.flac")
        val document = FlacFormat.read(file)
        assertTrue("a FLAC without text tags must read as an empty document", document.isEmpty)
        assertNull(document.artwork)
    }

    @Test
    fun readTaggedFlacParsesTextTagsAndPngCover() {
        val file = copyFixture("tagged.flac")
        val document = FlacFormat.read(file)
        assertEquals("Night Song", document.title)
        assertEquals("Jay", document.artist)
        assertEquals("November", document.album)
        assertEquals("2005", document.year)
        assertEquals("1", document.trackNumber)
        assertEquals("Pop", document.genre)
        assertNull(document.albumArtist)
        assertNull(document.discNumber)
        assertNull(document.lyrics)
        val artwork = document.artwork
        assertNotNull(artwork)
        assertEquals("image/png", artwork!!.mimeType)
        assertArrayEquals(FlacTestSupport.fixtureBytes("cover.png"), artwork.bytes)
    }

    @Test
    fun writeChineseValuesAndJpegCoverToBareFlac() {
        val file = copyFixture("bare.flac")
        val original = file.readBytes()
        val originalStructure = FlacTestSupport.parse(original)
        val document = chineseDocument()

        FlacFormat.write(file, document)

        val written = file.readBytes()
        assertEquals("fLaC", String(written, 0, 4, Charsets.US_ASCII))
        val structure = FlacTestSupport.parse(written)

        // STREAMINFO stays first and byte-identical.
        assertEquals(0, structure.blocks.first().type)
        assertEquals(34, structure.blocks.first().data.size)
        assertArrayEquals(originalStructure.blocks.first().data, structure.blocks.first().data)

        // One comment block and one picture block, inserted after STREAMINFO.
        assertEquals(listOf(0, 4, 6, 1), structure.blocks.map { it.type })
        assertEquals(1, structure.blocks.count { it.type == 4 })
        assertEquals(1, structure.blocks.count { it.type == 6 })

        // A fresh 4 KiB PADDING block is last.
        val padding = structure.blocks.last()
        assertEquals(1, padding.type)
        assertEquals(4096, padding.data.size)

        // Audio frames copied byte-for-byte.
        assertArrayEquals(FlacTestSupport.audioTail(original), FlacTestSupport.audioTail(written))

        // UTF-8 comment fields, unmodeled fields preserved, modeled fields appended afterwards.
        val comment = FlacTestSupport.parseComment(structure.blocks.first { it.type == 4 }.data)
        assertEquals("晴天", comment.value("TITLE"))
        assertEquals("周杰伦", comment.value("ARTIST"))
        assertEquals("叶惠美", comment.value("ALBUM"))
        assertEquals("周杰伦", comment.value("ALBUMARTIST"))
        assertEquals("2003", comment.value("DATE"))
        assertEquals("3", comment.value("TRACKNUMBER"))
        assertEquals("1", comment.value("DISCNUMBER"))
        assertEquals("流行", comment.value("GENRE"))
        assertEquals("一段测试评论", comment.value("COMMENT"))
        assertEquals("第一行歌词\n第二行歌词", comment.value("LYRICS"))
        assertEquals("encoder=Lavf62.12.100", comment.entries.first())
        assertTrue(comment.entries.contains("encoder=Lavf62.12.100"))

        // The PICTURE block carries picture type 3, the JPEG bytes and parsed dimensions.
        val picture = FlacTestSupport.parsePicture(structure.blocks.first { it.type == 6 }.data)
        assertEquals(3, picture.type)
        assertEquals("image/jpeg", picture.mimeType)
        assertEquals("封面描述", picture.description)
        assertArrayEquals(FlacTestSupport.fixtureBytes("cover.jpg"), picture.data)
        assertEquals(600, picture.width)
        assertEquals(600, picture.height)

        // Full read-back equality, including artwork bytes.
        val readBack = FlacFormat.read(file)
        assertEquals(document, readBack)
        assertEquals("封面描述", readBack.artwork!!.description)
    }

    @Test
    fun writingSameDocumentTwiceProducesIdenticalFile() {
        val file = copyFixture("bare.flac")
        val document = chineseDocument()
        FlacFormat.write(file, document)
        val first = file.readBytes()
        FlacFormat.write(file, document)
        assertArrayEquals("rewriting the same document must be byte-identical", first, file.readBytes())
    }

    @Test
    fun seekTableApplicationAndUnknownBlocksArePreserved() {
        val source = FlacTestSupport.fixtureBytes("bare.flac")
        val seekTable = FlacTestSupport.seekTable()
        val application = FlacTestSupport.applicationBlock()
        val unknown = FlacTestSupport.unknownBlockData()
        val augmented = FlacTestSupport.insertAfterStreamInfo(
            source,
            listOf(
                FlacTestSupport.Block(3, seekTable),
                FlacTestSupport.Block(2, application),
                FlacTestSupport.Block(100, unknown),
            ),
        )
        val file = File(workDir, "blocks.flac")
        file.writeBytes(augmented)

        FlacFormat.write(file, TagDocument(title = "保留测试", artist = "测试歌手"))

        val written = file.readBytes()
        val blocks = FlacTestSupport.parse(written).blocks
        assertArrayEquals("SEEKTABLE data must be untouched", seekTable, blocks.first { it.type == 3 }.data)
        assertArrayEquals(application, blocks.first { it.type == 2 }.data)
        assertArrayEquals(unknown, blocks.first { it.type == 100 }.data)
        val order = blocks.map { it.type }
        assertTrue(order.indexOf(3) < order.indexOf(2))
        assertTrue(order.indexOf(2) < order.indexOf(100))
        assertArrayEquals(FlacTestSupport.audioTail(augmented), FlacTestSupport.audioTail(written))
        assertEquals("保留测试", FlacFormat.read(file).title)
    }

    @Test
    fun removingAllFieldsAndCoverDropsPictureButKeepsUnknownComments() {
        val file = copyFixture("tagged.flac")
        val original = file.readBytes()
        FlacFormat.write(file, TagDocument())

        val readBack = FlacFormat.read(file)
        assertTrue("all modeled fields must be removed", readBack.isEmpty)
        assertNull(readBack.artwork)

        val written = file.readBytes()
        val blocks = FlacTestSupport.parse(written).blocks
        assertEquals(0, blocks.count { it.type == 6 })
        assertEquals(0, blocks.first().type)
        assertArrayEquals(
            FlacTestSupport.parse(original).blocks.first().data,
            blocks.first().data,
        )
        assertArrayEquals(FlacTestSupport.audioTail(original), FlacTestSupport.audioTail(written))
        val comment = FlacTestSupport.parseComment(blocks.first { it.type == 4 }.data)
        assertEquals(listOf("encoder=Lavf62.12.100"), comment.entries)
        assertNull(comment.value("TITLE"))
        assertNull(comment.value("ARTIST"))
    }

    @Test
    fun writingCoverReplacesStrayPictureBlocks() {
        val file = copyFixture("tagged.flac")
        val document = TagDocument(
            title = "新封面",
            artwork = Artwork.fromBytes(FlacTestSupport.fixtureBytes("cover.jpg")),
        )
        FlacFormat.write(file, document)
        val blocks = FlacTestSupport.parse(file.readBytes()).blocks
        assertEquals(1, blocks.count { it.type == 6 })
        val picture = FlacTestSupport.parsePicture(blocks.first { it.type == 6 }.data)
        assertEquals(3, picture.type)
        assertEquals("image/jpeg", picture.mimeType)
        assertArrayEquals(FlacTestSupport.fixtureBytes("cover.jpg"), picture.data)
        assertEquals("新封面", FlacFormat.read(file).title)
    }

    @Test
    fun unknownCommentKeysArePreservedByteForByte() {
        val vendor = "TestVendor 9.9"
        val preserved = listOf("CUSTOM_KEY=custom value", "X-NOTE=保留内容", "CUSTOM_KEY=second value")
        val commentBlock = FlacTestSupport.commentBytes(
            vendor,
            listOf("title=Old Title", "artist=Old Artist") + preserved,
        )
        val synthetic = FlacTestSupport.replaceBlock(
            FlacTestSupport.fixtureBytes("bare.flac"),
            type = 4,
            newData = commentBlock,
        )
        val file = File(workDir, "custom-comment.flac")
        file.writeBytes(synthetic)

        val before = FlacFormat.read(file)
        assertEquals("Old Title", before.title)
        assertEquals("Old Artist", before.artist)

        FlacFormat.write(file, TagDocument(title = "新标题", artist = "新歌手"))

        val comment = FlacTestSupport.parseComment(
            FlacTestSupport.parse(file.readBytes()).blocks.first { it.type == 4 }.data,
        )
        assertEquals(vendor, comment.vendor)
        assertEquals(preserved + listOf("TITLE=新标题", "ARTIST=新歌手"), comment.entries)
    }

    @Test
    fun missingCommentBlockIsTreatedAsEmptyAndCreatedOnWrite() {
        val source = FlacTestSupport.fixtureBytes("bare.flac")
        val structure = FlacTestSupport.parse(source)
        val withoutComment = FlacTestSupport.build(structure.blocks.filter { it.type != 4 }) +
            FlacTestSupport.audioTail(source)
        val file = File(workDir, "no-comment.flac")
        file.writeBytes(withoutComment)

        assertTrue(FlacFormat.read(file).isEmpty)

        FlacFormat.write(file, TagDocument(title = "标题", album = "专辑"))
        val blocks = FlacTestSupport.parse(file.readBytes()).blocks
        assertEquals(listOf(0, 4, 1), blocks.map { it.type })
        assertEquals("标题", FlacFormat.read(file).title)
        assertEquals("专辑", FlacFormat.read(file).album)
        assertEquals("MusicTagger", FlacTestSupport.parseComment(blocks[1].data).vendor)
    }

    @Test
    fun frontCoverPictureIsPreferredOverFirstPicture() {
        val jpeg = FlacTestSupport.fixtureBytes("cover.jpg")
        val png = FlacTestSupport.fixtureBytes("cover.png")
        val withPictures = FlacTestSupport.insertAfterStreamInfo(
            FlacTestSupport.fixtureBytes("bare.flac"),
            listOf(
                FlacTestSupport.Block(
                    6,
                    FlacTestSupport.buildPicture(0, "image/jpeg", "other", 600, 600, 24, 0, jpeg),
                ),
                FlacTestSupport.Block(
                    6,
                    FlacTestSupport.buildPicture(3, "image/png", "front", 600, 600, 24, 0, png),
                ),
            ),
        )
        val file = File(workDir, "two-pictures.flac")
        file.writeBytes(withPictures)

        val artwork = FlacFormat.read(file).artwork
        assertNotNull(artwork)
        assertEquals("image/png", artwork!!.mimeType)
        assertEquals("front", artwork.description)
        assertArrayEquals(png, artwork.bytes)
    }

    @Test
    fun ffprobeVerifiesWrittenTagsAndAttachedPicture() {
        FlacTestSupport.assumeFfprobe()
        val file = copyFixture("bare.flac")
        FlacFormat.write(file, chineseDocument())

        val tags = FlacTestSupport.formatTags(file)
        assertEquals("晴天", tags["title"])
        assertEquals("周杰伦", tags["artist"])
        assertEquals("叶惠美", tags["album"])
        assertEquals("2003", tags["date"])
        assertEquals("3", tags["track"])
        assertEquals("1", tags["disc"])
        assertEquals("流行", tags["genre"])
        assertEquals("一段测试评论", tags["comment"])
        assertEquals("周杰伦", tags["album_artist"] ?: tags["albumartist"])
        val pictures = FlacTestSupport.attachedPictureCodecs(file)
        assertEquals("exactly one attached picture stream", 1, pictures.size)
        assertTrue("unexpected cover codec: $pictures", pictures.first() in setOf("mjpeg", "jpeg", "png"))
    }

    @Test
    fun ffmpegDecodesWrittenFileAndDurationIsUnchanged() {
        FlacTestSupport.assumeFfprobe()
        FlacTestSupport.assumeFfmpeg()
        val file = copyFixture("bare.flac")
        val durationBefore = FlacTestSupport.durationSeconds(file)
        FlacFormat.write(file, chineseDocument())
        val durationAfter = FlacTestSupport.durationSeconds(file)
        assertEquals(durationBefore, durationAfter, 0.001)
        assertTrue(
            "ffmpeg must decode the rewritten FLAC without errors",
            FlacTestSupport.decodesWithoutErrors(file),
        )
    }

    @Test
    fun ffprobeSeesNoAttachedPictureAfterCoverRemoval() {
        FlacTestSupport.assumeFfprobe()
        val file = copyFixture("tagged.flac")
        assertEquals(1, FlacTestSupport.attachedPictureCodecs(file).size)
        FlacFormat.write(file, TagDocument())
        assertTrue(FlacTestSupport.attachedPictureCodecs(file).isEmpty())
        val tags = FlacTestSupport.formatTags(file)
        assertNull(tags["title"])
        assertNull(tags["artist"])
        assertEquals("Lavf62.12.100", tags["encoder"])
    }

    @Test
    fun corruptFlacFilesThrowChineseTagException() {
        val notFlac = File(workDir, "garbage.flac")
        notFlac.writeBytes("this is not a flac stream".toByteArray(Charsets.US_ASCII))
        assertChineseFlacMessage(
            assertThrows(TagException::class.java) { FlacFormat.read(notFlac) }.message,
        )

        val bare = FlacTestSupport.fixtureBytes("bare.flac")
        val truncated = File(workDir, "truncated.flac")
        truncated.writeBytes(bare.copyOfRange(0, 60))
        assertChineseFlacMessage(
            assertThrows(TagException::class.java) { FlacFormat.read(truncated) }.message,
        )

        val badType = File(workDir, "bad-type.flac")
        val invalidTypeBytes = bare.copyOf()
        invalidTypeBytes[42] = 0x7F.toByte() // metadata block type 127 does not exist
        badType.writeBytes(invalidTypeBytes)
        assertChineseFlacMessage(
            assertThrows(TagException::class.java) { FlacFormat.read(badType) }.message,
        )

        val hugeLength = File(workDir, "huge-length.flac")
        val hugeLengthBytes = bare.copyOf()
        hugeLengthBytes[43] = 0x00
        hugeLengthBytes[44] = 0xFF.toByte()
        hugeLengthBytes[45] = 0xFF.toByte() // declares a comment block larger than the file
        hugeLength.writeBytes(hugeLengthBytes)
        assertChineseFlacMessage(
            assertThrows(TagException::class.java) { FlacFormat.read(hugeLength) }.message,
        )

        // A failed write must not modify the (corrupt) original.
        val writeTarget = File(workDir, "corrupt-write.flac")
        writeTarget.writeBytes(bare.copyOfRange(0, 60))
        val before = writeTarget.readBytes()
        assertChineseFlacMessage(
            assertThrows(TagException::class.java) {
                FlacFormat.write(writeTarget, TagDocument(title = "无法写入"))
            }.message,
        )
        assertArrayEquals("corrupt file must stay untouched", before, writeTarget.readBytes())
    }

    @Test
    fun supportsDetectsFlacByExtensionAndMagic() {
        assertTrue(FlacFormat.supports(copyFixture("bare.flac")))
        assertFalse(FlacFormat.supports(copyFixture("bare.mp3")))
        val magicOnly = File(workDir, "stream.bin")
        magicOnly.writeBytes(FlacTestSupport.fixtureBytes("tagged.flac"))
        assertTrue("fLaC magic must be detected without a .flac extension", FlacFormat.supports(magicOnly))
        val empty = File(workDir, "empty.flac")
        empty.writeBytes(ByteArray(0))
        assertTrue("the .flac extension alone is enough", FlacFormat.supports(empty))
        assertEquals(setOf("flac"), FlacFormat.extensions)
        assertEquals("FLAC", FlacFormat.displayName)
    }

    private fun assertChineseFlacMessage(message: String?) {
        assertNotNull(message)
        assertTrue("message should mention FLAC: $message", message!!.contains("FLAC"))
        assertTrue("message should contain Chinese text: $message", message.any { it.code in 0x4E00..0x9FFF })
    }
}
