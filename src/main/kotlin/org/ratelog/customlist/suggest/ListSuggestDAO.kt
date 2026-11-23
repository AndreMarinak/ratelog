package org.ratelog.customlist.suggest

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

data class ListTitleRow(
    @Column("item_id") val itemId: Long,
    @Column("media_type") val mediaType: String,
    val position: Int,
    @Column("tmdb_id") val tmdbId: Int?,
)

class ListSuggestMarker

@SpringRepository
interface ListSuggestDAO : Repository<ListSuggestMarker, Long> {

    @Query(
        """
        SELECT i.id AS item_id, i.media_type AS media_type, i.position AS position,
               COALESCE(m.tmdb_id, t.tmdb_id) AS tmdb_id
        FROM custom_list_items i
        LEFT JOIN movies m ON i.media_type = 'movie' AND m.id = i.media_id
        LEFT JOIN tv t ON i.media_type = 'tvshow' AND t.id = i.media_id
        WHERE i.list_id = :listId
        ORDER BY i.position, i.id
        """
    )
    fun findTitles(listId: Long): List<ListTitleRow>
}
