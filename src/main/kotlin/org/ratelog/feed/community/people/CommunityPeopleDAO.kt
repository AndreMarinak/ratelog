package org.ratelog.feed.community.people

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class CommunityPersonEntity(
    val id: Long,
    val username: String,
    val ratingCount: Long,
    val following: Boolean,
)

class CommunityPersonMarker

/**
 * Fork: every account except the viewer, A-Z (case-insensitive), with its number of ratings (movie ratings plus rated
 * seasons, the same rows the Community feed shows) and whether the viewer follows it. One query, no paging: a
 * self-hosted instance has a handful of accounts.
 */
@SpringRepository
interface CommunityPeopleDAO : Repository<CommunityPersonMarker, Long> {

    @Query(
        """
        SELECT
            u.id AS id,
            u.username AS username,
            (SELECT COUNT(*) FROM movie_ratings mr WHERE mr.user_id = u.id)
              + (SELECT COUNT(*) FROM season_ratings sr WHERE sr.user_id = u.id) AS rating_count,
            EXISTS (
                SELECT 1 FROM users_follows uf WHERE uf.follower_id = :viewerId AND uf.followed_id = u.id
            ) AS following
        FROM users u
        WHERE u.id <> :viewerId
        ORDER BY LOWER(u.username), u.id
        """
    )
    fun findAllExcept(viewerId: Long): List<CommunityPersonEntity>
}
