package org.ratelog.api.media

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tmdb.TmdbError
import org.ratelog.tmdb.TmdbMovieResponse
import org.ratelog.tmdb.TmdbTvShowResponse
import org.springframework.stereotype.Component

/** Looks titles up on TMDB and picks the right one with [TitleMatcher]. Never writes to the DB. */
@Component
class TitleResolver(
    private val tmdbClient: TmdbClient,
) {
    fun search(query: String, type: MediaType?, page: Int = 1): Either<TmdbError, List<Candidate>> = either {
        val movies = if (type == null || type == MediaType.movie) {
            tmdbClient.searchMovies(query, Lang.en, page).bind().first.map { it.toCandidate() }
        } else emptyList()
        val shows = if (type == null || type == MediaType.tvshow) {
            tmdbClient.searchTvShows(query, Lang.en, page).bind().first.map { it.toCandidate() }
        } else emptyList()
        movies + shows
    }

    fun resolve(title: String, year: Int?, type: MediaType?): Either<TmdbError, MatchResult> = either {
        val firstPage = search(title, type, 1).bind()
        val result = TitleMatcher.match(title, year, firstPage)
        if (result !is MatchResult.NotFound || firstPage.isEmpty()) return@either result

        // Common titles ("Home", "Heat") can push the right one off page 1.
        val secondPage = search(title, type, 2).bind()
        if (secondPage.isEmpty()) result else TitleMatcher.match(title, year, firstPage + secondPage)
    }
}

internal fun TmdbMovieResponse.toCandidate() = Candidate(
    tmdbId = id,
    type = MediaType.movie,
    title = title,
    originalTitle = originalTitle,
    year = releaseDate?.take(4)?.toIntOrNull(),
    overview = overview,
    posterPath = posterPath,
    tmdbVoteAverage = voteAverage,
)

internal fun TmdbTvShowResponse.toCandidate() = Candidate(
    tmdbId = id,
    type = MediaType.tvshow,
    title = name,
    originalTitle = originalName,
    year = firstAirDate?.take(4)?.toIntOrNull(),
    overview = overview,
    posterPath = posterPath,
    tmdbVoteAverage = voteAverage,
)
