package org.ratelog.watchlist.seen

import org.ratelog.MediaType
import org.ratelog.toLocalDate
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Repository as SpringRepository

/** A stored title's TMDB id and release date (a show's first air date), for poster quick actions. */
data class TitleRelease(
    val tmdbId: Int,
    val releaseDate: java.time.LocalDate?,
)

/** Bulk lookups by local id: one query per media type, never one per title. */
interface TitleReleaseRepository {
    fun find(mediaType: MediaType, mediaIds: Collection<Long>): Map<Long, TitleRelease>
}

data class TitleReleaseRow(
    @Column("media_id") val mediaId: Long,
    @Column("tmdb_id") val tmdbId: Int,
    @Column("release_date") val releaseDate: String?,
)

class TitleReleaseMarker

@SpringRepository
interface TitleReleaseDAO : Repository<TitleReleaseMarker, Long> {
    @Query("SELECT id AS media_id, tmdb_id, release_date FROM movies WHERE id IN (:mediaIds)")
    fun findMovies(mediaIds: List<Long>): List<TitleReleaseRow>

    @Query("SELECT id AS media_id, tmdb_id, first_air_date AS release_date FROM tv WHERE id IN (:mediaIds)")
    fun findShows(mediaIds: List<Long>): List<TitleReleaseRow>
}

@SpringRepository
class TitleReleaseRepositoryImpl(
    private val dao: TitleReleaseDAO,
) : TitleReleaseRepository {
    override fun find(mediaType: MediaType, mediaIds: Collection<Long>): Map<Long, TitleRelease> {
        if (mediaIds.isEmpty()) return emptyMap()
        val ids = mediaIds.distinct()
        val rows = when (mediaType) {
            MediaType.movie -> dao.findMovies(ids)
            MediaType.tvshow -> dao.findShows(ids)
        }
        // Dates are stored as text (`2026-12-18`); a malformed one just means "no date".
        return rows.associate { row ->
            row.mediaId to TitleRelease(row.tmdbId, row.releaseDate?.let { runCatching { it.take(10).toLocalDate() }.getOrNull() })
        }
    }
}
