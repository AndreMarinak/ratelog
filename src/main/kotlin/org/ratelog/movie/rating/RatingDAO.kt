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
                       END DESC NULLS LAST
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
        ORDER BY ranked.rank
        LIMIT :limit
        """
    )
    fun findRankedRows(userId: Long, genreId: String?, name: String?, limit: Int, ratingCategory: String?): List<RatedMovieRow>
}
