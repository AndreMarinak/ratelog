package org.ratelog.extras.mdblist

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide "stop calling until" marker. MDBList answers 429 with `Retry-After` (seconds) when the daily
 * (1,000 on the free tier) or 5-minute limit is hit; retrying before then only burns requests.
 */
class RateLimitBackoff(private val clock: Clock) {
    private val blockedUntil = AtomicReference<Instant?>(null)

    /** The instant calls may resume, or null when not blocked. */
    fun blockedUntil(): Instant? = blockedUntil.get()?.takeIf { clock.instant().isBefore(it) }

    fun isBlocked(): Boolean = blockedUntil() != null

    /** Lifts any pause, e.g. after the admin saved a new key (a rejected key pauses calls for an hour). */
    fun clear() {
        blockedUntil.set(null)
    }

    /** Blocks for the time the headers ask (never shortening an existing block) and returns the resume instant. */
    fun block(retryAfter: String?, rateLimitReset: String?, fallback: Duration = DEFAULT_BLOCK): Instant {
        val now = clock.instant()
        val requested = parseRetryAfter(retryAfter, now) ?: parseReset(rateLimitReset, now) ?: (now + fallback)
        val until = requested.coerceIn(now + MIN_BLOCK, now + MAX_BLOCK)
        return blockedUntil.updateAndGet { old -> if (old == null || until.isAfter(old)) until else old } ?: until
    }

    companion object {
        val DEFAULT_BLOCK: Duration = Duration.ofMinutes(15)
        private val MIN_BLOCK: Duration = Duration.ofSeconds(1)

        /** The daily limit resets at 00:00 UTC, so no sane wait is longer than a day. */
        private val MAX_BLOCK: Duration = Duration.ofHours(25)

        /** `Retry-After` is delta-seconds per the MDBList spec; an HTTP-date is accepted too (RFC 9110). */
        fun parseRetryAfter(value: String?, now: Instant): Instant? {
            val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            raw.toLongOrNull()?.let { seconds -> return if (seconds >= 0) now.plusSeconds(seconds) else null }
            return runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
        }

        /** `X-RateLimit-Reset`: Unix timestamp (seconds) of the daily reset. */
        fun parseReset(value: String?, now: Instant): Instant? =
            value?.trim()?.toLongOrNull()?.let(Instant::ofEpochSecond)?.takeIf { it.isAfter(now) }
    }
}
