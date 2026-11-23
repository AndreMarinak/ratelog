package org.ratelog.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.test.MutableClock
import java.time.Duration

class LoginThrottleTest {

    private val clock = MutableClock()
    private val throttle = LoginThrottle(clock, maxFailuresPerUsername = 3, maxFailuresPerIp = 5)

    private fun fail(times: Int, username: String = "admin", ip: String = "1.1.1.1") =
        repeat(times) { throttle.recordFailure(username, ip) }

    @Test
    fun `a username locks after its limit of failures and not before`() {
        fail(2)
        assertFalse(throttle.isLocked("admin", "9.9.9.9"))

        fail(1)
        assertTrue(throttle.isLocked("admin", "9.9.9.9"), "locked from any IP")
        assertFalse(throttle.isLocked("bob", "9.9.9.9"))
    }

    @Test
    fun `usernames are matched case-insensitively and trimmed`() {
        fail(1, username = "Admin")
        fail(1, username = " admin ")
        fail(1, username = "ADMIN")

        assertTrue(throttle.isLocked("admin", null))
    }

    @Test
    fun `the lock expires after the lock duration`() {
        fail(3)
        clock.advance(LoginThrottle.LOCK_DURATION.minusSeconds(1))
        assertTrue(throttle.isLocked("admin", null))

        clock.advance(Duration.ofSeconds(1))
        assertFalse(throttle.isLocked("admin", null))
    }

    @Test
    fun `attempts made while locked do not extend the lock`() {
        fail(3)
        clock.advance(Duration.ofMinutes(10))
        fail(50)

        clock.advance(Duration.ofMinutes(5))
        assertFalse(throttle.isLocked("admin", null), "still expires 15 minutes after it started")
    }

    @Test
    fun `failures older than the window are forgotten`() {
        fail(2)
        clock.advance(LoginThrottle.WINDOW)
        fail(2)

        assertFalse(throttle.isLocked("admin", null))
    }

    @Test
    fun `one IP trying many usernames is locked by its own limit`() {
        listOf("a1", "a2", "a3", "a4", "a5").forEach { fail(1, username = it, ip = "6.6.6.6") }

        assertTrue(throttle.isLocked("someone-new", "6.6.6.6"))
        assertFalse(throttle.isLocked("someone-new", "7.7.7.7"))
    }

    @Test
    fun `a success clears the username's failures but keeps the IP's`() {
        fail(2, ip = "6.6.6.6")
        throttle.recordSuccess("admin")
        fail(2, ip = "6.6.6.6")
        assertFalse(throttle.isLocked("admin", null), "username count restarted after the success")

        fail(1, username = "other", ip = "6.6.6.6")
        assertTrue(throttle.isLocked(null, "6.6.6.6"), "IP count kept all 5 failures")
    }

    @Test
    fun `unknown usernames are counted exactly like real ones`() {
        fail(3, username = "does-not-exist")

        assertTrue(throttle.isLocked("does-not-exist", null))
    }

    @Test
    fun `blank usernames and IPs are ignored`() {
        repeat(10) { throttle.recordFailure("  ", null) }

        assertEquals(0, throttle.trackedKeys())
        assertFalse(throttle.isLocked("", ""))
    }

    @Test
    fun `a flood of distinct usernames is capped while active locks are kept`() {
        val small = LoginThrottle(clock, maxFailuresPerUsername = 2, maxFailuresPerIp = 1_000_000, maxTrackedKeys = 50)
        repeat(2) { small.recordFailure("admin", null) }
        repeat(500) { small.recordFailure("spray$it", null) }

        assertTrue(small.trackedKeys() <= 51)
        assertTrue(small.isLocked("admin", null))
    }
}
