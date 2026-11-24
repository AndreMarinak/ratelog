package org.ratelog.browse.discover

import arrow.core.Either
import org.ratelog.browse.BrowseLibraryRepository
import org.ratelog.browse.BrowseTitle
import org.ratelog.home.DiscoverError
import org.ratelog.home.DiscoverFeed
import org.ratelog.home.DiscoverTitle
import org.ratelog.home.TmdbDiscoverClient
import org.springframework.stereotype.Component

/**
 * Fork: Discover rows that show a fixed TMDB list ([DiscoverRowSpec.feed]) instead of a filtered grid: Classics, moved
 * from Home on 2026-11-23. The list is [TmdbDiscoverClient]'s (cached an hour, shared by every viewer); the viewer's
 * marks are read in one query, and with "Hide titles I've seen" on, seen titles are left out like on the other rows.
 */
@Component
class DiscoverFeedRows(
    private val client: TmdbDiscoverClient,
    private val library: BrowseLibraryRepository,
) {
    fun tiles(query: GetDiscoverRows, feed: DiscoverFeed, hideSeen: Boolean): Either<BrowseError, List<BrowseTile>> =
        client.fetch(feed, query.lang, query.region)
            .mapLeft { error ->
                when (error) {
                    DiscoverError.NotConfigured -> BrowseError.TmdbNotConfigured
                    DiscoverError.Unavailable -> BrowseError.TmdbUnavailable
                }
            }
            .map { titles ->
                val candidates = titles.filter { it.posterPath != null }.distinctBy { it.tmdbId }
                val marks = library.findMarks(query.userId, feed.mediaType, candidates.map { it.tmdbId })
                candidates
                    .map { BrowseTile(it.toBrowseTitle(), marks[it.tmdbId]) }
                    .filter { !hideSeen || it.mark?.seen != true }
            }

    private fun DiscoverTitle.toBrowseTitle() = BrowseTitle(
        tmdbId = tmdbId,
        type = mediaType,
        title = title,
        releaseDate = releaseDate,
        posterPath = posterPath,
        voteAverage = voteAverage,
        voteCount = null,
        genreIds = emptyList(),
        originalLanguage = null,
    )
}
