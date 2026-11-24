package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.browse.BrowseLibraryRepository
import org.ratelog.browse.LibraryMark
import org.ratelog.user.User

/** Mirrors BrowseLibraryDAO's queries on top of the other in-memory fakes (ratings and statuses by local id). */
class InMemoryBrowseLibraryRepository(
    val movies: InMemoryMovieRepository = InMemoryMovieRepository(),
    val ratings: InMemoryRatingRepository = InMemoryRatingRepository(),
    val shows: InMemoryTvShowRepository = InMemoryTvShowRepository(),
    val tvRatings: InMemoryTvRatingRepository = InMemoryTvRatingRepository(),
    val statuses: InMemoryMediaStatusRepository = InMemoryMediaStatusRepository(),
) : BrowseLibraryRepository {
    /** Every call, to check lookups are done in bulk. */
    val calls = mutableListOf<List<Int>>()

    override fun findMarks(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>): Map<Int, LibraryMark> {
        val ids = tmdbIds.distinct()
        calls += ids
        return ids.mapNotNull { tmdbId ->
            val mark = when (type) {
                MediaType.movie -> movies.findByTmdbId(TmdbId(tmdbId))?.let { movie ->
                    val rating = ratings.findByMovieIdAndUserId(movie.id!!, userId)
                    LibraryMark(
                        score = rating?.score?.value,
                        rated = rating != null,
                        status = statuses.find(userId, MediaType.movie, movie.id.value)?.status,
                    )
                }
                MediaType.tvshow -> shows.findByTmdbId(TmdbId(tmdbId))?.let { show ->
                    val rating = tvRatings.findByTvShowIdAndUserId(show.id!!, userId)
                    val rated = rating != null && rating.isRated
                    LibraryMark(
                        score = rating?.score?.value?.takeIf { rated },
                        rated = rated,
                        status = statuses.find(userId, MediaType.tvshow, show.id.value)?.status,
                    )
                }
            }
            mark?.takeIf { it.rated || it.status != null }?.let { tmdbId to it }
        }.toMap()
    }
}
