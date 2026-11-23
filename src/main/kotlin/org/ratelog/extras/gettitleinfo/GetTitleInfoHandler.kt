package org.ratelog.extras.gettitleinfo

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.extras.ExternalScores
import org.ratelog.extras.ExtrasTaskRunner
import org.ratelog.extras.RefreshPolicy
import org.ratelog.extras.TitleExtrasRecord
import org.ratelog.extras.TitleExtrasRefresher
import org.ratelog.extras.TitleExtrasRepository
import org.ratelog.extras.awaitAtMost
import org.ratelog.extras.mdblist.MdbListClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class GetTitleInfo(
    val type: MediaType,
    val tmdbId: TmdbId,
)

/** What a title page (or the API) shows besides Ratelog's own data. */
data class TitleInfo(
    val type: MediaType,
    val tmdbId: Int,
    /** English title from TMDB. */
    val title: String?,
    val tmdbVoteAverage: Double?,
    val tmdbVoteCount: Int?,
    /** Movie runtime, or a show's episode runtime. */
    val runtimeMinutes: Int?,
    val numberOfSeasons: Int?,
    val trailerYoutubeKey: String?,
    val imdbId: String?,
    /** Null when MDBList is not configured, has nothing for the title, or hasn't answered yet. */
    val scores: ExternalScores?,
    val scoresFetchedAt: Instant?,
) {
    val trailerUrl: String? get() = trailerYoutubeKey?.let { "https://www.youtube.com/watch?v=$it" }
    val imdbUrl: String? get() = imdbId?.let { "https://www.imdb.com/title/$it/" }
}

sealed interface GetTitleInfoError {
    /** Nothing cached and nothing could be fetched in time. */
    data object NotAvailable : GetTitleInfoError
}

/**
 * Serves extras from the `title_extras` cache.
 * - Cached: returned as is; stale halves are refreshed in the background, so the page never waits.
 * - Never fetched: fetched now, waiting at most [syncTimeoutMs]; a slower answer still lands in the cache.
 * - No `MDBLIST_API_KEY`: scores are left out, nothing else changes.
 */
@Component
class GetTitleInfoHandler(
    private val repository: TitleExtrasRepository,
    private val refresher: TitleExtrasRefresher,
    private val runner: ExtrasTaskRunner,
    private val mdbListClient: MdbListClient,
    @Value("\${ratelog.extras.sync-timeout-ms:3000}") private val syncTimeoutMs: Long = 3000,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun handle(query: GetTitleInfo): Either<GetTitleInfoError, TitleInfo> = either {
        val scoresEnabled = mdbListClient.isConfigured()
        val key = "${query.type.name}:${query.tmdbId.value}"
        var record = repository.find(query.type, query.tmdbId)

        val firstTmdb = record?.tmdbNextRefreshAt == null
        val firstScores = scoresEnabled && record?.scoresNextRefreshAt == null && !mdbListClient.isPaused()
        if (firstTmdb || firstScores) {
            runner.submit(key) { refresher.refresh(query.type, query.tmdbId, firstTmdb, firstScores) }
                .awaitAtMost(Duration.ofMillis(syncTimeoutMs))
            record = repository.find(query.type, query.tmdbId)
        }

        val now = clock.instant()
        val staleTmdb = RefreshPolicy.isDue(record?.tmdbNextRefreshAt, now)
        val staleScores = scoresEnabled && !mdbListClient.isPaused() && RefreshPolicy.isDue(record?.scoresNextRefreshAt, now)
        if (staleTmdb || staleScores) {
            runner.submit(key) { refresher.refresh(query.type, query.tmdbId, staleTmdb, staleScores) }
        }

        ensureNotNull(record?.toTitleInfo(scoresEnabled)) { GetTitleInfoError.NotAvailable }
    }

    private fun TitleExtrasRecord.toTitleInfo(scoresEnabled: Boolean): TitleInfo? {
        val visibleScores = scores?.takeIf { scoresEnabled && !scoresNoData && it.hasAnyScore() }
        if (tmdb == null && visibleScores == null) return null
        return TitleInfo(
            type = mediaType,
            tmdbId = tmdbId.value,
            title = tmdb?.title,
            tmdbVoteAverage = tmdb?.voteAverage,
            tmdbVoteCount = tmdb?.voteCount,
            runtimeMinutes = tmdb?.runtimeMinutes,
            numberOfSeasons = tmdb?.numberOfSeasons,
            trailerYoutubeKey = tmdb?.trailerYoutubeKey,
            imdbId = imdbId,
            scores = visibleScores,
            scoresFetchedAt = scoresFetchedAt.takeIf { visibleScores != null },
        )
    }
}
