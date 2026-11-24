package org.ratelog.tile

import org.ratelog.MediaType
import org.ratelog.user.User
import org.springframework.stereotype.Repository

/** A title as poster tiles know it: its type and TMDB id (the local ids differ per type). */
data class TileKey(val type: MediaType, val tmdbId: Int)

/** Account-wide marks for poster tiles that the per-page tile queries don't carry. */
interface TileMarksRepository {
    /** Every title in at least one of the user's own lists. */
    fun findListedTitles(userId: User.Id): Set<TileKey>

    /** TMDB ids of the movies the user rated in detail (all five categories). */
    fun findDetailedMovieRatings(userId: User.Id): Set<Int>

    /** Fork: TMDB ids of the shows with at least one rated season. */
    fun findSeasonRatedShows(userId: User.Id): Set<Int> = emptySet()
}

@Repository
class TileMarksRepositoryImpl(
    private val dao: TileMarksDAO,
) : TileMarksRepository {
    override fun findListedTitles(userId: User.Id): Set<TileKey> =
        dao.findListedTitles(userId.value).mapNotNull { row ->
            MediaType.entries.find { it.name == row.mediaType }?.let { TileKey(it, row.tmdbId) }
        }.toSet()

    override fun findDetailedMovieRatings(userId: User.Id): Set<Int> =
        dao.findDetailedMovieRatings(userId.value).map { it.tmdbId }.toSet()

    override fun findSeasonRatedShows(userId: User.Id): Set<Int> =
        dao.findSeasonRatedShows(userId.value).map { it.tmdbId }.toSet()
}
