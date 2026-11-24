package org.ratelog.browse

import org.ratelog.MediaType
import java.time.LocalDate

/** Raw query parameters of `/discover` and `GET /api/v1/browse` (bound by name, all optional). */
data class BrowseParams(
    val type: String? = null,
    val decade: String? = null,
    val year: String? = null,
    val minRating: String? = null,
    val genre: String? = null,
    /**
     * One or more TMDB watch provider ids: `8`, `8|15|337` (what the bar writes), `8,15`, or the parameter
     * repeated (`provider=8&provider=15`, which Spring binds as `8,15`).
     */
    val provider: String? = null,
    val language: String? = null,
    val runtime: String? = null,
    val popular: String? = null,
    val sort: String? = null,
    val hideRated: String? = null,
    val hideWatchlist: String? = null,
    val hideSeen: String? = null,
    val page: String? = null,
)

data class InvalidBrowseParam(val name: String, val value: String, val reason: String)

/**
 * @property invalid parameters that could not be used. The web page ignores them; the API answers 400.
 * @property dropped valid parameters left out by a combination rule (e.g. `sort` next to `popular`).
 */
data class ParsedBrowse(
    val filters: BrowseFilters,
    val page: Int,
    val invalid: List<InvalidBrowseParam>,
    val dropped: List<String>,
)

object BrowseParamParser {
    const val MAX_PAGE = 500
    /** More services than anyone ticks; keeps URLs and TMDB requests bounded. */
    const val MAX_PROVIDERS = 30
    private val PROVIDER_SEPARATORS = Regex("[|,\\s]+")
    const val FIRST_FILM_YEAR = 1874
    private val LANGUAGE = Regex("^[a-z]{2}$")
    private val DECADE = Regex("^(\\d{3}0)s?$")
    private val EARLIER = setOf("earlier", "pre1960", "pre-1960", "older", "1950s-and-earlier")

    /** The latest year (and decade) a filter may name: ten years ahead, for announced titles. */
    fun latestYear(today: LocalDate): Int = today.year + 10

    /**
     * `2010s`, `2010` or `earlier` as a decade (1870s up to [latestYear]'s decade); null when it isn't one.
     * Fork (2026-11-24): shared with the search page's Year menu (`search/SearchYearFilter.kt`).
     */
    fun decadeOrNull(raw: String, today: LocalDate): BrowseDecade? {
        val key = raw.trim().lowercase()
        if (key in EARLIER) return BrowseDecade.Earlier
        val start = DECADE.find(key)?.groupValues?.get(1)?.toInt() ?: return null
        return if (start < 1870 || start > latestYear(today)) null else BrowseDecade.Of(start)
    }

    /** A year from [FIRST_FILM_YEAR] up to [latestYear]; null otherwise. Fork (2026-11-24): shared with search. */
    fun yearOrNull(raw: String, today: LocalDate): Int? =
        raw.trim().toIntOrNull()?.takeIf { it in FIRST_FILM_YEAR..latestYear(today) }

