package org.ratelog.security

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * In-memory token buckets (single instance app; they reset on restart), one per key and kind (read or write).
 * A bucket holds up to `perMinute + burst` requests and refills continuously at `perMinute`, so a minute's worth plus
 * the burst can go at once, then requests flow at the steady rate.
 */
class RequestRateLimiter(
    private val reads: Limit,
    private val writes: Limit,
    private val clock: Clock = Clock.systemUTC(),
    private val maxTrackedKeys: Int = MAX_TRACKED_KEYS,
) {
    data class Limit(val perMinute: Int, val burst: Int) {
        init {
            require(perMinute > 0 && burst >= 0) { "rate limit must be positive" }
        }
        val capacity: Double get() = (perMinute + burst).toDouble()
        val tokensPerMs: Double get() = perMinute / 60_000.0
    }

    sealed interface Decision {
        data object Allowed : Decision
        data class Limited(val retryAfterSeconds: Long) : Decision
    }

    private class Bucket(var tokens: Double, var updatedMs: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryAcquire(key: String, write: Boolean): Decision {
        val limit = if (write) writes else reads
        val now = clock.millis()
        var decision: Decision = Decision.Allowed
        buckets.compute((if (write) "w:" else "r:") + key) { _, existing ->
            val bucket = existing ?: Bucket(limit.capacity, now)
            bucket.tokens = min(limit.capacity, bucket.tokens + (now - bucket.updatedMs).coerceAtLeast(0) * limit.tokensPerMs)
            bucket.updatedMs = now
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
            } else {
                val waitMs = (1.0 - bucket.tokens) / limit.tokensPerMs
                decision = Decision.Limited(ceil(waitMs / 1000.0).toLong().coerceAtLeast(1))
            }
            bucket
        }
        if (buckets.size > maxTrackedKeys) evictFull(now)
        return decision
    }

    internal fun trackedKeys(): Int = buckets.size

    /** A bucket that has refilled completely holds no state worth keeping; past twice the cap, start over. */
    private fun evictFull(now: Long) {
        buckets.entries.removeIf { (key, b) ->
            val limit = if (key.startsWith("w:")) writes else reads
            b.tokens + (now - b.updatedMs) * limit.tokensPerMs >= limit.capacity
        }
        if (buckets.size > maxTrackedKeys * 2) buckets.clear()
    }

    companion object {
        const val MAX_TRACKED_KEYS = 20_000
    }
}
