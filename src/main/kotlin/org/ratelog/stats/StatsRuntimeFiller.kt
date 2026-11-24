package org.ratelog.stats

import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.config.istmdbconfigured.IsTmdbConfiguredHandler
import org.ratelog.config.istmdbconfigured.IsTmdbConfiguredQuery
import org.ratelog.extras.ExtrasTaskRunner
import org.ratelog.extras.TitleExtrasRefresher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Looks up missing movie runtimes for the Stats page's hours, in the background. */
fun interface RuntimeFiller {
    /** Queues lookups for (some of) [tmdbIds]; never waits for them. Returns how many were queued. */
    fun fill(tmdbIds: List<Int>): Int
}

/**
 * Runtimes live in the title_extras cache (V1080), filled when a title page is opened. Movies you rated without ever
 * opening their page have none, so a Stats view queues up to [BATCH] of them on the extras pool (2 threads, the same
 * one title pages use, same `type:tmdbId` key so a page view joins a running lookup) and renders right away with
 * "based on N of M movies"; the next view counts them. Only the TMDB half is fetched (no MDBList call). A failed lookup
 * is retried after RefreshPolicy.FAILURE_RETRY, never on every view.
 */
@Component
class StatsRuntimeFiller(
    private val runner: ExtrasTaskRunner,
    private val refresher: TitleExtrasRefresher,
    private val tmdbConfigured: IsTmdbConfiguredHandler,
) : RuntimeFiller {
    private val logger = LoggerFactory.getLogger(StatsRuntimeFiller::class.java)

    override fun fill(tmdbIds: List<Int>): Int {
        if (tmdbIds.isEmpty()) return 0
        val configured = try {
            tmdbConfigured.handle(IsTmdbConfiguredQuery).fold({ false }, { it })
        } catch (e: Exception) {
            logger.warn("Stats runtime lookups skipped: {}", e.javaClass.simpleName)
            false
        }
        if (!configured) return 0
        val batch = tmdbIds.distinct().take(BATCH)
        batch.forEach { id ->
            runner.submit("${MediaType.movie.name}:$id") {
                refresher.refresh(MediaType.movie, TmdbId(id), tmdb = true, scores = false)
            }
        }
        return batch.size
    }

    companion object {
        const val BATCH = 40
    }
}
