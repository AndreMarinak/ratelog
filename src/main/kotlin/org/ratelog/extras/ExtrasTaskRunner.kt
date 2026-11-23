package org.ratelog.extras

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small background pool for fetching extras, so detail pages never wait on a refresh.
 * One task per key at a time: a title viewed again while its refresh is running just joins that refresh.
 */
@Component
class ExtrasTaskRunner(private val executor: Executor) {

    @Autowired
    constructor() : this(defaultExecutor())

    private val logger = LoggerFactory.getLogger(ExtrasTaskRunner::class.java)
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    fun submit(key: String, task: () -> Unit): CompletableFuture<Unit> {
        inFlight[key]?.let { return it }
        val future = CompletableFuture<Unit>()
        inFlight.putIfAbsent(key, future)?.let { return it }
        try {
            executor.execute {
                try {
                    task()
                } catch (e: Exception) {
                    logger.warn("Refreshing title extras {} failed: {}", key, e.javaClass.simpleName)
                } finally {
                    inFlight.remove(key, future)
                    future.complete(Unit)
                }
            }
        } catch (e: RejectedExecutionException) {
            logger.debug("Extras refresh queue full, skipping {}", key)
            inFlight.remove(key, future)
            future.complete(Unit)
        }
        return future
    }

    @PreDestroy
    fun shutdown() {
        (executor as? ExecutorService)?.shutdownNow()
    }

    companion object {
        private val threadCounter = AtomicInteger()

        private fun defaultExecutor(): ExecutorService = ThreadPoolExecutor(
            2, 2, 60, TimeUnit.SECONDS, LinkedBlockingQueue(200),
        ) { runnable ->
            Thread(runnable, "title-extras-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
        }.apply { allowCoreThreadTimeOut(true) }
    }
}

/** Waits up to [timeout]; on timeout the task keeps running in the background and still fills the cache. */
fun CompletableFuture<Unit>.awaitAtMost(timeout: Duration) {
    try {
        get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        // fall through: render without it
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    } catch (e: Exception) {
        // the task logs its own failures
    }
}
