package org.ratelog.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.security.RequestRateLimiter.Decision
import org.ratelog.security.RequestRateLimiter.Limit
import org.ratelog.test.MutableClock
import java.time.Duration

class RequestRateLimiterTest {

    private val clock = MutableClock()
    private val limiter = RequestRateLimiter(reads = Limit(perMinute = 600, burst = 100), writes = Limit(perMinute = 60, burst = 30), clock = clock)

    private fun acquire(times: Int, key: String = "user:1", write: Boolean = true): List<Decision> =
        (1..times).map { limiter.tryAcquire(key, write) }

    @Test
    fun `a full bucket allows a minute's worth plus the burst at once, then refuses`() {
        assertTrue(acquire(90).all { it == Decision.Allowed })

        val refused = limiter.tryAcquire("user:1", write = true)
        assertEquals(Decision.Limited(retryAfterSeconds = 1), refused)
    }

    @Test
    fun `tokens refill at the per-minute rate`() {
        acquire(90)
        clock.advance(Duration.ofSeconds(5))

        assertEquals(5, acquire(10).count { it == Decision.Allowed }, "60 per minute = 1 per second")
    }

    @Test
    fun `a bucket never refills past its capacity`() {
        acquire(90)
        clock.advance(Duration.ofHours(1))

        assertEquals(90, acquire(100).count { it == Decision.Allowed })
    }

    @Test
    fun `reads and writes have separate buckets`() {
        acquire(90, write = true)

        assertEquals(Decision.Allowed, limiter.tryAcquire("user:1", write = false))
        assertEquals(700, acquire(700, write = false).count { it == Decision.Allowed } + 1)
    }

    @Test
    fun `keys are isolated from each other`() {
        acquire(90, key = "user:1")

        assertEquals(Decision.Allowed, limiter.tryAcquire("user:2", write = true))
        assertEquals(Decision.Allowed, limiter.tryAcquire("token:1", write = true))
        assertEquals(Decision.Allowed, limiter.tryAcquire("ip:203.0.113.7", write = true))
    }

    @Test
    fun `retry-after is the wait for the next token, rounded up`() {
        val slow = RequestRateLimiter(reads = Limit(1, 0), writes = Limit(1, 0), clock = clock)
        slow.tryAcquire("k", write = true)
        clock.advance(Duration.ofSeconds(15))

        assertEquals(Decision.Limited(retryAfterSeconds = 45), slow.tryAcquire("k", write = true))
    }

    @Test
    fun `normal heavy use stays under the default limits`() {
        // Ticking off a 250-title list at 1.5 taps a second.
        val taps = (1..250).map { clock.advance(Duration.ofMillis(667)); limiter.tryAcquire("user:9", write = true) }
        assertTrue(taps.all { it == Decision.Allowed })

        // Discover: a page and 12 lazy rows, ten times in a row.
        val reads = (1..130).map { limiter.tryAcquire("user:9", write = false) }
        assertTrue(reads.all { it == Decision.Allowed })
    }

    @Test
    fun `refilled buckets are dropped when too many keys are tracked`() {
        val small = RequestRateLimiter(reads = Limit(60, 0), writes = Limit(60, 0), clock = clock, maxTrackedKeys = 10)
        repeat(10) { small.tryAcquire("ip:$it", write = false) }
        clock.advance(Duration.ofMinutes(1))
        small.tryAcquire("ip:new", write = false)

        assertEquals(1, small.trackedKeys())
    }
}
