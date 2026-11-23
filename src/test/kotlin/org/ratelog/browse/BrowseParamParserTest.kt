package org.ratelog.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Genre
import org.ratelog.MediaType
import java.time.LocalDate

class BrowseParamParserTest {

    private val today = LocalDate.parse("2026-11-22")

    private fun parse(params: BrowseParams) = BrowseParamParser.parse(params, today)

    private fun invalidNames(params: BrowseParams) = parse(params).invalid.map { it.name }

    @Test
    fun `given no parameters then browses popular movies on page 1`() {
        val parsed = parse(BrowseParams())

        assertEquals(BrowseFilters(), parsed.filters)
        assertEquals(MediaType.movie, parsed.filters.type)
        assertEquals(1, parsed.page)
        assertTrue(parsed.invalid.isEmpty())
        assertTrue(parsed.dropped.isEmpty())
    }

    @Test
    fun `given every filter then parses all of them`() {
        val parsed = parse(
            BrowseParams(
                type = "movie", decade = "2010s", year = "2014", minRating = "7", genre = "horror", provider = "8",
                language = "KO", runtime = "short", sort = "newest", hideRated = "1", hideWatchlist = "true", page = "3",
            )
        )

        assertTrue(parsed.invalid.isEmpty(), parsed.invalid.toString())
        assertEquals(
            BrowseFilters(
                type = MediaType.movie,
                decade = BrowseDecade.Of(2010),
                year = BrowseYear.Of(2014),
                minRating = 7.0,
                genre = Genre.HORROR,
                provider = 8,
                language = "ko",
                runtime = BrowseRuntime.SHORT,
                sort = BrowseSort.NEWEST,
                hideRated = true,
                hideWatchlist = true,
            ),
            parsed.filters,
        )
        assertEquals(3, parsed.page)
    }

    @Test
    fun `given type spellings then accepts movie and tv aliases, rejects others`() {
        assertEquals(MediaType.tvshow, parse(BrowseParams(type = "tv")).filters.type)
        assertEquals(MediaType.tvshow, parse(BrowseParams(type = "Series")).filters.type)
        assertEquals(MediaType.movie, parse(BrowseParams(type = "films")).filters.type)
        assertEquals(listOf("type"), invalidNames(BrowseParams(type = "anime")))
        // Unusable type falls back to movies.
        assertEquals(MediaType.movie, parse(BrowseParams(type = "anime")).filters.type)
    }

    @Test
    fun `given decades then accepts 2010s, 2010 and earlier`() {
        assertEquals(BrowseDecade.Of(2010), parse(BrowseParams(decade = "2010s")).filters.decade)
        assertEquals(BrowseDecade.Of(1980), parse(BrowseParams(decade = "1980")).filters.decade)
        assertEquals(BrowseDecade.Earlier, parse(BrowseParams(decade = "earlier")).filters.decade)
        assertEquals(listOf("decade"), invalidNames(BrowseParams(decade = "2015s")))
        assertEquals(listOf("decade"), invalidNames(BrowseParams(decade = "1700s")))
        assertEquals(listOf("decade"), invalidNames(BrowseParams(decade = "recent")))
    }

    @Test
    fun `given a year outside the decade then the year wins and the decade is dropped`() {
        val parsed = parse(BrowseParams(decade = "1990s", year = "2014"))

        assertNull(parsed.filters.decade)
        assertEquals(BrowseYear.Of(2014), parsed.filters.year)
        assertEquals(listOf("decade"), parsed.dropped)
        assertEquals(BrowseDecade.Of(2010), parsed.filters.effectiveDecade)
    }

    @Test
    fun `given a year inside earlier then keeps both`() {
        val parsed = parse(BrowseParams(decade = "earlier", year = "1954"))

        assertEquals(BrowseDecade.Earlier, parsed.filters.decade)
        assertEquals(BrowseYear.Of(1954), parsed.filters.year)
    }

    @Test
    fun `given upcoming then it is movies only and replaces the decade`() {
        val movie = parse(BrowseParams(decade = "2020s", year = "upcoming"))
        assertEquals(BrowseYear.Upcoming, movie.filters.year)
        assertNull(movie.filters.decade)
        assertEquals(listOf("decade"), movie.dropped)

        val tv = parse(BrowseParams(type = "tv", year = "upcoming"))
        assertNull(tv.filters.year)
        assertEquals(listOf("year"), tv.invalid.map { it.name })
    }

    @Test
    fun `given years out of range or not numbers then they are invalid`() {
        assertEquals(listOf("year"), invalidNames(BrowseParams(year = "1800")))
        assertEquals(listOf("year"), invalidNames(BrowseParams(year = "2099")))
        assertEquals(listOf("year"), invalidNames(BrowseParams(year = "twenty")))
        assertEquals(BrowseYear.Of(2030), parse(BrowseParams(year = "2030")).filters.year)
    }

