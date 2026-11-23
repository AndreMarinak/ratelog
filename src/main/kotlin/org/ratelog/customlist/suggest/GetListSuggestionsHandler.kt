package org.ratelog.customlist.suggest

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import jakarta.annotation.PreDestroy
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.browse.BrowseLibraryRepository
import org.ratelog.browse.LibraryMark
import org.ratelog.browse.TtlCache
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListRepository
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TitleCollection
import org.ratelog.recommendations.TmdbRecommendationsClient
import org.ratelog.recommendations.TmdbRecommendationsError
import org.ratelog.user.User
import org.ratelog.watchlist.seen.SeenState
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.security.MessageDigest
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

data class GetListSuggestions(
    val listId: CustomList.Id,
    val userId: User.Id,
    /** Metadata language: the titles' names. */
    val lang: Lang,
    /** Titles the row has already shown (or the viewer hid): "More suggestions" sends them so the next batch is new. */
    val skip: Set<TitleKey> = emptySet(),
    val limit: Int = GetListSuggestionsHandler.BATCH_SIZE,
)

data class ListSuggestion(
    val title: RelatedTitle,
    /** The viewer's status and score, null when they have neither. */
    val mark: LibraryMark?,
    val reason: SuggestionReason,
    val recommendedBy: Int,
) {
    /** The tiles' Seen rule: movies rated or Watched; shows Watched, or rated with no status. */
    val seen: Boolean get() = mark != null && SeenState(mark.status, mark.rated, isTv = title.type == MediaType.tvshow).seen
}

data class ListSuggestions(
    val listId: CustomList.Id,
    val listName: String,
    /** Titles in the list. */
    val listSize: Int,
    /** How many of them the suggestions are based on (at most [SuggestionScoring.MAX_SEEDS]). */
    val basedOn: Int,
    val suggestions: List<ListSuggestion>,
    /** Pool titles left after this batch (not in the list, not skipped). */
    val remaining: Int,
    /** The request skipped something, so starting over would show titles again. */
    val skipped: Boolean,
) {
    /** Nothing left to show: everything was in the list or skipped. */
    val exhausted: Boolean get() = suggestions.isEmpty()
}

sealed interface GetListSuggestionsError {
    data object ListNotFound : GetListSuggestionsError

    /** Suggestions are for adding, so only the list's owner gets them (official lists included). */
    data object NotOwner : GetListSuggestionsError
    data object TmdbNotConfigured : GetListSuggestionsError
    data object TmdbUnavailable : GetListSuggestionsError
}

/**
 * "Suggested for this list" on the owner's list page (and `GET /api/v1/lists/{id}/suggestions`): titles that fit the
 * list, from the TMDB recommendations of up to [SuggestionScoring.MAX_SEEDS] of its titles and the series they belong
 * to, scored by [SuggestionScoring].
 *
 * TMDB calls: one recommendations call per seed (a movie's also says which collection it is in) and one per collection,
 * at most [SuggestionScoring.MAX_COLLECTIONS]; all cached by [TmdbRecommendationsClient] (a day, collections a week), run
 * at most [parallel] at a time. The scored pool is cached per list and language for [cacheHours]; any change to the list
 * (a title added, removed or moved) changes its signature and rebuilds it. Pools built while TMDB was failing aren't kept.
 * Each request then costs two list queries and one marks query per media type in the batch.
 */
