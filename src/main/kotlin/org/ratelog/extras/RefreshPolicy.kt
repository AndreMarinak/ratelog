package org.ratelog.extras

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * How long cached extras stay fresh. Scores move fast right after release and barely at all later,
 * and MDBList's free tier allows 1,000 requests a day, so the interval grows with the title's age.
 */
object RefreshPolicy {
    val TMDB_INTERVAL: Duration = Duration.ofDays(7)

    /** When MDBList has no scores for a title, don't ask again for a week. */
    val NO_DATA_RETRY: Duration = Duration.ofDays(7)

    /** After a network error or unexpected response. */
    val FAILURE_RETRY: Duration = Duration.ofHours(1)

    fun scoresInterval(releaseDate: LocalDate?, today: LocalDate): Duration {
        // Unknown or not released yet: nothing to score, check back weekly.
        if (releaseDate == null || releaseDate.isAfter(today)) return Duration.ofDays(7)
        val ageInDays = ChronoUnit.DAYS.between(releaseDate, today)
        return when {
            ageInDays <= 30 -> Duration.ofDays(1)
            ageInDays <= 365 -> Duration.ofDays(7)
            else -> Duration.ofDays(30)
        }
    }

    fun nextScoresRefresh(scores: ExternalScores?, releaseDate: LocalDate?, now: Instant): Instant =
        if (scores == null || !scores.hasAnyScore()) now + NO_DATA_RETRY
        else now + scoresInterval(releaseDate, LocalDate.ofInstant(now, ZoneOffset.UTC))

    fun isDue(nextRefreshAt: Instant?, now: Instant): Boolean = nextRefreshAt != null && !now.isBefore(nextRefreshAt)
}
