package org.ratelog

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RatingScoresTest {

    @Test
    fun `score alone parses as a quick rating`() {
        val input = RatingInput.parse(8.5, null, null, null, null, null).getOrNull()

        assertEquals(RatingInput.Quick(Score(8.5)), input)
        assertNull(input!!.categories)
        assertEquals(8.5, input.score.value)
    }

    @Test
    fun `all five categories parse as a detailed rating scored by their average`() {
        val input = RatingInput.parse(null, 5.0, 6.0, 7.0, 8.0, 9.0).getOrNull()

        assertTrue(input is RatingInput.Detailed)
        assertEquals(7.0, input!!.score.value)
        assertEquals(5.0, input.categories!![RatingCategory.directing].value)
        assertEquals(9.0, input.categories!![RatingCategory.screenplay].value)
    }

    @Test
    fun `some categories only is an invalid combination`() {
        val result = RatingInput.parse(null, 5.0, 6.0, null, null, null)

        assertEquals(RatingInputError.InvalidCombination, result.leftOrNull())
    }

    @Test
    fun `score together with categories is an invalid combination`() {
        val result = RatingInput.parse(7.0, 5.0, 6.0, 7.0, 8.0, 9.0)

        assertEquals(RatingInputError.InvalidCombination, result.leftOrNull())
    }

    @Test
    fun `score with some categories is an invalid combination`() {
        val result = RatingInput.parse(7.0, 5.0, null, null, null, null)

        assertEquals(RatingInputError.InvalidCombination, result.leftOrNull())
    }

    @Test
    fun `nothing at all is an invalid combination`() {
        val result = RatingInput.parse(null, null, null, null, null, null)

        assertEquals(RatingInputError.InvalidCombination, result.leftOrNull())
    }

    @Test
    fun `out of range values are rejected for quick and detailed ratings`() {
        assertEquals(RatingInputError.OutOfRange, RatingInput.parse(10.5, null, null, null, null, null).leftOrNull())
        assertEquals(RatingInputError.OutOfRange, RatingInput.parse(-1.0, null, null, null, null, null).leftOrNull())
        assertEquals(RatingInputError.OutOfRange, RatingInput.parse(Double.NaN, null, null, null, null, null).leftOrNull())
        assertEquals(RatingInputError.OutOfRange, RatingInput.parse(null, 5.0, 5.0, 11.0, 5.0, 5.0).leftOrNull())
    }

    @Test
    fun `bounds are inclusive`() {
        assertTrue(RatingInput.parse(0.0, null, null, null, null, null).isRight())
        assertTrue(RatingInput.parse(10.0, null, null, null, null, null).isRight())
    }

    @Test
    fun `rating category parses known names ignoring case and blanks`() {
        assertEquals(RatingCategory.acting, RatingCategory.parse("acting"))
        assertEquals(RatingCategory.soundtrack, RatingCategory.parse(" Soundtrack "))
        assertNull(RatingCategory.parse(""))
        assertNull(RatingCategory.parse(null))
        assertNull(RatingCategory.parse("overall"))
    }
}
