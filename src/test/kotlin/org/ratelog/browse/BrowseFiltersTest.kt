package org.ratelog.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Genre
import org.ratelog.MediaType
import java.time.LocalDate

class BrowseFiltersTest {

    @Test
    fun `given filters then the URL lists them in a stable order and parses back to the same state`() {
        val filters = BrowseFilters(
            type = MediaType.movie,
            decade = BrowseDecade.Of(2010),
            year = BrowseYear.Of(2014),
            minRating = 7.5,
            genre = Genre.SCIENCE_FICTION,
            providers = listOf(8),
            language = "ko",
            runtime = BrowseRuntime.MEDIUM,
            sort = BrowseSort.RATING,
            hideRated = true,
            hideWatchlist = true,
        )

        val href = filters.href(page = 2)

        assertEquals(
            "/discover?type=movie&decade=2010s&year=2014&minRating=7.5&genre=science-fiction&provider=8&language=ko" +
                "&runtime=medium&sort=rating&hideRated=1&hideWatchlist=1&page=2",
            href,
        )
        val query = href.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        val parsed = BrowseParamParser.parse(
            BrowseParams(
                type = query["type"], decade = query["decade"], year = query["year"], minRating = query["minRating"],
                genre = query["genre"], provider = query["provider"], language = query["language"], runtime = query["runtime"],
                popular = query["popular"], sort = query["sort"], hideRated = query["hideRated"], hideWatchlist = query["hideWatchlist"],
                page = query["page"],
            ),
            LocalDate.parse("2026-11-22"),
        )
        assertEquals(filters, parsed.filters)
        assertEquals(2, parsed.page)
    }

    @Test
    fun `given page 1 or no filters then the URL stays short`() {
        assertEquals("/discover?type=movie", BrowseFilters().href())
        assertEquals("/discover?type=tv&popular=week", BrowseFilters(type = MediaType.tvshow, popular = BrowsePopular.WEEK).href(1))
    }

    @Test
    fun `given seen titles are shown then the URL says hideSeen=0 and it survives a round trip`() {
        val showing = BrowseFilters(genre = Genre.HORROR, hideSeen = false)

        val href = showing.href(page = 3)

        assertEquals("/discover?type=movie&genre=horror&hideSeen=0&page=3", href)
        val parsed = BrowseParamParser.parse(BrowseParams(genre = "horror", hideSeen = "0", page = "3"), LocalDate.parse("2026-11-22"))
        assertEquals(showing, parsed.filters)
        // Hiding is the default, so it never appears in a URL.
        assertEquals("/discover?type=movie&genre=horror", showing.copy(hideSeen = true).href())
        assertEquals(false, showing.withType(MediaType.tvshow).hideSeen)
    }

    @Test
    fun `given only a type and the seen setting then the view is unfiltered, anything else is a filter`() {
        assertTrue(BrowseFilters().isUnfiltered)
        assertTrue(BrowseFilters(type = MediaType.tvshow, hideSeen = false).isUnfiltered)

        listOf(
            BrowseFilters(genre = Genre.HORROR),
            BrowseFilters(popular = BrowsePopular.NOW),
            BrowseFilters(sort = BrowseSort.RATING),
            BrowseFilters(decade = BrowseDecade.Of(1990)),
            BrowseFilters(year = BrowseYear.Upcoming),
            BrowseFilters(minRating = 7.0),
            BrowseFilters(providers = listOf(8)),
            BrowseFilters(language = "ko"),
            BrowseFilters(runtime = BrowseRuntime.SHORT),
            BrowseFilters(hideRated = true),
            BrowseFilters(hideWatchlist = true),
        ).forEach { assertFalse(it.isUnfiltered, it.toString()) }
    }

