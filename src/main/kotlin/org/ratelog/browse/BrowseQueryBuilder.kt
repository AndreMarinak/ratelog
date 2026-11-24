package org.ratelog.browse

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import java.time.LocalDate
import java.util.SortedMap

/** One TMDB list call: a path plus its query parameters (sorted, never the api_key). */
data class TmdbListRequest(
    val type: MediaType,
    val path: String,
    val params: SortedMap<String, String>,
) {
    /** Normalized: same filters + page = same key. */
    val cacheKey: String get() = path + "?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }
}

/**
 * Turns [BrowseFilters] into TMDB `/discover/{movie|tv}` parameters, or `/trending/{movie|tv}/week` for
 * "Popular: This week". Trending takes no filters, so the ones that can be checked on its results
 * (genre, language, release window, rating) are applied by [matchesTrending]; the rest are [ignored].
 */
object BrowseQueryBuilder {
    /** "Highest rated" ordering: enough votes that a handful of 10/10s can't top the list. */
    const val MOVIE_RATING_SORT_MIN_VOTES = 300
    const val TV_RATING_SORT_MIN_VOTES = 200

    /** A rating floor ("7+") with another ordering. */
    const val MOVIE_RATING_FLOOR_MIN_VOTES = 100
    const val TV_RATING_FLOOR_MIN_VOTES = 50

    /** Title and release-date orderings: skips entries almost nobody has seen. */
    const val LISTING_MIN_VOTES = 20

    fun build(filters: BrowseFilters, page: Int, lang: Lang, region: WatchRegion, today: LocalDate): TmdbListRequest {
        val typePath = if (filters.isMovie) "movie" else "tv"
        if (filters.popular == BrowsePopular.WEEK) {
            return TmdbListRequest(
                type = filters.type,
                path = "/trending/$typePath/week",
                params = sortedMapOf("language" to lang.name, "page" to page.toString()),
            )
        }

        val params = sortedMapOf(
            "include_adult" to "false",
            "language" to lang.name,
            "page" to page.toString(),
            "sort_by" to sortBy(filters),
        )
        if (filters.isMovie) params["include_video"] = "false"

        val dateField = if (filters.isMovie) "primary_release_date" else "first_air_date"
        val (from, to) = releaseWindow(filters, today)
        from?.let { params["$dateField.gte"] = it.toString() }
        to?.let { params["$dateField.lte"] = it.toString() }

        minVotes(filters)?.let { params["vote_count.gte"] = it.toString() }
        filters.minRating?.let { params["vote_average.gte"] = BrowseFilters.formatRating(it) }
        filters.genre?.let { params["with_genres"] = it.tmdbId.toString() }
        if (filters.providers.isNotEmpty()) {
            // A pipe is TMDB's OR: titles streaming on any of the chosen services.
            params["with_watch_providers"] = filters.providers.joinToString("|")
            params["watch_region"] = region.value
            params["with_watch_monetization_types"] = "flatrate"
        }
        filters.language?.let { params["with_original_language"] = it }
        if (filters.isMovie) {
            filters.runtime?.let { runtime ->
                runtime.minMinutes?.let { params["with_runtime.gte"] = it.toString() }
                runtime.maxMinutes?.let { params["with_runtime.lte"] = it.toString() }
            }
        }
        return TmdbListRequest(filters.type, "/discover/$typePath", params)
    }

    /** Filters "This week" can't apply (trending has no service, runtime or ordering options). */
    fun ignored(filters: BrowseFilters): List<String> {
        if (filters.popular != BrowsePopular.WEEK) return emptyList()
        return buildList {
            if (filters.providers.isNotEmpty()) add("provider")
            if (filters.runtime != null) add("runtime")
        }
    }

    /** True when trending results are narrowed locally, so a page may need topping up from the next one. */
    fun filtersTrendingLocally(filters: BrowseFilters): Boolean =
        filters.popular == BrowsePopular.WEEK &&
            (filters.genre != null || filters.language != null || filters.minRating != null || filters.decade != null || filters.year != null)

