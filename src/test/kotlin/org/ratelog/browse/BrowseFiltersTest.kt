package org.ratelog.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
            provider = 8,
            language = "ko",
            runtime = BrowseRuntime.MEDIUM,
            sort = BrowseSort.RATING,
            hideRated = true,
            hideWatchlist = true,
        )

        val href = filters.href(page = 2)

        assertEquals(
            "/browse?type=movie&decade=2010s&year=2014&minRating=7.5&genre=science-fiction&provider=8&language=ko" +
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
        assertEquals("/browse?type=movie", BrowseFilters().href())
        assertEquals("/browse?type=tv&popular=week", BrowseFilters(type = MediaType.tvshow, popular = BrowsePopular.WEEK).href(1))
    }

    @Test
    fun `given a switch to TV then keeps what applies and maps genres`() {
        val movie = BrowseFilters(
            genre = Genre.SCIENCE_FICTION, runtime = BrowseRuntime.LONG, year = BrowseYear.Upcoming,
            minRating = 7.0, provider = 8, hideRated = true,
        )

        val tv = movie.withType(MediaType.tvshow)

        assertEquals(MediaType.tvshow, tv.type)
        assertEquals(Genre.SCI_FI_FANTASY, tv.genre)
        assertNull(tv.runtime)
        assertNull(tv.year)
        assertEquals(7.0, tv.minRating)
        assertEquals(8, tv.provider)
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
