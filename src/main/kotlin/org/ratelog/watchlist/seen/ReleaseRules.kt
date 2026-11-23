package org.ratelog.watchlist.seen

import org.ratelog.MediaType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * When a title counts as "Upcoming" and when following it (the bell) is worth showing.
 * Pure functions: callers pass today's date (from an injected Clock), so tests can pin it.
 */

object ReleaseRules {

    /** TMDB movie statuses that mean "not out yet" when TMDB has no release date. */
    private val UNRELEASED_MOVIE_STATUSES = setOf("rumored", "planned", "in production", "post production")

    /** TMDB TV statuses that mean "hasn't aired yet" when TMDB has no first air date. */
    private val UNAIRED_TV_STATUSES = setOf("planned", "in production")

    /** TMDB TV statuses of a show that is still being made, so following it can add new dates. */
    private val RUNNING_TV_STATUSES = setOf("returning series", "in production", "planned")

    /**
     * Released after today (a movie's release date, a show's first air date). A title released today is out.
     * No date means unknown, which is not upcoming here: poster tiles only know the date.
     */
    fun isUpcoming(date: LocalDate?, today: LocalDate): Boolean = date != null && date.isAfter(today)

    /**
     * Title pages also know TMDB's status: with no date, a movie that is rumored / planned / in (post) production,
     * or a show that is planned / in production, is upcoming too ("Upcoming", no date).
     */
    fun isUpcoming(type: MediaType, date: LocalDate?, tmdbStatus: String?, today: LocalDate): Boolean {
        if (date != null) return date.isAfter(today)
        val status = tmdbStatus.normalized() ?: return false
        return when (type) {
            MediaType.movie -> status in UNRELEASED_MOVIE_STATUSES
            MediaType.tvshow -> status in UNAIRED_TV_STATUSES
        }
    }

    /**
     * Following a title only adds its release dates to the Premieres box on the Ratings page (no notifications),
     * so the bell is shown only where that can do something:
     * - a movie whose release date is after today, or unknown (TBA);
     * - a show that is still running (Returning Series, In Production, Planned), not Ended / Canceled / unknown.
     * A title the user already follows always shows it (on), so a follow is never stranded without a way to undo it.
     */
    fun showFollow(type: MediaType, releaseDate: LocalDate?, tmdbStatus: String?, today: LocalDate, followed: Boolean): Boolean {
        if (followed) return true
        return when (type) {
            MediaType.movie -> releaseDate == null || releaseDate.isAfter(today)
            MediaType.tvshow -> tmdbStatus.normalized() in RUNNING_TV_STATUSES
        }
    }

    /**
     * Short date for poster badges: "Dec 18" (UI language) while it's under ten months away, so the year is never
     * ambiguous; "Feb 2028" further out.
     */
    fun shortDate(date: LocalDate, today: LocalDate, locale: Locale): String {
        val pattern = if (date.isBefore(today.plusMonths(10))) "MMMd" else "yMMM"
        return DateTimeFormatter.ofLocalizedPattern(pattern).withLocale(locale).format(date)
    }

    /** Full date for title pages: "Dec 18, 2026". */
    fun longDate(date: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofLocalizedPattern("yMMMd").withLocale(locale).format(date)

    private fun String?.normalized(): String? = this?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}
