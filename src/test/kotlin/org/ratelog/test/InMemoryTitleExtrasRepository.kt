package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.extras.ExternalScores
import org.ratelog.extras.TitleExtrasRecord
import org.ratelog.extras.TitleExtrasRepository
import org.ratelog.extras.TmdbExtras
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Mirrors the SQL upserts: the TMDB and scores halves are written independently, imdb id is never cleared. */
class InMemoryTitleExtrasRepository : TitleExtrasRepository {
    private val store = ConcurrentHashMap<Pair<MediaType, Int>, TitleExtrasRecord>()

    override fun find(type: MediaType, tmdbId: TmdbId): TitleExtrasRecord? = store[type to tmdbId.value]

    override fun saveTmdb(type: MediaType, tmdbId: TmdbId, extras: TmdbExtras, fetchedAt: Instant, nextRefreshAt: Instant) {
        update(type, tmdbId) {
            val imdbId = extras.imdbId ?: it.imdbId
            it.copy(
                imdbId = imdbId,
                tmdb = extras.copy(imdbId = imdbId),
                tmdbFetchedAt = fetchedAt,
                tmdbNextRefreshAt = nextRefreshAt,
                scores = it.scores?.copy(imdbId = imdbId),
            )
        }
    }

    override fun saveScores(type: MediaType, tmdbId: TmdbId, scores: ExternalScores?, fetchedAt: Instant, nextRefreshAt: Instant) {
        update(type, tmdbId) {
            val imdbId = scores?.imdbId ?: it.imdbId
            it.copy(
                imdbId = imdbId,
                tmdb = it.tmdb?.copy(imdbId = imdbId),
                scores = scores?.copy(imdbId = imdbId),
                scoresNoData = scores == null,
                scoresFetchedAt = fetchedAt,
                scoresNextRefreshAt = nextRefreshAt,
            )
        }
    }

    override fun deferTmdb(type: MediaType, tmdbId: TmdbId, nextRefreshAt: Instant) {
        update(type, tmdbId) { it.copy(tmdbNextRefreshAt = nextRefreshAt) }
    }

    override fun deferScores(type: MediaType, tmdbId: TmdbId, nextRefreshAt: Instant) {
        update(type, tmdbId) { it.copy(scoresNextRefreshAt = nextRefreshAt) }
    }

    fun count(): Int = store.size

    private fun update(type: MediaType, tmdbId: TmdbId, change: (TitleExtrasRecord) -> TitleExtrasRecord) {
        store.compute(type to tmdbId.value) { _, existing -> change(existing ?: empty(type, tmdbId)) }
    }

    private fun empty(type: MediaType, tmdbId: TmdbId) = TitleExtrasRecord(
        mediaType = type,
        tmdbId = tmdbId,
        imdbId = null,
        tmdb = null,
        tmdbFetchedAt = null,
        tmdbNextRefreshAt = null,
        scores = null,
        scoresNoData = false,
        scoresFetchedAt = null,
        scoresNextRefreshAt = null,
    )
}
