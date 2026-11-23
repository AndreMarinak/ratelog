package org.ratelog.import.letterboxd

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import jakarta.annotation.PreDestroy
import org.ratelog.tmdb.TmdbRateLimiter
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fork: runs Letterboxd imports in the background, within [LetterboxdImportLimits].
 *
 * The file is parsed during the upload request (format and size errors show at once); the TMDB lookups then run on a
 * small dedicated pool, so the upload returns right away (Railway closes requests idle for 5 minutes) and a big file
 * can't hold a request thread. One import per user, [LetterboxdImportLimits.MAX_CONCURRENT_IMPORTS] in total, and all
 * imports share one TMDB rate of [LetterboxdImportLimits.TMDB_REQUESTS_PER_SECOND].
 */
@Component
class LetterboxdImportRunner internal constructor(
    private val handler: LetterboxdImportHandler,
    private val executor: ExecutorService,
    private val budgetFactory: () -> ImportBudget,
) {
    @Autowired
    constructor(handler: LetterboxdImportHandler) : this(handler, newImportExecutor(), sharedBudgetFactory(Clock.systemUTC()))

    private val logger = LoggerFactory.getLogger(LetterboxdImportRunner::class.java)
    private val running = ConcurrentHashMap.newKeySet<User.Id>()

    /** Parses [csvContent] and queues the import; returns the number of rated rows queued. */
    fun start(userId: User.Id, csvContent: ByteArray): Either<LetterboxdImportError, Int> = either {
        val entries = handler.parse(csvContent).bind()
        ensure(running.add(userId)) { LetterboxdImportError.AlreadyRunning }
        try {
            executor.execute { run(userId, entries) }
        } catch (_: RejectedExecutionException) {
            running.remove(userId)
            raise(LetterboxdImportError.Busy)
        }
        entries.size
    }

    fun isRunning(userId: User.Id): Boolean = userId in running

    private fun run(userId: User.Id, entries: List<LetterboxdEntry>) {
        try {
            val result = handler.importEntries(entries, userId, budgetFactory())
            logger.info(
                "Letterboxd import for user {} finished: {} rows, {} movies, {} TV shows, {} duplicates, {} not found, {} errors, {} not processed",
                userId.value, entries.size, result.importedMovies, result.importedTvShows, result.skippedDuplicates,
                result.notFound.size, result.errors.size, result.notProcessed,
            )
        } catch (e: Exception) {
            logger.error("Letterboxd import for user {} failed: {}", userId.value, e.javaClass.simpleName)
        } finally {
            running.remove(userId)
        }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        private fun newImportExecutor(): ExecutorService {
            val count = AtomicInteger()
            // No queue: when every slot is busy the upload is refused with a "try again later" message.
            return ThreadPoolExecutor(
                LetterboxdImportLimits.MAX_CONCURRENT_IMPORTS, LetterboxdImportLimits.MAX_CONCURRENT_IMPORTS,
                0L, TimeUnit.MILLISECONDS, SynchronousQueue(),
            ) { task -> Thread(task, "letterboxd-import-${count.incrementAndGet()}").apply { isDaemon = true } }
        }

        /** One rate limiter shared by every import; each import gets its own deadline. */
        internal fun sharedBudgetFactory(clock: Clock): () -> ImportBudget {
            val limiter by lazy { TmdbRateLimiter(LetterboxdImportLimits.TMDB_REQUESTS_PER_SECOND) }
            return {
                val deadline = clock.millis() + LetterboxdImportLimits.TIME_BUDGET.toMillis()
                object : ImportBudget {
                    override fun beforeTmdbCall() = limiter.acquire()
                    override fun isExpired(): Boolean = clock.millis() >= deadline
                }
            }
        }
    }
}