    /** The part of the filters that can be checked on a trending result. */
    fun matchesTrending(filters: BrowseFilters, title: BrowseTitle, today: LocalDate): Boolean {
        if (filters.genre != null && filters.genre.tmdbId !in title.genreIds) return false
        if (filters.language != null && !filters.language.equals(title.originalLanguage, ignoreCase = true)) return false
        filters.minRating?.let { floor ->
            if ((title.voteAverage ?: 0.0) < floor) return false
            if ((title.voteCount ?: 0) < ratingFloorVotes(filters.type)) return false
        }
        val (from, to) = releaseWindow(filters.copy(sort = null), today)
        if (from != null || to != null) {
            val date = title.releaseDate ?: return false
            if (from != null && date < from) return false
            if (to != null && date > to) return false
        }
        return true
    }

    fun minVotes(filters: BrowseFilters): Int? {
        if (filters.popular != null) {
            return filters.minRating?.let { ratingFloorVotes(filters.type) }
        }
        val forSort = when (filters.sort) {
            BrowseSort.RATING -> if (filters.isMovie) MOVIE_RATING_SORT_MIN_VOTES else TV_RATING_SORT_MIN_VOTES
            BrowseSort.TITLE, BrowseSort.NEWEST, BrowseSort.OLDEST ->
                // Unreleased titles have no votes yet.
                if (filters.year == BrowseYear.Upcoming) null else LISTING_MIN_VOTES
            BrowseSort.POPULARITY, null -> null
        }
        val forFloor = filters.minRating?.let { ratingFloorVotes(filters.type) }
        return listOfNotNull(forSort, forFloor).maxOrNull()
    }

    private fun ratingFloorVotes(type: MediaType) =
        if (type == MediaType.movie) MOVIE_RATING_FLOOR_MIN_VOTES else TV_RATING_FLOOR_MIN_VOTES

    private fun sortBy(filters: BrowseFilters): String = when (filters.popular) {
        BrowsePopular.NOW -> "popularity.desc"
        BrowsePopular.ALL_TIME -> "vote_count.desc"
        BrowsePopular.WEEK, null -> when (filters.sort) {
            BrowseSort.RATING -> "vote_average.desc"
            BrowseSort.NEWEST -> if (filters.isMovie) "primary_release_date.desc" else "first_air_date.desc"
            BrowseSort.OLDEST -> if (filters.isMovie) "primary_release_date.asc" else "first_air_date.asc"
            BrowseSort.TITLE -> if (filters.isMovie) "title.asc" else "name.asc"
            BrowseSort.POPULARITY, null -> "popularity.desc"
        }
    }

    /**
     * Release date range (inclusive). "Newest first" stops at today, otherwise announced titles
     * dated years ahead would fill the first pages.
     */
    private fun releaseWindow(filters: BrowseFilters, today: LocalDate): Pair<LocalDate?, LocalDate?> {
        var from: LocalDate? = null
        var to: LocalDate? = null
        when (val year = filters.year) {
            BrowseYear.Upcoming -> from = today.plusDays(1)
            is BrowseYear.Of -> {
                from = LocalDate.of(year.year, 1, 1)
                to = LocalDate.of(year.year, 12, 31)
            }
            null -> when (val decade = filters.decade) {
                is BrowseDecade.Of -> {
                    from = LocalDate.of(decade.startYear, 1, 1)
                    to = LocalDate.of(decade.endYear, 12, 31)
                }
                BrowseDecade.Earlier -> to = LocalDate.of(BrowseDecade.Earlier.LAST_YEAR, 12, 31)
                null -> Unit
            }
        }
        if (filters.sort == BrowseSort.NEWEST && filters.year != BrowseYear.Upcoming && filters.popular == null) {
            to = if (to == null || to > today) today else to
        }
        return from to to
    }
}
