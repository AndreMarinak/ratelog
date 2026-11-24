package org.ratelog.extras.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.extras.CastMember
import org.ratelog.extras.CreditPerson
import org.ratelog.extras.TitleCredits

class TitleCreditsViewTest {

    @Test
    fun `money is short and readable`() {
        assertEquals("$950", TitleCreditsView.money(950))
        assertEquals("$850K", TitleCreditsView.money(850_000))
        assertEquals("$6M", TitleCreditsView.money(6_000_000))
        assertEquals("$245.1M", TitleCreditsView.money(245_066_411))
        assertEquals("$1B", TitleCreditsView.money(999_990_000))
        assertEquals("$2.9B", TitleCreditsView.money(2_923_706_026))
    }

    @Test
    fun `writer jobs become lower-case labels, a plain Writer credit has none`() {
        val view = TitleCreditsView(
            TitleCredits(
                writers = listOf(
                    CreditPerson(1, "A", listOf("Screenplay", "Story")),
                    CreditPerson(2, "B", listOf("Writer")),
                    CreditPerson(3, "C", listOf("Writer", "Novel")),
                    CreditPerson(4, "D", listOf("Theatre Play")),
                ),
            ),
            isMovie = true,
        )

        assertEquals(listOf("screenplay, story", null, "novel", "theatre play"), view.writers.map { it.job })
        assertEquals(3, view.lineWriters.size)
        assertEquals(1, view.moreWriters)
        assertTrue(view.hasMoreDetails, "the cut writer is listed under More details")
    }

    @Test
    fun `cast gets photo URLs or an initial, and the preview size`() {
        val view = TitleCreditsView(
            TitleCredits(cast = (1..7).map { CastMember(it, if (it == 1) "  élodie Bouchez" else "Actor $it", null, if (it == 1) null else "/x$it.jpg") }),
            isMovie = true,
        )

        assertNull(view.cast[0].profileUrl)
        assertEquals("É", view.cast[0].initial)
        assertEquals("https://image.tmdb.org/t/p/w185/x2.jpg", view.cast[1].profileUrl)
        assertTrue(view.hasMoreCast)
        assertEquals(7, view.castCount)
    }

    @Test
    fun `show years - ended in one year, ended over several, still running`() {
        fun view(status: String?, first: Int?, last: Int?) =
            TitleCreditsView(TitleCredits(status = status, firstAirYear = first, lastAirYear = last), isMovie = false)

        assertTrue(view("Ended", 2019, 2019).airedSingleYear)
        assertFalse(view("Ended", 2008, 2013).airedSingleYear)
        assertTrue(view("Canceled", 2008, 2013).hasEnded)
        assertFalse(view("Returning Series", 2019, 2025).hasEnded)
        assertFalse(view(null, 2019, null).airedSingleYear)
    }

    @Test
    fun `nothing worth showing counts as empty`() {
        assertTrue(TitleCreditsView(TitleCredits(), isMovie = true).isEmpty)
        assertFalse(TitleCreditsView(TitleCredits(certification = "PG"), isMovie = true).isEmpty)
        assertFalse(TitleCreditsView(TitleCredits(networks = listOf("HBO")), isMovie = false).isEmpty)
    }
}
