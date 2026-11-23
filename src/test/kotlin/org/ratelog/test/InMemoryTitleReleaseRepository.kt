package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.movie.Movie
import org.ratelog.tvshow.TvShow
import org.ratelog.watchlist.seen.TitleRelease
import org.ratelog.watchlist.seen.TitleReleaseRepository
import java.util.concurrent.atomic.AtomicInteger

/** Release dates read from the in-memory movie and show fakes, counting lookups (one per media type is expected). */
class InMemoryTitleReleaseRepository(
    private val movies: InMemoryMovieRepository,
    private val shows: InMemoryTvShowRepository,
) : TitleReleaseRepository {
    val lookups = AtomicInteger()

    override fun find(mediaType: MediaType, mediaIds: Collection<Long>): Map<Long, TitleRelease> {
        if (mediaIds.isEmpty()) return emptyMap()
        lookups.incrementAndGet()
        return mediaIds.distinct().mapNotNull { id ->
            when (mediaType) {
                MediaType.movie -> movies.findById(Movie.Id(id))?.let { id to TitleRelease(it.tmdbId.value, it.releaseDate) }
                MediaType.tvshow -> shows.findById(TvShow.Id(id))?.let { id to TitleRelease(it.tmdbId.value, it.firstAirDate) }
            }
        }.toMap()
    }
}
