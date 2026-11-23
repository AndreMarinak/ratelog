package org.ratelog.browse.discover

import arrow.core.Either
import arrow.core.left
import jakarta.annotation.PreDestroy
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

data class GetDiscoverRows(
    val userId: User.Id,
    val lang: Lang,
    val region: WatchRegion,
    val type: MediaType,
    val hideSeen: Boolean,
)

/** A landing row. [tiles] is null when the page loads it itself (below the fold, or TMDB was too slow to wait for). */
data class DiscoverRowSlot(
    val spec: DiscoverRowSpec,
    val tiles: List<BrowseTile>?,
) {
    val pending: Boolean get() = tiles == null
}

data class DiscoverLanding(
    /** In display order. Loaded rows are never empty; rows that failed or came back empty are left out. */
    val rows: List<DiscoverRowSlot>,
    /** Set when every row the server tried failed; [rows] is then empty. */
    val error: BrowseError?,
)

/**
 * The Discover landing: "Popular now" and genre rows, each one page of [BrowseHandler] results (so "hide seen" and
 * its top-up from the next TMDB pages apply, and the TMDB pages are shared with the rows' "More" grids through
 * [org.ratelog.browse.TmdbBrowseClient]'s cache).
 *
 * Only the first [eagerRows] rows are loaded here, in parallel on a small pool ([parallelRows] threads, so a page
 * never fires more than that many TMDB calls at once), waiting at most [waitMs]. Rows still loading by then and the
 * rows further down are returned as pending: the page fetches them from `/discover/row` (see [row]) as they near the
 * viewport. A row that fails is dropped; the landing itself never fails.
 */
@Service
class GetDiscoverRowsHandler(
    private val browse: BrowseHandler,
    @Value("\${ratelog.discover.eager-rows:4}") private val eagerRows: Int = 4,
    @Value("\${ratelog.discover.parallel-rows:4}") parallelRows: Int = 4,
    @Value("\${ratelog.discover.wait-ms:2500}") private val waitMs: Long = 2500,
) {
    private val logger = LoggerFactory.getLogger(GetDiscoverRowsHandler::class.java)

    /** Null runs rows one after another on the caller's thread (`parallel-rows: 1`). */
    private val pool: ExecutorService? = if (parallelRows > 1) newPool(parallelRows) else null

    fun landing(query: GetDiscoverRows): DiscoverLanding {
        val specs = DiscoverRows.forType(query.type, query.hideSeen)
        val eager = specs.take(eagerRows.coerceAtLeast(0))
        val started = eager.map { spec -> spec to start { row(query, spec) } }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        val outcomes = started.map { (spec, future) -> spec to future?.let { await(it, deadline) } }

        val errors = outcomes.mapNotNull { it.second?.leftOrNull() }
        if (eager.isNotEmpty() && errors.size == eager.size) {
            return DiscoverLanding(emptyList(), errors.firstOrNull { it != BrowseError.TmdbUnavailable } ?: BrowseError.TmdbUnavailable)
        }

        val loaded = outcomes.mapNotNull { (spec, outcome) ->
            when (outcome) {
                null -> DiscoverRowSlot(spec, null)
                else -> outcome.getOrNull()?.takeIf { it.isNotEmpty() }?.let { DiscoverRowSlot(spec, it) }
            }
        }
        return DiscoverLanding(loaded + specs.drop(eager.size).map { DiscoverRowSlot(it, null) }, error = null)
    }

    /** One row's tiles (posters only, at most [ROW_LIMIT]); empty when everything was hidden. */
    fun row(query: GetDiscoverRows, spec: DiscoverRowSpec): Either<BrowseError, List<BrowseTile>> =
        browse.handle(BrowseQuery(query.userId, query.lang, query.region, spec.filters, page = 1))
            .map { result -> result.tiles.filter { it.title.posterPath != null }.take(ROW_LIMIT) }

    /** Null when the pool is saturated: the row is left for the page to fetch. */
    private fun start(task: () -> Either<BrowseError, List<BrowseTile>>): CompletableFuture<Either<BrowseError, List<BrowseTile>>>? {
        val executor = pool ?: return CompletableFuture.completedFuture(safely(task))
        return try {
            CompletableFuture.supplyAsync({ safely(task) }, executor)
        } catch (e: RejectedExecutionException) {
            null
        }
    }

    /** The outcome, or null if it isn't ready by [deadline] (it keeps running and warms the TMDB cache). */
    private fun await(
        future: CompletableFuture<Either<BrowseError, List<BrowseTile>>>,
        deadline: Long,
    ): Either<BrowseError, List<BrowseTile>>? = try {
        future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
    } catch (e: TimeoutException) {
        null
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        null
    } catch (e: ExecutionException) {
        BrowseError.TmdbUnavailable.left()
    }

    /** A bug or database error in one row drops that row instead of failing the page. Never logs messages (TMDB URLs carry the key). */
    private fun safely(task: () -> Either<BrowseError, List<BrowseTile>>): Either<BrowseError, List<BrowseTile>> = try {
        task()
    } catch (e: Exception) {
        logger.warn("Discover row failed: {}", e.javaClass.simpleName)
        BrowseError.TmdbUnavailable.left()
    }

    @PreDestroy
    fun shutdown() {
        pool?.shutdownNow()
    }

    companion object {
        const val ROW_LIMIT = 20

        /** Bounded both ways: [threads] rows at once, and a short queue so a burst of page loads can't pile up work. */
        private fun newPool(threads: Int): ExecutorService {
            val counter = AtomicInteger()
            return ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, LinkedBlockingQueue(threads * 8)) { runnable ->
                Thread(runnable, "discover-rows-${counter.incrementAndGet()}").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }
        }
    }
}
