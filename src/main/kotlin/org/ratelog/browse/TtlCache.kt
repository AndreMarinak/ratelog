package org.ratelog.browse

import arrow.core.Either
import arrow.core.right
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Small in-memory cache: entries expire after [ttl], and the least recently used entry goes once there are
 * more than [maxEntries]. Only successful loads are stored, so a TMDB failure is retried on the next request.
 */
class TtlCache<K : Any, V : Any>(
    private val ttl: Duration,
    private val maxEntries: Int,
    private val clock: Clock,
) {
    private data class Entry<V>(val value: V, val expiresAt: Instant)

    private val entries = object : LinkedHashMap<K, Entry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (!clock.instant().isBefore(entry.expiresAt)) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    @Synchronized
    fun put(key: K, value: V) {
        entries[key] = Entry(value, clock.instant().plus(ttl))
    }

    @get:Synchronized
    val size: Int get() = entries.size

    /** The cached value, or the result of [load] (stored only when it is a Right). */
    fun <E> getOrLoad(key: K, load: () -> Either<E, V>): Either<E, V> {
        get(key)?.let { return it.right() }
        return load().onRight { put(key, it) }
    }
}
