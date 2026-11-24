package org.ratelog.extras

import org.ratelog.MediaType
import org.ratelog.TmdbId
import java.time.Instant
import java.time.LocalDate

/** Extra TMDB metadata shown on title pages that Ratelog's own tables don't keep. */
data class TmdbExtras(
    /** English title, used for the Rotten Tomatoes search link. */
    val title: String?,
    /** Movie release date, or a show's last air date (falls back to its first air date). */
    val latestReleaseDate: LocalDate?,
    val voteAverage: Double?,
    val voteCount: Int?,
    /** Movie runtime, or a show's episode runtime. */
    val runtimeMinutes: Int?,
    val numberOfSeasons: Int?,
    val trailerYoutubeKey: String?,
    val imdbId: String?,
    /** Null only for rows cached before credits were fetched (2026-11-24); a fetch always sets it. */
    val credits: TitleCredits? = null,
)

/** Third-party scores (via MDBList). Any of them can be missing. */
data class ExternalScores(
    val imdbId: String? = null,
    /** 0-10 */
    val imdbRating: Double? = null,
    val imdbVotes: Long? = null,
    /** Rotten Tomatoes critics (Tomatometer), 0-100 */
    val rtCritics: Int? = null,
    /** Rotten Tomatoes audience (Popcornmeter), 0-100 */
    val rtAudience: Int? = null,
    /** Metascore, 0-100 */
    val metacritic: Int? = null,
    /** 0-5 */
    val letterboxd: Double? = null,
) {
    fun hasAnyScore(): Boolean =
        imdbRating != null || rtCritics != null || rtAudience != null || metacritic != null || letterboxd != null
}

/** One cached row of `title_extras`. Each half is null until it has been fetched successfully. */
data class TitleExtrasRecord(
    val mediaType: MediaType,
    val tmdbId: TmdbId,
    val imdbId: String?,
    val tmdb: TmdbExtras?,
    val tmdbFetchedAt: Instant?,
    /** Null until the first TMDB attempt; set after failures too, so they are not retried on every view. */
    val tmdbNextRefreshAt: Instant?,
    val scores: ExternalScores?,
    val scoresNoData: Boolean,
    val scoresFetchedAt: Instant?,
    /** Null until the first MDBList attempt. */
    val scoresNextRefreshAt: Instant?,
)

interface TitleExtrasRepository {
    fun find(type: MediaType, tmdbId: TmdbId): TitleExtrasRecord?

    fun saveTmdb(type: MediaType, tmdbId: TmdbId, extras: TmdbExtras, fetchedAt: Instant, nextRefreshAt: Instant)

    /** `scores == null` stores the "no data" marker (and clears old scores). */
    fun saveScores(type: MediaType, tmdbId: TmdbId, scores: ExternalScores?, fetchedAt: Instant, nextRefreshAt: Instant)

    /** After a failed TMDB fetch: keep what is cached, try again later. */
    fun deferTmdb(type: MediaType, tmdbId: TmdbId, nextRefreshAt: Instant)

    /** After a failed MDBList fetch: keep what is cached, try again later. */
    fun deferScores(type: MediaType, tmdbId: TmdbId, nextRefreshAt: Instant)
}
