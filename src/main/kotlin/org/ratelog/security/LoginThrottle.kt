package org.ratelog.security

import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory failed-login counter (single instance app; it resets on restart).
 *
 * Failures are counted per username and per client IP in a fixed window. When a key reaches its limit it is locked for
 * [lockDuration]: sign-ins for that username (or from that IP) are refused without checking the password, even a
 * correct one. A lock always expires; attempts made while locked are not counted, so they don't extend it.
 * Unknown usernames are counted exactly like real ones, so the responses never reveal which accounts exist.
 */
class LoginThrottle(
    private val clock: Clock = Clock.systemUTC(),
    private val maxFailuresPerUsername: Int = MAX_FAILURES_PER_USERNAME,
    private val maxFailuresPerIp: Int = MAX_FAILURES_PER_IP,
    private val window: Duration = WINDOW,
    private val lockDuration: Duration = LOCK_DURATION,
    private val maxTrackedKeys: Int = MAX_TRACKED_KEYS,
) {
    private class Counter(var windowStartMs: Long, var failures: Int = 0, var lockedUntilMs: Long = 0)

    private val counters = ConcurrentHashMap<String, Counter>()

    fun isLocked(username: String?, ip: String?): Boolean {
        val now = clock.millis()
        return keys(username, ip).any { (key, _) -> (counters[key]?.lockedUntilMs ?: 0) > now }
    }

    fun recordFailure(username: String?, ip: String?) {
        val now = clock.millis()
        keys(username, ip).forEach { (key, limit) ->
            counters.compute(key) { _, existing ->
                val counter = existing?.takeIf { now - it.windowStartMs < window.toMillis() || it.lockedUntilMs > now }
                    ?: Counter(windowStartMs = now)
                if (counter.lockedUntilMs <= now) {
                    counter.failures++
                    if (counter.failures >= limit) {
                        counter.lockedUntilMs = now + lockDuration.toMillis()
                        counter.failures = 0
                        counter.windowStartMs = now
                    }
                }
                counter
            }
        }
        if (counters.size > maxTrackedKeys) evict(now)
    }

    /** A correct password for [username] clears that username's failures (the IP's count is kept). */
    fun recordSuccess(username: String?) {
        usernameKey(username)?.let { key -> counters.computeIfPresent(key) { _, c -> c.takeIf { it.lockedUntilMs > clock.millis() } } }
    }

    internal fun trackedKeys(): Int = counters.size

    private fun keys(username: String?, ip: String?): List<Pair<String, Int>> = listOfNotNull(
        usernameKey(username)?.let { it to maxFailuresPerUsername },
        ip?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_KEY_LENGTH)?.let { "ip:$it" to maxFailuresPerIp },
    )

    private fun usernameKey(username: String?): String? =
        username?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.take(MAX_KEY_LENGTH)?.let { "user:$it" }

    /** Drop expired entries; if a flood of distinct keys still exceeds the cap, drop the oldest unlocked ones. */
    private fun evict(now: Long) {
        counters.entries.removeIf { (_, c) -> c.lockedUntilMs <= now && now - c.windowStartMs >= window.toMillis() }
        if (counters.size <= maxTrackedKeys) return
        counters.entries
            .filter { it.value.lockedUntilMs <= now }
            .sortedBy { it.value.windowStartMs }
            .take(counters.size - maxTrackedKeys)
            .forEach { counters.remove(it.key) }
    }

    companion object {
        const val MAX_FAILURES_PER_USERNAME = 10
        const val MAX_FAILURES_PER_IP = 30
        val WINDOW: Duration = Duration.ofMinutes(15)
        val LOCK_DURATION: Duration = Duration.ofMinutes(15)
        const val MAX_TRACKED_KEYS = 10_000
        private const val MAX_KEY_LENGTH = 100
    }
}
