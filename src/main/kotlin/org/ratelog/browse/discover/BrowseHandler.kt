package org.ratelog.browse.discover

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.ratelog.Lang
import org.ratelog.WatchRegion
import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowseLibraryRepository
import org.ratelog.browse.BrowseQueryBuilder
import org.ratelog.browse.BrowseTitle
import org.ratelog.browse.LibraryMark
import org.ratelog.browse.TmdbBrowseClient
import org.ratelog.browse.TmdbBrowseError
import org.ratelog.user.User
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

data class BrowseQuery(
    val userId: User.Id,
    val lang: Lang,
    val region: WatchRegion,
    val filters: BrowseFilters,
    /** The TMDB page to start from (1-500). */
    val page: Int,
)

data class BrowseTile(
    val title: BrowseTitle,
    /** The user's score / status, null when they have neither. */
    val mark: LibraryMark?,
)

data class BrowseResult(
    val tiles: List<BrowseTile>,
    val page: Int,
    /** The `page` to ask for next, or null at the end. Can skip ahead when hidden titles were topped up. */
    val nextPage: Int?,
    val totalPages: Int,
    val totalResults: Int,
    /** Filters the chosen source could not apply (see [BrowseQueryBuilder.ignored]). */
    val ignored: List<String>,
)

sealed interface BrowseError {
    data object TmdbNotConfigured : BrowseError
    data object TmdbKeyRejected : BrowseError
    data object TmdbUnavailable : BrowseError
}

/**
 * One page of browse results: a TMDB discover (or trending) page with the user's own score and status
 * looked up in bulk. When titles are hidden (rated / watchlist, or trending narrowed locally), the next TMDB
 * pages top the result up, up to [MAX_TMDB_PAGES] calls per request.
 */
@Service
class BrowseHandler(
    private val client: TmdbBrowseClient,
    private val library: BrowseLibraryRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(query: BrowseQuery): Either<BrowseError, BrowseResult> {
        val filters = query.filters
        val today = LocalDate.now(clock)
        val localFilter = BrowseQueryBuilder.filtersTrendingLocally(filters)
        val topUp = filters.hidesLibrary || localFilter

        val collected = LinkedHashMap<Int, BrowseTile>()
        var tmdbPage = query.page
        var totalPages = query.page
        var totalResults = 0
        var nextPage: Int? = null

        while (true) {
            val request = BrowseQueryBuilder.build(filters, tmdbPage, query.lang, query.region, today)
            val page = when (val result = client.page(request)) {
                is Either.Right -> result.value
                is Either.Left -> {
                    if (collected.isEmpty()) return result.value.toBrowseError().left()
                    // Keep what earlier pages gave; the caller can retry from the page that failed.
                    nextPage = tmdbPage
                    break
                }
            }
            totalPages = page.totalPages
            totalResults = page.totalResults

            val candidates = page.items
                .filter { it.tmdbId !in collected }
                .filter { !localFilter || BrowseQueryBuilder.matchesTrending(filters, it, today) }
            val marks = library.findMarks(query.userId, filters.type, candidates.map { it.tmdbId })
            candidates
                .filter { isVisible(filters, marks[it.tmdbId]) }
                .forEach { collected[it.tmdbId] = BrowseTile(it, marks[it.tmdbId]) }

            val more = tmdbPage < totalPages
            nextPage = if (more) tmdbPage + 1 else null
            val calls = tmdbPage - query.page + 1
            if (!topUp || !more || collected.size >= MIN_VISIBLE || calls >= MAX_TMDB_PAGES) break
            tmdbPage++
        }

        return BrowseResult(
            tiles = collected.values.toList(),
            page = query.page,
            nextPage = nextPage,
            totalPages = totalPages,
            totalResults = totalResults,
            ignored = BrowseQueryBuilder.ignored(filters),
        ).right()
    }

    private fun isVisible(filters: BrowseFilters, mark: LibraryMark?): Boolean {
        if (mark == null) return true
        if (filters.hideRated && mark.rated) return false
        if (filters.hideWatchlist && mark.onWatchlist) return false
        return true
    }

    private fun TmdbBrowseError.toBrowseError(): BrowseError = when (this) {
        TmdbBrowseError.ApiKeyMissing -> BrowseError.TmdbNotConfigured
        TmdbBrowseError.ApiKeyRejected -> BrowseError.TmdbKeyRejected
        is TmdbBrowseError.Failed -> BrowseError.TmdbUnavailable
    }

    companion object {
        /** Below this many visible titles, hidden ones are replaced from the next TMDB page. */
        const val MIN_VISIBLE = 12
        const val MAX_TMDB_PAGES = 3
    }
}
