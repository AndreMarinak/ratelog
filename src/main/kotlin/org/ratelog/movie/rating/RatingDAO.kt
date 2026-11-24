package org.ratelog.movie.rating

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

/** Category columns are null for a quick rating (score only). */
@Table("movie_ratings")
data class RatingEntity(
    @Id val id: Long? = null,
    @Column("movie_id") val movieId: Long,
    @Column("user_id") val userId: Long,
    val directing: Double?,
    val cinematography: Double?,
    val acting: Double?,
    val soundtrack: Double?,
    val screenplay: Double?,
    @Column("created_at_epoch_ms") val createdAtEpochMs: Long,
    val score: Double?,
    @Column("review_title") val reviewTitle: String?,
    @Column("review_text") val reviewText: String?,
)

data class RatedMovieRow(
    val id: Long?,
    val movieId: Long,
    val userId: Long,
    val directing: Double?,
    val cinematography: Double?,
    val acting: Double?,
    val soundtrack: Double?,
    val screenplay: Double?,
    val createdAtEpochMs: Long,
    val score: Double?,
    val reviewTitle: String?,
    val reviewText: String?,
    val rank: Long,
)

@Repository
interface RatingDAO : CrudRepository<RatingEntity, Long> {
    fun findFirstByMovieIdAndUserId(movieId: Long, userId: Long): RatingEntity?

    /**
     * Ranked by score, or by one category when `ratingCategory` is set. Quick ratings have no
     * categories, so they are left out of a category ranking (they still rank by overall score).
     * Fork (ranking scroll): ties rank by rating id (the earlier rating first) so pages of `limit` from `offset`
     * never repeat or skip a title; the Ratings tab loads 30 at a time as you scroll.
     */
    @Query(
        """
        WITH ranked AS (
            SELECT r.id, r.movie_id, r.user_id, r.directing, r.cinematography, r.acting,
                   r.soundtrack, r.screenplay, r.created_at_epoch_ms, r.score, r.review_title, r.review_text,
                   ROW_NUMBER() OVER (
                       ORDER BY CASE :ratingCategory
                           WHEN 'directing' THEN r.directing
                           WHEN 'cinematography' THEN r.cinematography
                           WHEN 'acting' THEN r.acting
                           WHEN 'soundtrack' THEN r.soundtrack
                           WHEN 'screenplay' THEN r.screenplay
                           ELSE r.score
                       END DESC NULLS LAST, r.id
                   ) AS rank
            FROM movie_ratings r
            WHERE r.user_id = :userId
              AND (CASE :ratingCategory
                       WHEN 'directing' THEN r.directing
                       WHEN 'cinematography' THEN r.cinematography
                       WHEN 'acting' THEN r.acting
                       WHEN 'soundtrack' THEN r.soundtrack
                       WHEN 'screenplay' THEN r.screenplay
                       ELSE 0
                   END) IS NOT NULL
        )
        SELECT ranked.*
        FROM ranked
        INNER JOIN movies m ON ranked.movie_id = m.id
        WHERE (:genreId IS NULL OR m.genres LIKE CONCAT('%', :genreId, '%'))
          AND (:name IS NULL OR LOWER(m.original_title) LIKE LOWER(CONCAT('%', :name, '%')))
          AND (:tagId IS NULL OR EXISTS (SELECT 1 FROM tag_assignments ta INNER JOIN tags tg ON tg.id = ta.tag_id
               WHERE ta.tag_id = :tagId AND tg.user_id = :userId AND ta.media_type = 'movie' AND ta.media_id = m.id))
        ORDER BY ranked.rank
        LIMIT :limit OFFSET :offset
        """
    )
    fun findRankedRows(userId: Long, genreId: String?, name: String?, limit: Int, ratingCategory: String?, tagId: Long?, offset: Int): List<RatedMovieRow>
}
