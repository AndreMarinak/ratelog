package org.ratelog.extras.mdblist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.test.MutableClock
import java.time.Duration

class RateLimitBackoffTest {

    private val clock = MutableClock()
    private val backoff = RateLimitBackoff(clock)

    @Test
    fun `given no 429 yet then calls are allowed`() {
        assertFalse(backoff.isBlocked())
        assertNull(backoff.blockedUntil())
    }

    @Test
    fun `given Retry-After seconds then blocks exactly that long`() {
        // When
        val until = backoff.block(retryAfter = "120", rateLimitReset = null)

        // Then
        assertEquals(clock.now.plusSeconds(120), until)
        assertTrue(backoff.isBlocked())
        clock.advance(Duration.ofSeconds(119))
        assertTrue(backoff.isBlocked())
        clock.advance(Duration.ofSeconds(1))
        assertFalse(backoff.isBlocked())
    }

    @Test
    fun `given an HTTP-date Retry-After then blocks until that date`() {
        val until = backoff.block(retryAfter = "Sun, 22 Nov 2026 13:30:00 GMT", rateLimitReset = null)
        assertEquals(clock.now.plus(Duration.ofMinutes(90)), until)
    }

    @Test
    fun `given no Retry-After but a daily reset timestamp then blocks until the reset`() {
        val reset = clock.now.plus(Duration.ofHours(12)).epochSecond
        val until = backoff.block(retryAfter = null, rateLimitReset = reset.toString())
        assertEquals(clock.now.plus(Duration.ofHours(12)), until)
    }

    @Test
    fun `given unusable headers then falls back to the default pause`() {
        val until = backoff.block(retryAfter = "soon", rateLimitReset = "-5")
        assertEquals(clock.now.plus(RateLimitBackoff.DEFAULT_BLOCK), until)
    }

    @Test
    fun `given an absurd Retry-After then caps the pause at a day and a bit`() {
        val until = backoff.block(retryAfter = "9999999", rateLimitReset = null)
        assertEquals(clock.now.plus(Duration.ofHours(25)), until)
    }

    @Test
    fun `given a later 429 asking for less time then the longer pause wins`() {
        backoff.block(retryAfter = "3600", rateLimitReset = null)
        val until = backoff.block(retryAfter = "10", rateLimitReset = null)
        assertEquals(clock.now.plusSeconds(3600), until)
        assertEquals(clock.now.plusSeconds(3600), backoff.blockedUntil())
    }
}
