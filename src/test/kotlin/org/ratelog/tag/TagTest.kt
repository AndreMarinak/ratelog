package org.ratelog.tag

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TagTest {

    @Test
    fun `tag names are trimmed and inner whitespace is collapsed`() {
        assertEquals(TagName("comfort watch").right(), TagName.parse("  comfort \t  watch \n"))
    }

    @Test
    fun `tag names must be 1 to 40 characters`() {
        assertEquals(TagParseError.InvalidName.left(), TagName.parse("   "))
        assertEquals(TagParseError.InvalidName.left(), TagName.parse(null))
        assertEquals(TagParseError.InvalidName.left(), TagName.parse("x".repeat(41)))
        assertEquals(TagName("x".repeat(40)).right(), TagName.parse("x".repeat(40)))
    }

    @Test
    fun `tag names reject control characters`() {
        assertEquals(TagParseError.InvalidName.left(), TagName.parse("bad\u0007name"))
    }

    @Test
    fun `names compare ignoring case`() {
        assertEquals(TagName("Mind-Benders").key, TagName("mind-benders").key)
    }

    @Test
    fun `colors parse from the palette, blank or none means no color`() {
        assertEquals(TagColor.teal.right(), TagColor.parse(" Teal "))
        assertEquals(null.right(), TagColor.parse(null))
        assertEquals(null.right(), TagColor.parse(""))
        assertEquals(null.right(), TagColor.parse("none"))
        assertEquals(TagParseError.InvalidColor.left(), TagColor.parse("#ff0000"))
    }
}
