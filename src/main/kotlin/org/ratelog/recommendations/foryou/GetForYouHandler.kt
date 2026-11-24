package org.ratelog.recommendations.foryou

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import jakarta.annotation.PreDestroy
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.browse.BrowseLibraryRepository
import org.ratelog.browse.TtlCache
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TmdbRecommendationsClient
import org.ratelog.recommendations.TmdbRecommendationsError
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class GetForYou(
    val userId: User.Id,
    /** Metadata language: titles. */
    val lang: Lang,
    /** Only this type (Discover follows its Movies / TV mode); null mixes movies and TV (Home). */
    val type: MediaType?,
    val limit: Int = ROW_LIMIT,
) {
    companion object {
        const val ROW_LIMIT = 20
    }
}

data class ForYou(
    /** The rated titles the picks come from, in seed order. Empty when the viewer has rated nothing (of that type). */
    val seeds: List<ForYouSeed>,
    /** Titles the viewer hasn't rated or given a status, at most the query's limit. */
    val picks: List<ForYouPick>,
)

sealed interface GetForYouError {
    data object TmdbNotConfigured : GetForYouError

    /** Every seed's TMDB recommendations failed. */
    data object TmdbUnavailable : GetForYouError
}

/**
 * "Recommended for you" on Home (movies and TV) and Discover (one type), and `GET /api/v1/recommendations`.
 * The rule is [ForYouRule]; this adds the data:
 *
 * - Seeds come from the viewer's ratings (at most two small queries per type).
 * - Each seed's recommendations come from [TmdbRecommendationsClient] (page 1, cached 24 h and shared with the title
 *   pages' "More like this"), fetched in parallel on a small pool. A seed TMDB doesn't know is skipped.
 * - The merged list is cached per viewer, type and language for `cacheMinutes` (only when every seed answered), so
 *   page views cost no TMDB calls, just one marks query per type.
 * - The viewer's marks are always read fresh: anything rated, Watched, Watching, Dropped or on the watchlist is left
 *   out (the watchlist and Watching titles already have their own Home rows), so a title rated from the row is gone
 *   on the next load.
 */
@Service
class GetForYouHandler(
    private val seeds: ForYouSeedRepository,
    private val client: TmdbRecommendationsClient,
    private val library: BrowseLibraryRepository,
    @Value("\${ratelog.recommendations.for-you-cache-minutes:5}") cacheMinutes: Long = 5,
    @Value("\${ratelog.recommendations.for-you-parallel:5}") parallel: Int = 5,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val logger = LoggerFactory.getLogger(GetForYouHandler::class.java)

    private data class Merged(val seeds: List<ForYouSeed>, val picks: List<ForYouPick>)

    private val cache = TtlCache<String, Merged>(Duration.ofMinutes(cacheMinutes), maxEntries = 500, clock)

    /** Null fetches seeds one after another on the caller's thread (parallel = 1, as in tests). */
    private val pool: ExecutorService? = if (parallel > 1) newPool(parallel) else null

    fun handle(query: GetForYou): Either<GetForYouError, ForYou> {
        val key = "${query.userId.value}|${query.type?.name ?: "all"}|${query.lang.name}"
        val merged = cache.get(key) ?: when (val loaded = load(query)) {
            is Either.Left -> return loaded.value.left()
            is Either.Right -> {
                val (value, complete) = loaded.value
                if (complete && value.seeds.isNotEmpty()) cache.put(key, value)
                value
            }
        }
        return ForYou(merged.seeds, unseen(query.userId, merged.picks).take(query.limit.coerceAtLeast(0))).right()
    }

    /** The merged picks, and whether every seed answered (partial results aren't cached, so the next view retries). */
    private fun load(query: GetForYou): Either<GetForYouError, Pair<Merged, Boolean>> {
        val types = query.type?.let(::listOf) ?: MediaType.entries
        val picked = ForYouRule.pickSeeds(
            high = types.associateWith { seeds.findRecentRatedAtLeast(query.userId, it, ForYouRule.HIGH_SCORE, query.lang, ForYouRule.MAX_SEEDS) },
            top = { types.associateWith { seeds.findTopRated(query.userId, it, query.lang, ForYouRule.MAX_SEEDS) } },
        )
        if (picked.isEmpty()) return (Merged(emptyList(), emptyList()) to true).right()

        val results = fetchAll(picked, query.lang)
        val answered = results.mapNotNull { (seed, result) ->
            when (result) {
                is Either.Right -> seed to result.value
                is Either.Left -> if (result.value == TmdbRecommendationsError.NotFound) seed to emptyList() else null
            }
        }
        if (answered.isEmpty()) {
            val notConfigured = results.any { it.second.leftOrNull() == TmdbRecommendationsError.ApiKeyMissing }
            return (if (notConfigured) GetForYouError.TmdbNotConfigured else GetForYouError.TmdbUnavailable).left()
        }
        val merged = Merged(picked, ForYouRule.merge(answered, LocalDate.now(clock)))
        return (merged to (answered.size == picked.size)).right()
    }

    private fun fetchAll(picked: List<ForYouSeed>, lang: Lang): List<Pair<ForYouSeed, Either<TmdbRecommendationsError, List<RelatedTitle>>>> {
        val executor = pool ?: return picked.map { it to safely(it, lang) }
        val futures = picked.map { seed ->
            seed to try {
                CompletableFuture.supplyAsync({ safely(seed, lang) }, executor)
            } catch (e: RejectedExecutionException) {
                CompletableFuture.completedFuture(safely(seed, lang))
            }
        }
        return futures.map { (seed, future) ->
            val result: Either<TmdbRecommendationsError, List<RelatedTitle>> = try {
                future.get(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                TmdbRecommendationsError.Unavailable.left()
            } catch (e: Exception) {
                TmdbRecommendationsError.Unavailable.left()
            }
            seed to result
        }
    }

    /** Never logs messages: TMDB errors can carry the request URL (and its api_key). */
    private fun safely(seed: ForYouSeed, lang: Lang): Either<TmdbRecommendationsError, List<RelatedTitle>> = try {
        client.recommendations(seed.type, seed.tmdbId, lang).map { it.titles }
    } catch (e: Exception) {
        logger.warn("Recommendations for {} {} failed: {}", seed.type, seed.tmdbId, e.javaClass.simpleName)
        TmdbRecommendationsError.Unavailable.left()
    }

    /** Drops every title the viewer has a rating or any status on (one query per type). */
    private fun unseen(userId: User.Id, picks: List<ForYouPick>): List<ForYouPick> {
        val marked = picks.groupBy { it.title.type }.mapValues { (type, ofType) ->
            library.findMarks(userId, type, ofType.map { it.title.tmdbId }).keys
        }
        return picks.filter { it.title.tmdbId !in marked[it.title.type].orEmpty() }
    }

    @PreDestroy
    fun shutdown() {
        pool?.shutdownNow()
    }

    companion object {
        /** Each TMDB call has a 6 s read timeout; this only guards against a stuck pool. */
        const val FETCH_TIMEOUT_SECONDS = 12L

        private fun newPool(threads: Int): ExecutorService {
            val counter = AtomicInteger()
            return ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, LinkedBlockingQueue(threads * 8)) { runnable ->
                Thread(runnable, "for-you-${counter.incrementAndGet()}").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }
        }
    }
}
