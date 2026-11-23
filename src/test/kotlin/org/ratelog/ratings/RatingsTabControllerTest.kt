package org.ratelog.ratings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RatingsTabControllerTest {
    private val controller = RatingsTabController()

    @Test
    fun `opens the movie ranking by default`() {
        assertEquals("redirect:/movies", controller.ratings(null))
        assertEquals("redirect:/movies", controller.ratings("movie"))
        assertEquals("redirect:/movies", controller.ratings("nonsense"))
    }

    @Test
    fun `type tv opens the TV ranking`() {
        listOf("tv", "TV", "tvshow", "tvshows", " shows ").forEach {
            assertEquals("redirect:/tvshows", controller.ratings(it), it)
        }
    }
}
