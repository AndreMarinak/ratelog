package org.ratelog.extras

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class RefreshPolicyTest {

    private val today = LocalDate.parse("2026-11-22")
    private val now = Instant.parse("2026-11-22T12:00:00Z")
    private val someScores = ExternalScores(imdbRating = 7.0)

    @Test
    fun `titles released in the last 30 days refresh daily`() {
        assertEquals(Duration.ofDays(1), RefreshPolicy.scoresInterval(today, today))
        assertEquals(Duration.ofDays(1), RefreshPolicy.scoresInterval(today.minusDays(30), today))
    }

    @Test
    fun `titles released within the last year refresh weekly`() {
        assertEquals(Duration.ofDays(7), RefreshPolicy.scoresInterval(today.minusDays(31), today))
        assertEquals(Duration.ofDays(7), RefreshPolicy.scoresInterval(today.minusDays(365), today))
    }

    @Test
    fun `older titles refresh every 30 days`() {
        assertEquals(Duration.ofDays(30), RefreshPolicy.scoresInterval(today.minusDays(366), today))
        assertEquals(Duration.ofDays(30), RefreshPolicy.scoresInterval(LocalDate.parse("1999-03-31"), today))
    }

    @Test
    fun `unknown and future release dates refresh weekly`() {
        assertEquals(Duration.ofDays(7), RefreshPolicy.scoresInterval(null, today))
        assertEquals(Duration.ofDays(7), RefreshPolicy.scoresInterval(today.plusDays(90), today))
    }

    @Test
    fun `next refresh uses the release-age interval when there are scores`() {
        assertEquals(now + Duration.ofDays(1), RefreshPolicy.nextScoresRefresh(someScores, today.minusDays(3), now))
        assertEquals(now + Duration.ofDays(30), RefreshPolicy.nextScoresRefresh(someScores, today.minusYears(5), now))
    }

    @Test
    fun `no data is not asked again for 7 days, even for brand new titles`() {
        assertEquals(now + Duration.ofDays(7), RefreshPolicy.nextScoresRefresh(null, today.minusDays(1), now))
        assertEquals(now + Duration.ofDays(7), RefreshPolicy.nextScoresRefresh(ExternalScores(imdbId = "tt0000001"), today, now))
        assertEquals(now + Duration.ofDays(7), RefreshPolicy.nextScoresRefresh(null, today.minusYears(20), now))
    }

    @Test
    fun `isDue is true from the refresh instant on and false when never scheduled`() {
        assertFalse(RefreshPolicy.isDue(null, now))
        assertFalse(RefreshPolicy.isDue(now.plusSeconds(1), now))
        assertTrue(RefreshPolicy.isDue(now, now))
        assertTrue(RefreshPolicy.isDue(now.minusSeconds(1), now))
    }
}
