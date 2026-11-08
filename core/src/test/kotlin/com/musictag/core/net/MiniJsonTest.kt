package com.musictag.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MiniJson` is the foundation of every provider, so these tests pin down exact
 * parsed values (a parser that silently ignores a field fails here).
 */
class MiniJsonTest {
    @Test
    fun parsesObjectsArraysAndScalars() {
        val json = """
            {
              "name": "Hey Jude",
              "count": 42,
              "ratio": 1.5,
              "ok": true,
              "nothing": null,
              "artist": {"name": "The Beatles", "tags": ["rock", "pop"]}
            }
        """.trimIndent()

        val map = MiniJson.parse(json).asMap()!!

        assertEquals("Hey Jude", map.str("name"))
        assertEquals(42L, map.long("count"))
        assertEquals(1.5, map["ratio"] as Double, 0.0)
        assertEquals(true, map["ok"])
        assertNull(map["nothing"])
        val artist = map.obj("artist")!!
        assertEquals("The Beatles", artist.str("name"))
        assertEquals(listOf("rock", "pop"), artist.arr("tags"))
    }

    @Test
    fun parsesNumberForms() {
        val map = MiniJson.parse(
            """{"int":7,"neg":-3,"frac":0.25,"exp":1.5e2,"negExp":2e-3,"big":1888354230}""",
        ).asMap()!!

        assertEquals(7L, map.long("int"))
        assertEquals(-3L, map.long("neg"))
        assertEquals(0.25, map["frac"] as Double, 0.0)
        assertEquals(150.0, map["exp"] as Double, 0.0)
        assertEquals(0.002, map["negExp"] as Double, 0.0)
        assertEquals(1888354230L, map.long("big"))
    }

    @Test
    fun decodesAllEscapes() {
        val map = MiniJson.parse(
            "{\"s\":\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\"}",
        ).asMap()!!
        assertEquals("a\"b\\c/d\be\u000Cf\ng\rh\ti", map.str("s"))
    }

    @Test
    fun decodesUnicodeEscapesIncludingSurrogatePairs() {
        val map = MiniJson.parse("""{"s":"\u4F60\u597D \uD83C\uDFB5"}""").asMap()!!

        assertEquals("你好 🎵", map.str("s"))
        assertEquals(5, map.str("s")!!.length) // 2 BMP chars + space + surrogate pair (2 units)
        assertEquals(0x1F3B5, map.str("s")!!.codePointAt(3))
    }

    @Test
    fun rejectsMalformedInput() {
        val malformed = listOf(
            "",
            "   ",
            "{",
            "{\"a\":}",
            "[1,]",
            "[1 2]",
            "{\"a\":1,}",
            "{\"a\" 1}",
            "{\"a\":\"unterminated",
            "'single quoted'",
            "{\"a\":1} trailing",
            "nul",
            "{\"a\":tru}",
        )
        malformed.forEach { input ->
            val error = assertThrows(NetException::class.java) { MiniJson.parse(input) }
            assertEquals("响应格式异常", error.message)
        }
    }

    @Test
    fun respectsNestingDepthLimit() {
        val ok = "[".repeat(100) + "]".repeat(100)
        assertTrue(MiniJson.parse(ok) is List<*>)

        val tooDeep = "[".repeat(200) + "]".repeat(200)
        val error = assertThrows(NetException::class.java) { MiniJson.parse(tooDeep) }
        assertEquals("响应格式异常", error.message)
    }

    @Test
    fun lenientAccessorsReturnNullForMismatchedTypes() {
        val map = MiniJson.parse("""{"s":"x","n":3,"o":{"k":1},"a":[1,2]}""").asMap()!!

        assertNull(3.asMap())
        assertNull("x".asList())
        assertNull(map.obj("s"))
        assertNull(map.arr("s"))
        assertNull(map.obj("missing"))
        assertNull(map.arr("missing"))
        assertNull(map.str("o"))
        assertNull(map.long("o"))
        assertNull(map.str("missing"))
        assertNull(map.long("missing"))
        assertEquals("x", map.str("s"))
        assertEquals(3L, map.long("n"))
        assertEquals(listOf(1L, 2L), map.arr("a")!!.map { (it as Double).toLong() })
    }

    @Test
    fun accessorsCoercePrimitiveValues() {
        val map = MiniJson.parse(
            """{"num":"123","decimal":"12.7","bad":"x","flag":true,"real":9}""",
        ).asMap()!!

        assertEquals("123", map.str("num"))
        assertEquals(123L, map.long("num"))
        assertEquals(12L, map.long("decimal"))
        assertNull(map.long("bad"))
        assertEquals("true", map.str("flag"))
        assertEquals(9L, map.long("real"))
    }

    @Test
    fun parsesJsonObjectBodyToleratesJsonpAndBom() {
        assertEquals(1L, parseJsonObjectBody("callback({\"a\":1});").long("a"))
        assertEquals(2L, parseJsonObjectBody("\uFEFF{\"a\":2}").long("a"))
        val error = assertThrows(NetException::class.java) { parseJsonObjectBody("[1,2,3]") }
        assertEquals("响应格式异常", error.message)
    }
}
