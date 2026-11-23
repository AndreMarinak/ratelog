package org.ratelog.tile

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class TileKeyRow(
    @Column("media_type") val mediaType: String,
    @Column("tmdb_id") val tmdbId: Int,
)

class TileMarksMarker

/*
 * Two whole-account lookups for poster tiles, each run at most once per page (TileMarks): which titles are in any of
 * the viewer's own lists, and which movies the viewer rated in detail (all five categories, so `directing` is set:
 * the DB CHECK constraints keep the five together).
 */
@SpringRepository
interface TileMarksDAO : Repository<TileMarksMarker, Long> {

    @Query(
        """
        SELECT DISTINCT i.media_type AS media_type, COALESCE(m.tmdb_id, t.tmdb_id) AS tmdb_id
        FROM custom_list_items i
        JOIN custom_lists l ON l.id = i.list_id
        LEFT JOIN movies m ON i.media_type = 'movie' AND m.id = i.media_id
        LEFT JOIN tv t ON i.media_type = 'tvshow' AND t.id = i.media_id
        WHERE l.user_id = :userId AND COALESCE(m.tmdb_id, t.tmdb_id) IS NOT NULL
        """
    )
    fun findListedTitles(userId: Long): List<TileKeyRow>

    @Query(
        """
        SELECT DISTINCT 'movie' AS media_type, m.tmdb_id AS tmdb_id
        FROM movie_ratings r
        JOIN movies m ON m.id = r.movie_id
        WHERE r.user_id = :userId AND r.directing IS NOT NULL
        """
    )
    fun findDetailedMovieRatings(userId: Long): List<TileKeyRow>
}
