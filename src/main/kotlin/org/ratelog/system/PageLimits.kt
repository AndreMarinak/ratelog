package org.ratelog.system

/**
 * Fork: bounds for `limit` query parameters on the feed and ranking pages. A negative or zero value used to reach the
 * database (Postgres rejects a negative LIMIT, a 500) and any size was accepted; out-of-range values are now clamped.
 */
object PageLimits {
    const val MAX = 1000

    fun clamp(limit: Int): Int = limit.coerceIn(1, MAX)
}