    @Test
    fun `given rating floors then accepts numbers above 0 and below 10`() {
        assertEquals(7.5, parse(BrowseParams(minRating = "7.5")).filters.minRating)
        assertEquals(8.0, parse(BrowseParams(minRating = " 8 ")).filters.minRating)
        assertEquals(listOf("minRating"), invalidNames(BrowseParams(minRating = "10")))
        assertEquals(listOf("minRating"), invalidNames(BrowseParams(minRating = "0")))
        assertEquals(listOf("minRating"), invalidNames(BrowseParams(minRating = "high")))
        assertEquals(listOf("minRating"), invalidNames(BrowseParams(minRating = "NaN")))
    }

    @Test
    fun `given genres then accepts ids, slugs and the nearest genre of the other type`() {
        assertEquals(Genre.SCIENCE_FICTION, parse(BrowseParams(genre = "878")).filters.genre)
        assertEquals(Genre.SCIENCE_FICTION, parse(BrowseParams(genre = "science-fiction")).filters.genre)
        assertEquals(Genre.SCIENCE_FICTION, parse(BrowseParams(genre = "Sci-Fi")).filters.genre)
        assertEquals(Genre.ACTION_ADVENTURE, parse(BrowseParams(type = "tv", genre = "action")).filters.genre)
        assertEquals(Genre.SCI_FI_FANTASY, parse(BrowseParams(type = "tv", genre = "Sci-Fi & Fantasy")).filters.genre)
        assertEquals(listOf("genre"), invalidNames(BrowseParams(type = "tv", genre = "horror")))
        assertEquals(listOf("genre"), invalidNames(BrowseParams(genre = "talk")))
        assertEquals(listOf("genre"), invalidNames(BrowseParams(genre = "12345")))
    }

    @Test
    fun `given provider and language then validates their format`() {
        assertEquals(337, parse(BrowseParams(provider = "337")).filters.provider)
        assertEquals(listOf("provider"), invalidNames(BrowseParams(provider = "-1")))
        assertEquals(listOf("provider"), invalidNames(BrowseParams(provider = "netflix")))
        assertEquals("ja", parse(BrowseParams(language = "JA")).filters.language)
        assertEquals(listOf("language"), invalidNames(BrowseParams(language = "japanese")))
        assertEquals(listOf("language"), invalidNames(BrowseParams(language = "e1")))
    }

    @Test
    fun `given a runtime then it applies to movies only`() {
        assertEquals(BrowseRuntime.LONG, parse(BrowseParams(runtime = "over150")).filters.runtime)
        assertEquals(BrowseRuntime.MEDIUM, parse(BrowseParams(runtime = "medium")).filters.runtime)
        assertEquals(listOf("runtime"), invalidNames(BrowseParams(runtime = "epic")))

        val tv = parse(BrowseParams(type = "tv", runtime = "short"))
        assertNull(tv.filters.runtime)
        assertEquals(listOf("runtime"), tv.invalid.map { it.name })
    }

    @Test
    fun `given popular and sort together then popular wins`() {
        val parsed = parse(BrowseParams(popular = "week", sort = "title"))

        assertEquals(BrowsePopular.WEEK, parsed.filters.popular)
        assertNull(parsed.filters.sort)
        assertEquals(listOf("sort"), parsed.dropped)
    }

    @Test
    fun `given popular and sort spellings then accepts aliases and rejects the rest`() {
        assertEquals(BrowsePopular.ALL_TIME, parse(BrowseParams(popular = "all-time")).filters.popular)
        assertEquals(BrowsePopular.NOW, parse(BrowseParams(popular = "now")).filters.popular)
        assertEquals(listOf("popular"), invalidNames(BrowseParams(popular = "yesterday")))
        assertEquals(BrowseSort.RATING, parse(BrowseParams(sort = "highest-rated")).filters.sort)
        assertEquals(BrowseSort.OLDEST, parse(BrowseParams(sort = "old")).filters.sort)
        assertEquals(listOf("sort"), invalidNames(BrowseParams(sort = "random")))
    }

    @Test
    fun `given flags and pages then validates them`() {
        assertFalse(parse(BrowseParams(hideRated = "0")).filters.hideRated)
        assertTrue(parse(BrowseParams(hideWatchlist = "on")).filters.hideWatchlist)
        assertEquals(listOf("hideRated"), invalidNames(BrowseParams(hideRated = "maybe")))
        assertEquals(500, parse(BrowseParams(page = "500")).page)
        assertEquals(listOf("page"), invalidNames(BrowseParams(page = "0")))
        assertEquals(listOf("page"), invalidNames(BrowseParams(page = "501")))
        assertEquals(1, parse(BrowseParams(page = "abc")).page)
    }

    @Test
    fun `given blank values then they count as missing`() {
        val parsed = parse(BrowseParams(decade = " ", genre = "", sort = "  "))

        assertEquals(BrowseFilters(), parsed.filters)
        assertTrue(parsed.invalid.isEmpty())
    }
}
