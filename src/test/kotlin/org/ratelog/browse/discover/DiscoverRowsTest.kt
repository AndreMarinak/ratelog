package org.ratelog.browse.discover

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Genre
import org.ratelog.MediaType
import org.ratelog.browse.BrowseGenres
import org.ratelog.browse.BrowsePopular

class DiscoverRowsTest {

    @Test
    fun `given each type then rows are Popular now followed by genres TMDB has for that type`() {
        MediaType.entries.forEach { type ->
            val rows = DiscoverRows.forType(type, hideSeen = true)

            assertEquals(DiscoverRows.POPULAR, rows.first().id)
            assertEquals(BrowsePopular.NOW, rows.first().filters.popular)
            assertTrue(rows.drop(1).all { it.genre in BrowseGenres.forType(type) }, "$type genres must exist on TMDB for $type")
            assertEquals(rows.size, rows.map { it.id }.toSet().size, "ids are unique")
            assertTrue(rows.all { it.filters.type == type && it.filters.hideSeen })
            assertTrue(rows.none { it.filters.isUnfiltered }, "every row opens a grid")
        }
        assertEquals(Genre.HORROR, DiscoverRows.forType(MediaType.movie, true)[1].genre)
        assertTrue(DiscoverRows.TV_GENRES.none { it in setOf(Genre.NEWS, Genre.TALK, Genre.SOAP) })
    }

    @Test
    fun `given an id then finds the row for that type only, keeping the seen setting`() {
        val row = DiscoverRows.find(MediaType.tvshow, " Sci-Fi-Fantasy ", hideSeen = false)!!

        assertEquals("sci-fi-fantasy", row.id)
        assertEquals(Genre.SCI_FI_FANTASY, row.genre)
        assertEquals(false, row.filters.hideSeen)
        assertEquals("popular", DiscoverRows.find(MediaType.movie, "popular", true)?.id)
        assertNull(DiscoverRows.find(MediaType.tvshow, "horror", true), "Horror isn't a TV genre")
        assertNull(DiscoverRows.find(MediaType.movie, "western", true), "a real genre without a row")
        assertNull(DiscoverRows.find(MediaType.movie, "", true))
    }
}
