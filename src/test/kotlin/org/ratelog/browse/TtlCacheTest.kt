package org.ratelog.browse

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ratelog.test.MutableClock
import java.time.Duration

class TtlCacheTest {

    private val clock = MutableClock()
    private val cache = TtlCache<String, String>(Duration.ofMinutes(45), maxEntries = 2, clock)

    @Test
    fun `given a cached value then it is served until it expires`() {
        cache.put("a", "1")

        clock.advance(Duration.ofMinutes(44))
        assertEquals("1", cache.get("a"))

        clock.advance(Duration.ofMinutes(1))
        assertNull(cache.get("a"))
        assertEquals(0, cache.size)
    }

    @Test
    fun `given more entries than the limit then the least recently used one goes`() {
        cache.put("a", "1")
        cache.put("b", "2")
        cache.get("a")
        cache.put("c", "3")

        assertEquals("1", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("3", cache.get("c"))
    }

    @Test
    fun `given getOrLoad then loads once and never stores failures`() {
        var loads = 0
        val failing = cache.getOrLoad<String>("a") { loads++; "boom".left() }
        val first = cache.getOrLoad<String>("a") { loads++; "fresh".right() }
        val second = cache.getOrLoad<String>("a") { loads++; "again".right() }

        assertEquals("boom".left(), failing)
        assertEquals("fresh".right(), first)
        assertEquals("fresh".right(), second)
        assertEquals(2, loads)
    }
}
