package org.ratelog.search

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.browse.BrowseDecade
import org.ratelog.tmdb.TmdbClient
import org.ratelog.user.User
import org.springframework.transaction.annotation.Transactional

data class SearchQuery(
    val query: String,
    val userId: User.Id,
    val lang: Lang,
    val mediaType: MediaType?,
    val page: Int = 1,
    /** Fork (2026-11-24): only titles released (movies) / first aired (TV) in this year; null = any year. */
    val year: Int? = null,
    /**
     * Fork (2026-11-24): only titles from this decade (ignored when [year] is set). TMDB can't filter a range, so
     * [page] is then the TMDB page to start reading from (see [SearchHandler.handle]).
     */
    val decade: BrowseDecade? = null,
)

data class SearchResult(
    val items: List<SearchResultItem>,
    val hasMore: Boolean,
    /**
     * Fork: the last TMDB page this result read; the next results page starts after it. Equal to the requested page,
     * except for a decade, which reads several.
     */
    val lastPage: Int? = null,
)

data class SearchResultItem(
    val tmdbId: Int,
    val title: String,
    val overview: String?,
    val year: Int?,
    val posterPath: String?,
    val type: String,
    /** Fork: release date (movies) / first air date (TV) as TMDB sends it, for the Upcoming badge. */
    val releaseDate: String? = null,
)

@org.springframework.stereotype.Service
class SearchHandler(
    private val tmdbClient: TmdbClient,
) {
    @Transactional
    fun handle(query: SearchQuery): Either<SearchHandlerError, SearchResult> = either {
        if (query.query.isBlank()) return@either SearchResult(emptyList(), false)
        val decade = query.decade
        if (query.year == null && decade != null) return@either decadeSearch(query, SearchYearFilter.Decade(decade)).bind()

        // Fork (2026-11-24): a results page is the whole TMDB page (20; 40 for Any, movies and shows interleaved). It was
        // cut to the first 9 while the next page started at the next TMDB page, so titles 10-20 of every page never showed.
        when (query.mediaType) {
            MediaType.movie -> {
                val movies = searchMovies(query.query, query.lang, query.page, query.year).bind()
                SearchResult(movies.first, movies.second > query.page)
            }
            MediaType.tvshow -> {
                val shows = searchTvShows(query.query, query.lang, query.page, query.year).bind()
                SearchResult(shows.first, shows.second > query.page)
            }
            null -> {
                val movies = searchMovies(query.query, query.lang, query.page, query.year).bind()
                val shows = searchTvShows(query.query, query.lang, query.page, query.year).bind()
                SearchResult(interleave(movies.first, shows.first), movies.second > query.page || shows.second > query.page)
            }
        }
    }

    /**
     * Fork (2026-11-24): a decade, filtered here. Reads TMDB pages from `query.page` on (movies and TV page by page
     * together for Any) and keeps the titles of the decade, until [DECADE_FILL] are found, TMDB has no
     * more pages, or [DECADE_PAGES_PER_LOAD] pages were read; never past TMDB page [DECADE_MAX_PAGE]. Every match of
     * the pages read is shown (none dropped: the next load starts after [SearchResult.lastPage]). The trade-off is
     * documented in docs/decisions/people-and-credits.md "Year menu".
     */
    private fun decadeSearch(query: SearchQuery, decade: SearchYearFilter.Decade): Either<SearchHandlerError, SearchResult> = either {
        val wantMovies = query.mediaType != MediaType.tvshow
        val wantShows = query.mediaType != MediaType.movie
        var movieTotal = Int.MAX_VALUE
        var showTotal = Int.MAX_VALUE
        val found = mutableListOf<SearchResultItem>()
        val start = query.page.coerceAtLeast(1)
        if (start > DECADE_MAX_PAGE) return@either SearchResult(emptyList(), false, lastPage = start)
        var page = start - 1
        var more: Boolean
        do {
            page++
            val movies = if (wantMovies && page <= movieTotal) {
                searchMovies(query.query, query.lang, page, null).bind().also { movieTotal = it.second }.first
            } else emptyList()
            val shows = if (wantShows && page <= showTotal) {
                searchTvShows(query.query, query.lang, page, null).bind().also { showTotal = it.second }.first
            } else emptyList()
            found += interleave(movies.filter { decade.contains(it.year) }, shows.filter { decade.contains(it.year) })
            more = page < DECADE_MAX_PAGE && ((wantMovies && page < movieTotal) || (wantShows && page < showTotal))
        } while (more && found.size < DECADE_FILL && page - start + 1 < DECADE_PAGES_PER_LOAD)
        SearchResult(found.distinctBy { it.type to it.tmdbId }, more, lastPage = page)
    }

    companion object {
        /** A decade's results page stops reading TMDB pages once this many titles of the decade were found. */
        private const val DECADE_FILL = 9

        /** At most this many TMDB pages (per type) are read for one results page of a decade (latency cap). */
        const val DECADE_PAGES_PER_LOAD = 5

        /** A decade only looks through TMDB's first 20 pages per type (400 titles, by relevance): a bounded search. */
        const val DECADE_MAX_PAGE = 20
    }

    private fun interleave(movies: List<SearchResultItem>, shows: List<SearchResultItem>): List<SearchResultItem> {
        val result = mutableListOf<SearchResultItem>()
        val maxSize = maxOf(movies.size, shows.size)
        for (i in 0 until maxSize) {
            if (i < movies.size) result.add(movies[i])
            if (i < shows.size) result.add(shows[i])
        }
        return result
    }

    private fun searchMovies(query: String, lang: Lang, page: Int, year: Int?): Either<SearchHandlerError, Pair<List<SearchResultItem>, Int>> =
        either {
            val (movies, totalPages) = (if (year == null) tmdbClient.searchMovies(query, lang, page) else tmdbClient.searchMovies(query, lang, page, year)).bind()
            val items = movies.map {
                SearchResultItem(
                    tmdbId = it.id,
                    title = it.title,
                    overview = it.overview,
                    year = it.releaseDate?.takeIf { d -> d.isNotBlank() }?.take(4)?.toIntOrNull(),
                    posterPath = it.posterPath,
                    type = MediaType.movie.name,
                    releaseDate = it.releaseDate,
                )
            }
            items to totalPages
        }

    private fun searchTvShows(query: String, lang: Lang, page: Int, year: Int?): Either<SearchHandlerError, Pair<List<SearchResultItem>, Int>> =
        either {
            val (tvshows, totalPages) = (if (year == null) tmdbClient.searchTvShows(query, lang, page) else tmdbClient.searchTvShows(query, lang, page, year)).bind()
            val items = tvshows.map {
                SearchResultItem(
                    tmdbId = it.id,
                    title = it.name,
                    overview = it.overview,
                    year = it.firstAirDate?.takeIf { d -> d.isNotBlank() }?.take(4)?.toIntOrNull(),
                    posterPath = it.posterPath,
                    type = MediaType.tvshow.name,
                    releaseDate = it.firstAirDate,
                )
            }
            items to totalPages
        }
}
