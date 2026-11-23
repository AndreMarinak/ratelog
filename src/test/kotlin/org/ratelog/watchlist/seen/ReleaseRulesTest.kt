package org.ratelog.watchlist.seen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import java.time.LocalDate
import java.util.Locale

class ReleaseRulesTest {

    private val today = LocalDate.parse("2026-11-23")

    // --- Upcoming (dates only: poster tiles) ---

    @Test
    fun `tomorrow is upcoming, today and yesterday are out`() {
        assertTrue(ReleaseRules.isUpcoming(today.plusDays(1), today))
        assertFalse(ReleaseRules.isUpcoming(today, today), "released today counts as out")
        assertFalse(ReleaseRules.isUpcoming(today.minusDays(1), today))
        assertTrue(ReleaseRules.isUpcoming(LocalDate.parse("2026-12-18"), today), "Avengers: Doomsday")
    }

    @Test
    fun `no date is not upcoming on tiles`() {
        assertFalse(ReleaseRules.isUpcoming(null, today))
    }

    // --- Upcoming with TMDB's status (title pages) ---

    @Test
    fun `a date always wins over the status`() {
        assertTrue(ReleaseRules.isUpcoming(MediaType.movie, today.plusDays(1), "Released", today))
        assertFalse(ReleaseRules.isUpcoming(MediaType.movie, today, "Post Production", today))
        assertFalse(ReleaseRules.isUpcoming(MediaType.movie, today.minusDays(1), "In Production", today))
    }

    @Test
    fun `movies without a date are upcoming while TMDB says they are not out`() {
        listOf("Rumored", "Planned", "In Production", "Post Production", " in production ").forEach {
            assertTrue(ReleaseRules.isUpcoming(MediaType.movie, null, it, today), it)
        }
        listOf("Released", "Canceled", null, "").forEach {
            assertFalse(ReleaseRules.isUpcoming(MediaType.movie, null, it, today), "$it")
        }
    }

    @Test
    fun `shows use the first air date, else a planned or in-production status`() {
        assertTrue(ReleaseRules.isUpcoming(MediaType.tvshow, today.plusDays(1), "Returning Series", today), "first air date tomorrow")
        assertFalse(ReleaseRules.isUpcoming(MediaType.tvshow, today, "Returning Series", today), "first aired today")
        assertFalse(ReleaseRules.isUpcoming(MediaType.tvshow, today.minusYears(3), "Returning Series", today), "a new season is not 'upcoming'")
        assertTrue(ReleaseRules.isUpcoming(MediaType.tvshow, null, "Planned", today))
        assertTrue(ReleaseRules.isUpcoming(MediaType.tvshow, null, "In Production", today))
        listOf("Returning Series", "Ended", "Canceled", "Pilot", null).forEach {
            assertFalse(ReleaseRules.isUpcoming(MediaType.tvshow, null, it, today), "$it")
        }
    }

    // --- The follow bell ---

    @Test
    fun `movies show the bell when the release date is after today or unknown`() {
        assertTrue(ReleaseRules.showFollow(MediaType.movie, today.plusDays(1), "Post Production", today, followed = false), "future")
        assertTrue(ReleaseRules.showFollow(MediaType.movie, null, null, today, followed = false), "TBA")
        assertFalse(ReleaseRules.showFollow(MediaType.movie, today, "Released", today, followed = false), "released today")
        assertFalse(ReleaseRules.showFollow(MediaType.movie, today.minusDays(1), "Released", today, followed = false), "past")
        assertFalse(ReleaseRules.showFollow(MediaType.movie, LocalDate.parse("1994-06-24"), "Released", today, followed = false), "The Lion King")
    }

    @Test
    fun `shows show the bell only while they are still running`() {
        mapOf(
            "Returning Series" to true,
            "In Production" to true,
            "Planned" to true,
            "returning series" to true,
            "Ended" to false,
            "Canceled" to false,
            "Pilot" to false,
            "" to false,
        ).forEach { (status, shown) ->
            assertEquals(shown, ReleaseRules.showFollow(MediaType.tvshow, today.minusYears(2), status, today, followed = false), status)
        }
        assertFalse(ReleaseRules.showFollow(MediaType.tvshow, null, null, today, followed = false), "unknown status")
        assertFalse(ReleaseRules.showFollow(MediaType.tvshow, today.plusDays(10), "Ended", today, followed = false), "dates don't matter for TV")
    }

    @Test
    fun `a title already followed always keeps its bell so it can be unfollowed`() {
        assertTrue(ReleaseRules.showFollow(MediaType.movie, today.minusYears(30), "Released", today, followed = true))
        assertTrue(ReleaseRules.showFollow(MediaType.movie, today, "Released", today, followed = true))
        assertTrue(ReleaseRules.showFollow(MediaType.tvshow, today.minusYears(10), "Ended", today, followed = true))
        assertTrue(ReleaseRules.showFollow(MediaType.tvshow, null, "Canceled", today, followed = true))
        assertTrue(ReleaseRules.showFollow(MediaType.tvshow, null, null, today, followed = true))
    }

    // --- Date labels ---

    @Test
    fun `short dates leave the year out while it can't be ambiguous`() {
        assertEquals("Dec 18", ReleaseRules.shortDate(LocalDate.parse("2026-12-18"), today, Locale.ENGLISH))
        assertEquals("Feb 5", ReleaseRules.shortDate(LocalDate.parse("2027-02-05"), today, Locale.ENGLISH), "next year, under ten months away")
        assertEquals("Sep 2027", ReleaseRules.shortDate(LocalDate.parse("2027-09-30"), today, Locale.ENGLISH), "ten months or more away")
        assertEquals("May 2028", ReleaseRules.shortDate(LocalDate.parse("2028-05-01"), today, Locale.ENGLISH))
    }

    @Test
    fun `long dates are full and follow the UI language`() {
        assertEquals("Dec 18, 2026", ReleaseRules.longDate(LocalDate.parse("2026-12-18"), Locale.ENGLISH))
        assertTrue(ReleaseRules.longDate(LocalDate.parse("2026-12-18"), Locale.forLanguageTag("es")).contains("2026"))
    }
}
