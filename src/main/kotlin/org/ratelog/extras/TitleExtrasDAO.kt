package org.ratelog.extras

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.Optional

@Table("title_extras")
data class TitleExtrasEntity(
    @Id val id: Long? = null,
    @Column("media_type") val mediaType: String,
    @Column("tmdb_id") val tmdbId: Int,
    @Column("imdb_id") val imdbId: String?,
    val title: String?,
    @Column("latest_release_date") val latestReleaseDate: LocalDate?,
    @Column("tmdb_vote_average") val tmdbVoteAverage: Double?,
    @Column("tmdb_vote_count") val tmdbVoteCount: Int?,
    @Column("runtime_minutes") val runtimeMinutes: Int?,
    @Column("number_of_seasons") val numberOfSeasons: Int?,
    @Column("trailer_youtube_key") val trailerYoutubeKey: String?,
    @Column("tmdb_fetched_at_epoch_ms") val tmdbFetchedAtEpochMs: Long?,
    @Column("tmdb_next_refresh_at_epoch_ms") val tmdbNextRefreshAtEpochMs: Long?,
    @Column("imdb_rating") val imdbRating: Double?,
    @Column("imdb_votes") val imdbVotes: Long?,
    @Column("rt_critics") val rtCritics: Int?,
    @Column("rt_audience") val rtAudience: Int?,
    val metacritic: Int?,
    val letterboxd: Double?,
    @Column("scores_no_data") val scoresNoData: Boolean,
    @Column("scores_fetched_at_epoch_ms") val scoresFetchedAtEpochMs: Long?,
    @Column("scores_next_refresh_at_epoch_ms") val scoresNextRefreshAtEpochMs: Long?,
)

/**
 * The TMDB and MDBList halves of a row are written by separate upserts, so a background refresh of one
 * never overwrites the other, and two first views of the same title can't insert duplicate rows.
 */
@Repository
interface TitleExtrasDAO : CrudRepository<TitleExtrasEntity, Long> {

    @Query("SELECT * FROM title_extras WHERE media_type = :mediaType AND tmdb_id = :tmdbId")
    fun findByMediaTypeAndTmdbId(mediaType: String, tmdbId: Int): Optional<TitleExtrasEntity>

    @Modifying
    @Query(
        """
        INSERT INTO title_extras (media_type, tmdb_id, imdb_id, title, latest_release_date, tmdb_vote_average,
            tmdb_vote_count, runtime_minutes, number_of_seasons, trailer_youtube_key,
            tmdb_fetched_at_epoch_ms, tmdb_next_refresh_at_epoch_ms)
        VALUES (:mediaType, :tmdbId, :imdbId, :title, :latestReleaseDate, :voteAverage,
            :voteCount, :runtimeMinutes, :numberOfSeasons, :trailerYoutubeKey,
            :fetchedAtEpochMs, :nextRefreshAtEpochMs)
        ON CONFLICT (media_type, tmdb_id) DO UPDATE SET
            imdb_id = COALESCE(EXCLUDED.imdb_id, title_extras.imdb_id),
            title = EXCLUDED.title,
            latest_release_date = EXCLUDED.latest_release_date,
            tmdb_vote_average = EXCLUDED.tmdb_vote_average,
            tmdb_vote_count = EXCLUDED.tmdb_vote_count,
            runtime_minutes = EXCLUDED.runtime_minutes,
            number_of_seasons = EXCLUDED.number_of_seasons,
            trailer_youtube_key = EXCLUDED.trailer_youtube_key,
            tmdb_fetched_at_epoch_ms = EXCLUDED.tmdb_fetched_at_epoch_ms,
            tmdb_next_refresh_at_epoch_ms = EXCLUDED.tmdb_next_refresh_at_epoch_ms
        """
    )
    fun upsertTmdb(
        mediaType: String,
        tmdbId: Int,
        imdbId: String?,
        title: String?,
        latestReleaseDate: LocalDate?,
        voteAverage: Double?,
        voteCount: Int?,
        runtimeMinutes: Int?,
        numberOfSeasons: Int?,
        trailerYoutubeKey: String?,
        fetchedAtEpochMs: Long,
        nextRefreshAtEpochMs: Long,
    )

    @Modifying
    @Query(
        """
        INSERT INTO title_extras (media_type, tmdb_id, imdb_id, imdb_rating, imdb_votes, rt_critics, rt_audience,
            metacritic, letterboxd, scores_no_data, scores_fetched_at_epoch_ms, scores_next_refresh_at_epoch_ms)
        VALUES (:mediaType, :tmdbId, :imdbId, :imdbRating, :imdbVotes, :rtCritics, :rtAudience,
            :metacritic, :letterboxd, :noData, :fetchedAtEpochMs, :nextRefreshAtEpochMs)
        ON CONFLICT (media_type, tmdb_id) DO UPDATE SET
            imdb_id = COALESCE(EXCLUDED.imdb_id, title_extras.imdb_id),
            imdb_rating = EXCLUDED.imdb_rating,
            imdb_votes = EXCLUDED.imdb_votes,
            rt_critics = EXCLUDED.rt_critics,
            rt_audience = EXCLUDED.rt_audience,
            metacritic = EXCLUDED.metacritic,
            letterboxd = EXCLUDED.letterboxd,
            scores_no_data = EXCLUDED.scores_no_data,
            scores_fetched_at_epoch_ms = EXCLUDED.scores_fetched_at_epoch_ms,
            scores_next_refresh_at_epoch_ms = EXCLUDED.scores_next_refresh_at_epoch_ms
        """
    )
    fun upsertScores(
        mediaType: String,
        tmdbId: Int,
        imdbId: String?,
        imdbRating: Double?,
        imdbVotes: Long?,
        rtCritics: Int?,
        rtAudience: Int?,
        metacritic: Int?,
        letterboxd: Double?,
        noData: Boolean,
        fetchedAtEpochMs: Long,
        nextRefreshAtEpochMs: Long,
    )

    @Modifying
    @Query(
        """
        INSERT INTO title_extras (media_type, tmdb_id, tmdb_next_refresh_at_epoch_ms)
        VALUES (:mediaType, :tmdbId, :nextRefreshAtEpochMs)
        ON CONFLICT (media_type, tmdb_id) DO UPDATE SET
            tmdb_next_refresh_at_epoch_ms = EXCLUDED.tmdb_next_refresh_at_epoch_ms
        """
    )
    fun deferTmdb(mediaType: String, tmdbId: Int, nextRefreshAtEpochMs: Long)

    @Modifying
    @Query(
        """
        INSERT INTO title_extras (media_type, tmdb_id, scores_next_refresh_at_epoch_ms)
        VALUES (:mediaType, :tmdbId, :nextRefreshAtEpochMs)
        ON CONFLICT (media_type, tmdb_id) DO UPDATE SET
            scores_next_refresh_at_epoch_ms = EXCLUDED.scores_next_refresh_at_epoch_ms
        """
    )
    fun deferScores(mediaType: String, tmdbId: Int, nextRefreshAtEpochMs: Long)
}
