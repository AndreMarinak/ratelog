package org.ratelog.search

import org.ratelog.browse.BrowseDecade
import org.ratelog.browse.BrowseParamParser
import java.time.LocalDate

/**
 * Fork (2026-11-24): what the search page's Year menu filters by, from the URL's `year` / `decade` (written like
 * Discover's: `year=2023`, `decade=2020s`, `decade=earlier`). An exact year is TMDB's own filter; a decade is filtered on
 * our side (TMDB's search has no date range), see [SearchHandler]. docs/decisions/people-and-credits.md "Year menu".
 */
sealed interface SearchYearFilter {
    data object AnyYear : SearchYearFilter

    data class Exact(val year: Int) : SearchYearFilter

    data class Decade(val decade: BrowseDecade) : SearchYearFilter {
        /** A title without a known year is in no decade. */
        fun contains(year: Int?): Boolean = year != null && when (decade) {
            is BrowseDecade.Of -> decade.contains(year)
            BrowseDecade.Earlier -> year <= BrowseDecade.Earlier.LAST_YEAR
        }
    }

    /** The URL parameter this choice writes, e.g. `year` to `2023`; null for any year. */
    val param: Pair<String, String>?
        get() = when (this) {
            AnyYear -> null
            is Exact -> "year" to year.toString()
            is Decade -> "decade" to decade.param
        }

    companion object {
        /**
         * A valid year wins (a decade next to it is ignored: the year says more); else a valid decade; else any year.
         * Invalid values are ignored here; [invalidYear] tells the page a typed year wasn't one.
         */
        fun parse(year: String?, decade: String?, today: LocalDate): SearchYearFilter {
            year?.takeIf { it.isNotBlank() }?.let { BrowseParamParser.yearOrNull(it, today) }?.let { return Exact(it) }
            decade?.takeIf { it.isNotBlank() }?.let { BrowseParamParser.decadeOrNull(it, today) }?.let { return Decade(it) }
            return AnyYear
        }

        /** The `year` text when it was given but isn't a year we search (letters, 1492, 2999); null otherwise. */
        fun invalidYear(year: String?, today: LocalDate): String? =
            year?.trim()?.takeIf { it.isNotEmpty() && BrowseParamParser.yearOrNull(it, today) == null }?.take(12)
    }
}
