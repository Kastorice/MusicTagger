package com.musictag.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    @Test
    fun detectsLrcTimeTags() {
        assertTrue(Lrc.isLrc("[00:12.34]hello"))
        assertTrue(Lrc.isLrc("[00:12]hello"))
        assertTrue(Lrc.isLrc("[00:12.345]hello"))
        assertTrue(Lrc.isLrc("[1:02:34]hello"))
        assertTrue(Lrc.isLrc("[ti:Title]\n[00:01.00]line"))
        assertFalse(Lrc.isLrc("just plain lyrics\nno timing here"))
        assertFalse(Lrc.isLrc("[ti:Title]\n[ar:Artist]"))
        assertFalse(Lrc.isLrc(null))
        assertFalse(Lrc.isLrc("  "))
    }

    @Test
    fun plainTextStripsTagsAndMetadata() {
        val lrc = """
            [ti:测试歌曲]
            [ar:歌手]
            [al:专辑]
            [by:someone]
            [offset:0]

            [00:01.00]第一行
            [00:02.50][00:06.00]副歌
            [00:03.00]
            [00:04.00]最后一行
        """.trimIndent()

        assertEquals("第一行\n副歌\n最后一行", Lrc.plainText(lrc))
    }

    @Test
    fun plainTextHandlesBlankInput() {
        assertEquals("", Lrc.plainText(null))
        assertEquals("", Lrc.plainText("   "))
    }

    @Test
    fun mergeTranslationAppendsTranslatedLines() {
        val original = "[00:01.00]Hello\n[00:02.00]World\n[00:03.00]No translation"
        val translation = "[00:01.00]你好\n[00:02.00]世界"
        val merged = Lrc.mergeTranslation(original, translation)

        assertEquals(
            "[00:01.00]Hello\n你好\n[00:02.00]World\n世界\n[00:03.00]No translation",
            merged,
        )
        assertTrue(Lrc.isLrc(merged))
    }

    @Test
    fun mergeTranslationMatchesByTimeValueNotTagText() {
        val merged = Lrc.mergeTranslation("[00:09.48]A\n[00:10.00]B", "[00:09.480]译")
        assertEquals("[00:09.48]A\n译\n[00:10.00]B", merged)
    }

    @Test
    fun mergeTranslationSkipsTranslationMetadataAndEmptyLines() {
        val merged = Lrc.mergeTranslation(
            "[00:01.00]A",
            "[ti:标题]\n\n[00:01.00]译文",
        )
        assertEquals("[00:01.00]A\n译文", merged)
    }

    @Test
    fun mergeTranslationReturnsOriginalForBlankTranslation() {
        val original = "[00:01.00]A"
        assertEquals(original, Lrc.mergeTranslation(original, null))
        assertEquals(original, Lrc.mergeTranslation(original, "   "))
        assertEquals(original, Lrc.mergeTranslation(original, "[ti:only metadata]"))
        assertNull(Lrc.mergeTranslation(null, "[00:01.00]x"))
    }
}