    @Test
    fun `given several services then the URL pipe-joins them and parses back`() {
        val filters = BrowseFilters(type = MediaType.tvshow).withProviders(listOf(337, 8, 15, 8))

        assertEquals(listOf(8, 15, 337), filters.providers)
        assertEquals("/discover?type=tv&provider=8%7C15%7C337", filters.href())
        val raw = java.net.URLDecoder.decode(filters.href().substringAfter("provider="), Charsets.UTF_8)
        assertEquals("8|15|337", raw)
        assertEquals(filters, BrowseParamParser.parse(BrowseParams(type = "tv", provider = raw), LocalDate.parse("2026-11-22")).filters)
    }

    @Test
    fun `given a service toggle then it adds the ids, or removes them when any is already chosen`() {
        val none = BrowseFilters()
        val netflix = none.toggleProviders(listOf(8))
        val withParamount = netflix.toggleProviders(listOf(2616, 2303))

        assertEquals(listOf(8), netflix.providers)
        assertEquals(listOf(8, 2303, 2616), withParamount.providers)
        assertEquals(listOf(2303, 2616), withParamount.toggleProviders(listOf(8)).providers)
        // One plan chosen (e.g. an old link): unticking drops every plan of the service.
        assertEquals(listOf(8), BrowseFilters(providers = listOf(8, 2616)).toggleProviders(listOf(2303, 2616)).providers)
        assertTrue(netflix.toggleProviders(listOf(8)).isUnfiltered)
    }

    @Test
    fun `given a switch to TV then keeps what applies and maps genres`() {
        val movie = BrowseFilters(
            genre = Genre.SCIENCE_FICTION, runtime = BrowseRuntime.LONG, year = BrowseYear.Upcoming,
            minRating = 7.0, providers = listOf(8), hideRated = true,
        )

        val tv = movie.withType(MediaType.tvshow)

        assertEquals(MediaType.tvshow, tv.type)
        assertEquals(Genre.SCI_FI_FANTASY, tv.genre)
        assertNull(tv.runtime)
        assertNull(tv.year)
        assertEquals(7.0, tv.minRating)
        assertEquals(listOf(8), tv.providers)
        assertEquals(true, tv.hideRated)
    }

    @Test
    fun `given a genre the other type lacks then switching drops it`() {
        assertNull(BrowseFilters(genre = Genre.HORROR).withType(MediaType.tvshow).genre)
        assertNull(BrowseFilters(type = MediaType.tvshow, genre = Genre.TALK).withType(MediaType.movie).genre)
        assertEquals(Genre.FAMILY, BrowseFilters(type = MediaType.tvshow, genre = Genre.KIDS).withType(MediaType.movie).genre)
        assertEquals(Genre.COMEDY, BrowseFilters(genre = Genre.COMEDY).withType(MediaType.tvshow).genre)
    }

    @Test
    fun `given popular or sort then choosing one clears the other`() {
        val sorted = BrowseFilters(sort = BrowseSort.TITLE)
        assertEquals(BrowseFilters(popular = BrowsePopular.ALL_TIME), sorted.withPopular(BrowsePopular.ALL_TIME))
        assertEquals(BrowseFilters(sort = BrowseSort.NEWEST), BrowseFilters(popular = BrowsePopular.WEEK).withSort(BrowseSort.NEWEST))
    }

    @Test
    fun `given a decade or upcoming then picking it clears the conflicting year or decade`() {
        val year = BrowseFilters(decade = BrowseDecade.Of(2010), year = BrowseYear.Of(2014))
        assertEquals(BrowseFilters(decade = BrowseDecade.Of(1990)), year.withDecade(BrowseDecade.Of(1990)))
        assertEquals(BrowseFilters(year = BrowseYear.Upcoming), year.withYear(BrowseYear.Upcoming))
    }

    @Test
    fun `given each genre list then every genre has a slug that parses back`() {
        BrowseGenres.MOVIE.forEach { assertEquals(it, BrowseGenres.parse(BrowseGenres.slug(it), MediaType.movie)) }
        BrowseGenres.TV.forEach { assertEquals(it, BrowseGenres.parse(BrowseGenres.slug(it), MediaType.tvshow)) }
        assertEquals(19, BrowseGenres.MOVIE.size)
        assertEquals(16, BrowseGenres.TV.size)
    }
}