@Service
class GetListSuggestionsHandler(
    private val customLists: CustomListRepository,
    private val listTitles: ListTitlesRepository,
    private val client: TmdbRecommendationsClient,
    private val library: BrowseLibraryRepository,
    @Value("\${ratelog.list-suggestions.cache-hours:6}") cacheHours: Long = 6,
    @Value("\${ratelog.list-suggestions.parallel:4}") parallel: Int = 4,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val logger = LoggerFactory.getLogger(GetListSuggestionsHandler::class.java)

    private data class CachedPool(val signature: String, val entries: List<PoolEntry>, val seeds: Int)

    private val pools = TtlCache<String, CachedPool>(Duration.ofHours(cacheHours), maxEntries = 500, clock)

    /** Null runs the TMDB calls one after another on the caller's thread (`parallel: 1`). */
    private val pool: ExecutorService? = if (parallel > 1) newPool(parallel) else null

    fun handle(query: GetListSuggestions): Either<GetListSuggestionsError, ListSuggestions> {
        val list = customLists.findById(query.listId) ?: return GetListSuggestionsError.ListNotFound.left()
        if (list.userId != query.userId) return GetListSuggestionsError.NotOwner.left()

        val titles = listTitles.findTitles(query.listId)
        if (titles.isEmpty()) {
            return ListSuggestions(query.listId, list.name.value, 0, 0, emptyList(), remaining = 0, skipped = query.skip.isNotEmpty()).right()
        }

        val cached = poolFor(query.listId, query.lang, titles).fold({ return it.left() }, { it })
        val inList = titles.map { it.key }.toSet()
        val visible = cached.entries.filter { it.key !in inList && it.key !in query.skip }
        val batch = visible.take(query.limit.coerceIn(1, MAX_LIMIT))

        val marks = batch.groupBy { it.title.type }.flatMap { (type, entries) ->
            library.findMarks(query.userId, type, entries.map { it.title.tmdbId }).map { (tmdbId, mark) -> TitleKey(type, tmdbId) to mark }
        }.toMap()

        return ListSuggestions(
            listId = query.listId,
            listName = list.name.value,
            listSize = titles.size,
            basedOn = cached.seeds,
            suggestions = batch.map { ListSuggestion(it.title, marks[it.key], it.reason, it.recommendedBy) },
            remaining = visible.size - batch.size,
            skipped = query.skip.isNotEmpty(),
        ).right()
    }

    private fun poolFor(listId: CustomList.Id, lang: Lang, titles: List<ListTitle>): Either<GetListSuggestionsError, CachedPool> {
        val key = "${listId.value}|${lang.name}"
        val signature = signature(titles)
        pools.get(key)?.takeIf { it.signature == signature }?.let { return it.right() }

        val seeds = SuggestionScoring.pickSeeds(titles)
        val answers = inParallel(seeds) { seed -> client.recommendations(seed.type, seed.tmdbId, lang) }
        val found = seeds.zip(answers).mapNotNull { (seed, answer) ->
            answer.getOrNull()?.let { SeedRecommendations(seed, it.titles, it.collection?.takeIf { seed.type == MediaType.movie }) }
        }
        val errors = answers.mapNotNull { it.leftOrNull() }
        if (found.isEmpty() && errors.isNotEmpty() && errors.none { it == TmdbRecommendationsError.NotFound }) {
            return (if (TmdbRecommendationsError.ApiKeyMissing in errors) GetListSuggestionsError.TmdbNotConfigured else GetListSuggestionsError.TmdbUnavailable).left()
        }

        val refs = SuggestionScoring.collectionsToFetch(found)
        val collectionAnswers = inParallel(refs) { ref -> client.collection(ref.id, lang) }
        val collections: List<TitleCollection> = collectionAnswers.mapNotNull { it.getOrNull() }

        val entries = SuggestionScoring.rank(found, collections, titles, LocalDate.now(clock))
        val built = CachedPool(signature, entries, found.size)
        val failed = (errors + collectionAnswers.mapNotNull { it.leftOrNull() }).any { it == TmdbRecommendationsError.Unavailable }
        if (!failed) pools.put(key, built)
        return built.right()
    }

    /** [call] for every input, at most [pool]'s size at once; a call that throws counts as TMDB being unavailable. */
    private fun <I, T> inParallel(
        inputs: List<I>,
        call: (I) -> Either<TmdbRecommendationsError, T>,
    ): List<Either<TmdbRecommendationsError, T>> {
        val executor = pool
        if (executor == null || inputs.size <= 1) return inputs.map { safely { call(it) } }
        val futures = inputs.map { input ->
            try {
                CompletableFuture.supplyAsync({ safely { call(input) } }, executor)
            } catch (e: RejectedExecutionException) {
                CompletableFuture.completedFuture(safely { call(input) })
            }
        }
        return futures.map { future ->
            try {
                future.get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                TmdbRecommendationsError.Unavailable.left()
            } catch (e: Exception) {
                TmdbRecommendationsError.Unavailable.left()
            }
        }
    }

    /** Never logs the message: TMDB errors can carry the request URL (and its api_key). */
    private fun <T> safely(call: () -> Either<TmdbRecommendationsError, T>): Either<TmdbRecommendationsError, T> = try {
        call()
    } catch (e: Exception) {
        logger.warn("List suggestions TMDB call failed: {}", e.javaClass.simpleName)
        TmdbRecommendationsError.Unavailable.left()
    }

    @PreDestroy
    fun shutdown() {
        pool?.shutdownNow()
    }

    companion object {
        /** Tiles per batch: more than fit on a desktop row (about 8), so the row scrolls. */
        const val BATCH_SIZE = 20
        const val MAX_LIMIT = 50

        /** Titles, their order and the newest additions: everything the seeds and the exclusions depend on. */
        fun signature(titles: List<ListTitle>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            titles.forEach { digest.update("${it.itemId}:${it.type.name}:${it.tmdbId}:${it.position};".toByteArray()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Bounded both ways, like Discover's row pool: [threads] calls at once and a short queue. */
        private fun newPool(threads: Int): ExecutorService {
            val counter = AtomicInteger()
            return ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, LinkedBlockingQueue(threads * 8)) { runnable ->
                Thread(runnable, "list-suggestions-${counter.incrementAndGet()}").apply { isDaemon = true }
            }.apply { allowCoreThreadTimeOut(true) }
        }
    }
}
