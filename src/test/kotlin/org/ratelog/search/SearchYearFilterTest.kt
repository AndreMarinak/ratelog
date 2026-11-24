package org.ratelog.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.browse.BrowseDecade
import org.ratelog.browse.BrowseParamParser
import java.time.LocalDate

class SearchYearFilterTest {

    private val today = LocalDate.of(2026, 11, 24)

    @Test
    fun `parses an exact year, a decade, or nothing like Discover writes them`() {
        assertEquals(SearchYearFilter.Exact(2023), SearchYearFilter.parse("2023", null, today))
        assertEquals(SearchYearFilter.Exact(1994), SearchYearFilter.parse(" 1994 ", "", today))
        assertEquals(SearchYearFilter.Decade(BrowseDecade.Of(2020)), SearchYearFilter.parse(null, "2020s", today))
        assertEquals(SearchYearFilter.Decade(BrowseDecade.Of(1980)), SearchYearFilter.parse("", "1980", today))
        assertEquals(SearchYearFilter.Decade(BrowseDecade.Earlier), SearchYearFilter.parse(null, "earlier", today))
        assertEquals(SearchYearFilter.AnyYear, SearchYearFilter.parse(null, null, today))
        assertEquals(SearchYearFilter.AnyYear, SearchYearFilter.parse("  ", "  ", today))
    }

    @Test
    fun `a year wins over a decade, and invalid values are ignored`() {
        assertEquals(SearchYearFilter.Exact(1985), SearchYearFilter.parse("1985", "2010s", today))
        // An invalid year falls back to the decade, an invalid decade to any year.
        assertEquals(SearchYearFilter.Decade(BrowseDecade.Of(2010)), SearchYearFilter.parse("abc", "2010s", today))
        assertEquals(SearchYearFilter.AnyYear, SearchYearFilter.parse(null, "1985s", today))
        assertEquals(SearchYearFilter.AnyYear, SearchYearFilter.parse(null, "3000s", today))
        // The same bounds as Discover: 1874 up to ten years ahead.
        assertEquals(SearchYearFilter.AnyYear, SearchYearFilter.parse("1873", null, today))
        assertEquals(SearchYearFilter.Exact(1874), SearchYearFilter.parse("1874", null, today))
        assertEquals(SearchYearFilter.Exact(2036), SearchYearFilter.parse("2036", null, today))
        assertEquals(SearchYearFilter.AnyYear, SearchYearFilter.parse("2037", null, today))
    }

    @Test
    fun `invalidYear reports a typed year that isn't one`() {
        assertEquals("abcd", SearchYearFilter.invalidYear("abcd", today))
        assertEquals("1492", SearchYearFilter.invalidYear(" 1492", today))
        assertNull(SearchYearFilter.invalidYear("1994", today))
        assertNull(SearchYearFilter.invalidYear("", today))
        assertNull(SearchYearFilter.invalidYear(null, today))
    }

    @Test
    fun `a decade contains its years only, and a title without a year is in none`() {
        val eighties = SearchYearFilter.Decade(BrowseDecade.Of(1980))
        assertTrue(eighties.contains(1980))
        assertTrue(eighties.contains(1989))
        assertFalse(eighties.contains(1979))
        assertFalse(eighties.contains(1990))
        assertFalse(eighties.contains(null))

        val earlier = SearchYearFilter.Decade(BrowseDecade.Earlier)
        assertTrue(earlier.contains(1959))
        assertTrue(earlier.contains(1925))
        assertFalse(earlier.contains(1960))
    }

    @Test
    fun `each choice writes its URL parameter`() {
        assertNull(SearchYearFilter.AnyYear.param)
        assertEquals("year" to "2023", SearchYearFilter.Exact(2023).param)
        assertEquals("decade" to "2020s", SearchYearFilter.Decade(BrowseDecade.Of(2020)).param)
        assertEquals("decade" to "earlier", SearchYearFilter.Decade(BrowseDecade.Earlier).param)
    }

    @Test
    fun `the shared parser helpers keep Discover's rules`() {
        assertEquals(BrowseDecade.Of(2010), BrowseParamParser.decadeOrNull("2010S", today))
        assertEquals(BrowseDecade.Earlier, BrowseParamParser.decadeOrNull("pre-1960", today))
        assertNull(BrowseParamParser.decadeOrNull("1860s", today))
        assertNull(BrowseParamParser.decadeOrNull("2040s", today))
        assertEquals(2036, BrowseParamParser.latestYear(today))
        assertEquals(1874, BrowseParamParser.yearOrNull("1874", today))
        assertNull(BrowseParamParser.yearOrNull("19x4", today))
    }
}
