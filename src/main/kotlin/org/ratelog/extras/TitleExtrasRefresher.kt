package org.ratelog.extras

import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.extras.mdblist.MdbListClient
import org.ratelog.extras.mdblist.MdbListError
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/** Fetches one title's extras from TMDB and/or MDBList and writes them (or a retry time) to the cache. */
@Component
class TitleExtrasRefresher(
    private val repository: TitleExtrasRepository,
    private val tmdbExtrasClient: TmdbExtrasClient,
    private val mdbListClient: MdbListClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun refresh(type: MediaType, tmdbId: TmdbId, tmdb: Boolean, scores: Boolean) {
        var releaseDate = repository.find(type, tmdbId)?.tmdb?.latestReleaseDate
        if (tmdb) {
            refreshTmdb(type, tmdbId)?.let { releaseDate = it.latestReleaseDate }
        }
        if (scores) {
            refreshScores(type, tmdbId, releaseDate)
        }
    }

    private fun refreshTmdb(type: MediaType, tmdbId: TmdbId): TmdbExtras? {
        val now = clock.instant()
        return tmdbExtrasClient.fetch(type, tmdbId).fold(
            {
                repository.deferTmdb(type, tmdbId, now + RefreshPolicy.FAILURE_RETRY)
                null
            },
            { extras ->
                repository.saveTmdb(type, tmdbId, extras, now, now + RefreshPolicy.TMDB_INTERVAL)
                extras
            },
        )
    }

    private fun refreshScores(type: MediaType, tmdbId: TmdbId, releaseDate: LocalDate?) {
        val now = clock.instant()
        mdbListClient.fetch(type, tmdbId).fold(
            { error ->
                when (error) {
                    // Nothing to record: either hidden, or retried once the pause is over.
                    MdbListError.NotConfigured, is MdbListError.RateLimited -> Unit
                    MdbListError.NotFound -> repository.saveScores(type, tmdbId, null, now, now + RefreshPolicy.NO_DATA_RETRY)
                    is MdbListError.Failed -> repository.deferScores(type, tmdbId, now + RefreshPolicy.FAILURE_RETRY)
                }
            },
            { scores ->
                val stored = scores.takeIf { it.hasAnyScore() }
                repository.saveScores(type, tmdbId, stored, now, RefreshPolicy.nextScoresRefresh(stored, releaseDate, now))
            },
        )
    }
}
