package org.ratelog.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.ratelog.browse.BrowseDecade
import org.ratelog.browse.TmdbBrowseClient
import org.ratelog.browse.web.BarHidden
import org.ratelog.browse.web.BrowseBarViews
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.test.MutableClock
import java.time.Instant
import java.util.Locale

class SearchYearMenuTest {

    private val clock = MutableClock(Instant.parse("2026-11-24T12:00:00Z"))
    private val messages = BrowseBarViewsTest.testMessageSource()
    private val menus = SearchYearMenu(BrowseBarViews(messages, mock<TmdbBrowseClient>(), clock), messages, clock)

    private fun build(filter: SearchYearFilter, mediaType: String? = null, typed: String? = null) =
        menus.build("star wars", mediaType, filter, typed, Locale.ENGLISH)

    @Test
    fun `the menu is Discover's year menu without Upcoming, its links on the search page`() {
        val menu = build(SearchYearFilter.AnyYear)

        assertEquals("year", menu.id)
        assertNull(menu.value)
        assertEquals(listOf("Any year", "This year", "Last year"), menu.sections[0].links.map { it.label })
        assertEquals(listOf("2020s", "2010s", "2000s", "1990s", "1980s", "1970s", "1960s", "1950s and earlier"), menu.sections[1].links.map { it.label })
        assertEquals("/?q=star+wars", menu.sections[0].links[0].href)
        assertTrue(menu.sections[0].links[0].active)
        assertEquals("/?q=star+wars&year=2026", menu.sections[0].links[1].href)
        assertEquals("/?q=star+wars&year=2025", menu.sections[0].links[2].href)
        assertEquals("/?q=star+wars&decade=1980s", menu.sections[1].links[4].href)
        assertEquals("/?q=star+wars&decade=earlier", menu.sections[1].links[7].href)
        // A decade keeps the menu open on its years, like Discover.
        assertTrue(menu.sections[1].links.all { it.keepOpen })
        assertEquals(2, menu.sections.size)
    }

    @Test
    fun `a decade filters to it and shows its exact years, which write only the year`() {
        val menu = build(SearchYearFilter.Decade(BrowseDecade.Of(1980)), mediaType = "movie")

        assertEquals("1980s", menu.value)
        assertTrue(menu.active)
        assertTrue(menu.sections[1].links.single { it.label == "1980s" }.active)
        val years = menu.sections[2]
        assertEquals("Years in the 1980s", years.heading)
        assertTrue(years.reveal)
        assertEquals((1980..1989).map { it.toString() }, years.links.map { it.label })
        assertEquals("/?q=star+wars&mediaType=movie&year=1984", years.links[4].href)
        assertFalse(years.links.any { it.keepOpen })
    }

    @Test
    fun `an exact year highlights it and its decade`() {
        val menu = build(SearchYearFilter.Exact(1984))

        assertEquals("1984", menu.value)
        assertTrue(menu.sections[1].links.single { it.label == "1980s" }.active)
        assertTrue(menu.sections[2].links.single { it.label == "1984" }.active)
        assertFalse(menu.sections[0].links[0].active)
        assertEquals("1984", menu.yearBox!!.value)
    }

    @Test
    fun `the type a year box keeps the query and type, and says when a typed year isn't one`() {
        val box = build(SearchYearFilter.AnyYear, mediaType = "tvshow").yearBox!!

        assertEquals("/", box.action)
        assertEquals(listOf(BarHidden("q", "star wars"), BarHidden("mediaType", "tvshow")), box.hidden)
        assertEquals("Type a year", box.label)
        assertEquals(1874, box.min)
        assertEquals(2036, box.max)
        assertEquals("Type a year from 1874 to 2036.", box.error)
        assertFalse(box.invalid)
        assertNull(box.value)

        val wrong = build(SearchYearFilter.AnyYear, typed = "19x4").yearBox!!
        assertTrue(wrong.invalid)
        assertEquals("19x4", wrong.value)
    }
}