    /**
     * @param hideSeenByDefault what a missing `hideSeen` means: true on the Discover page (seen titles are
     *   hidden unless `hideSeen=0`), false for the API, which kept its original behavior.
     */
    fun parse(params: BrowseParams, today: LocalDate, hideSeenByDefault: Boolean = true): ParsedBrowse {
        val invalid = mutableListOf<InvalidBrowseParam>()
        val dropped = mutableListOf<String>()
        fun bad(name: String, value: String, reason: String): Nothing? {
            invalid += InvalidBrowseParam(name, value, reason)
            return null
        }
        val latestYear = latestYear(today)

        val type = params.type.clean()?.let { raw ->
            when (raw.lowercase()) {
                "movie", "movies", "film", "films" -> MediaType.movie
                "tv", "tvshow", "tv_show", "show", "shows", "series" -> MediaType.tvshow
                else -> bad("type", raw, "type must be \"movie\" or \"tv\"")
            }
        } ?: MediaType.movie

        var decade: BrowseDecade? = params.decade.clean()?.let { raw ->
            decadeOrNull(raw, today) ?: bad("decade", raw, "decade must look like 2010s (1870s-${latestYear / 10 * 10}s) or \"earlier\"")
        }

        val year: BrowseYear? = params.year.clean()?.let { raw ->
            if (raw.equals("upcoming", ignoreCase = true)) {
                if (type == MediaType.movie) BrowseYear.Upcoming else bad("year", raw, "\"upcoming\" is only available for movies")
            } else {
                yearOrNull(raw, today)?.let { BrowseYear.Of(it) }
                    ?: bad("year", raw, "year must be between $FIRST_FILM_YEAR and $latestYear, or \"upcoming\"")
            }
        }

        // A year wins over a decade it isn't part of; "upcoming" replaces any decade.
        when (val y = year) {
            BrowseYear.Upcoming -> if (decade != null) { decade = null; dropped += "decade" }
            is BrowseYear.Of -> {
                val d = decade
                val fits = when (d) {
                    null -> true
                    BrowseDecade.Earlier -> y.year <= BrowseDecade.Earlier.LAST_YEAR
                    is BrowseDecade.Of -> d.contains(y.year)
                }
                if (!fits) { decade = null; dropped += "decade" }
            }
            null -> Unit
        }

        val minRating = params.minRating.clean()?.let { raw ->
            val value = raw.toDoubleOrNull()
            if (value == null || value.isNaN() || value <= 0.0 || value >= 10.0) bad("minRating", raw, "minRating must be a number above 0 and below 10")
            else Math.round(value * 10) / 10.0
        }

        val genre = params.genre.clean()?.let { raw ->
            BrowseGenres.parse(raw, type) ?: bad("genre", raw, "unknown ${BrowseFilters.typeParam(type)} genre")
        }

        val providers = params.provider.clean()?.let { raw ->
            val ids = raw.split(PROVIDER_SEPARATORS).filter { it.isNotEmpty() }.map { it.toIntOrNull()?.takeIf { id -> id > 0 } }
            when {
                ids.isEmpty() -> emptyList()
                null in ids -> bad("provider", raw, "provider must be TMDB watch provider ids, e.g. 8 or 8|15|337")
                ids.distinct().size > MAX_PROVIDERS -> bad("provider", raw, "provider takes at most $MAX_PROVIDERS services")
                else -> BrowseFilters.normalizeProviders(ids.filterNotNull())
            }
        }.orEmpty()

        val language = params.language.clean()?.let { raw ->
            raw.lowercase().takeIf { LANGUAGE.matches(it) } ?: bad("language", raw, "language must be a 2-letter ISO 639-1 code")
        }

        val runtime = params.runtime.clean()?.let { raw ->
            val parsed = BrowseRuntime.parse(raw) ?: return@let bad("runtime", raw, "runtime must be short, medium or long")
            if (type == MediaType.movie) parsed else bad("runtime", raw, "runtime is only available for movies")
        }

        val popular = params.popular.clean()?.let { raw ->
            BrowsePopular.parse(raw) ?: bad("popular", raw, "popular must be week, now or alltime")
        }

        var sort = params.sort.clean()?.let { raw ->
            BrowseSort.parse(raw) ?: bad("sort", raw, "sort must be popularity, rating, newest, oldest or title")
        }
        if (popular != null && sort != null) { sort = null; dropped += "sort" }

        val hideRated = params.hideRated.clean()?.let { raw -> parseFlag(raw) ?: bad("hideRated", raw, "hideRated must be true or false") } ?: false
        val hideWatchlist = params.hideWatchlist.clean()?.let { raw -> parseFlag(raw) ?: bad("hideWatchlist", raw, "hideWatchlist must be true or false") } ?: false
        val hideSeen = params.hideSeen.clean()?.let { raw -> parseFlag(raw) ?: bad("hideSeen", raw, "hideSeen must be true or false") } ?: hideSeenByDefault

        val page = params.page.clean()?.let { raw ->
            raw.toIntOrNull()?.takeIf { it in 1..MAX_PAGE } ?: bad("page", raw, "page must be between 1 and $MAX_PAGE")
        } ?: 1

        return ParsedBrowse(
            filters = BrowseFilters(
                type = type,
                decade = decade,
                year = year,
                minRating = minRating,
                genre = genre,
                providers = providers,
                language = language,
                runtime = runtime,
                popular = popular,
                sort = sort,
                hideRated = hideRated,
                hideWatchlist = hideWatchlist,
                hideSeen = hideSeen,
            ),
            page = page,
            invalid = invalid,
            dropped = dropped,
        )
    }

    private fun parseFlag(raw: String): Boolean? = when (raw.lowercase()) {
        "1", "true", "yes", "on" -> true
        "0", "false", "no", "off" -> false
        else -> null
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
