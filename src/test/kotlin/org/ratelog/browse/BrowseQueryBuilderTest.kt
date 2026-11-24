package org.ratelog.browse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Genre
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import java.time.LocalDate

class BrowseQueryBuilderTest {

    private val today = LocalDate.parse("2026-11-22")
    private val us = WatchRegion("US")

    private fun build(filters: BrowseFilters, page: Int = 1, lang: Lang = Lang.en) =
        BrowseQueryBuilder.build(filters, page, lang, us, today)

    private fun params(filters: BrowseFilters, page: Int = 1) = build(filters, page).params

    @Test
    fun `given no filters then discovers popular movies`() {
        val request = build(BrowseFilters(), page = 2, lang = Lang.es)

        assertEquals("/discover/movie", request.path)
        assertEquals(
            mapOf(
                "include_adult" to "false", "include_video" to "false", "language" to "es",
                "page" to "2", "sort_by" to "popularity.desc",
            ),
            request.params,
        )
    }

    @Test
    fun `given TV then uses discover tv and its own sort and date fields`() {
        val filters = BrowseFilters(type = MediaType.tvshow, decade = BrowseDecade.Of(2000), sort = BrowseSort.TITLE)
        val request = build(filters)

        assertEquals("/discover/tv", request.path)
        assertEquals("name.asc", request.params["sort_by"])
        assertEquals("2000-01-01", request.params["first_air_date.gte"])
        assertEquals("2009-12-31", request.params["first_air_date.lte"])
        assertNull(request.params["include_video"])
        assertNull(request.params["primary_release_date.gte"])
    }

    @Test
    fun `given a decade, a year, earlier or upcoming then sets the release window`() {
        params(BrowseFilters(decade = BrowseDecade.Of(1990))).let {
            assertEquals("1990-01-01", it["primary_release_date.gte"])
            assertEquals("1999-12-31", it["primary_release_date.lte"])
        }
        params(BrowseFilters(decade = BrowseDecade.Of(2010), year = BrowseYear.Of(2014))).let {
            assertEquals("2014-01-01", it["primary_release_date.gte"])
            assertEquals("2014-12-31", it["primary_release_date.lte"])
        }
        params(BrowseFilters(decade = BrowseDecade.Earlier)).let {
            assertNull(it["primary_release_date.gte"])
            assertEquals("1959-12-31", it["primary_release_date.lte"])
        }
        params(BrowseFilters(year = BrowseYear.Upcoming)).let {
            assertEquals("2026-11-23", it["primary_release_date.gte"])
            assertNull(it["primary_release_date.lte"])
            assertEquals("popularity.desc", it["sort_by"])
            assertNull(it["vote_count.gte"])
        }
    }

    @Test
    fun `given newest first then stops at today, also inside the current decade`() {
        params(BrowseFilters(sort = BrowseSort.NEWEST)).let {
            assertEquals("primary_release_date.desc", it["sort_by"])
            assertEquals("2026-11-22", it["primary_release_date.lte"])
            assertEquals("20", it["vote_count.gte"])
        }
        params(BrowseFilters(decade = BrowseDecade.Of(2020), sort = BrowseSort.NEWEST)).let {
            assertEquals("2020-01-01", it["primary_release_date.gte"])
            assertEquals("2026-11-22", it["primary_release_date.lte"])
        }
        params(BrowseFilters(decade = BrowseDecade.Of(1980), sort = BrowseSort.NEWEST)).let {
            assertEquals("1989-12-31", it["primary_release_date.lte"])
        }
        // Upcoming + newest: no cap (everything is in the future), no vote floor.
        params(BrowseFilters(year = BrowseYear.Upcoming, sort = BrowseSort.NEWEST)).let {
            assertNull(it["primary_release_date.lte"])
            assertNull(it["vote_count.gte"])
        }
    }

    @Test
    fun `given each sort then maps to the TMDB ordering`() {
        assertEquals("popularity.desc", params(BrowseFilters(sort = BrowseSort.POPULARITY))["sort_by"])
        assertEquals("vote_average.desc", params(BrowseFilters(sort = BrowseSort.RATING))["sort_by"])
        assertEquals("primary_release_date.asc", params(BrowseFilters(sort = BrowseSort.OLDEST))["sort_by"])
        assertEquals("title.asc", params(BrowseFilters(sort = BrowseSort.TITLE))["sort_by"])
        assertEquals("first_air_date.desc", params(BrowseFilters(type = MediaType.tvshow, sort = BrowseSort.NEWEST))["sort_by"])
        assertEquals("first_air_date.asc", params(BrowseFilters(type = MediaType.tvshow, sort = BrowseSort.OLDEST))["sort_by"])
    }

    @Test
    fun `given highest rated then requires enough votes, more for movies than TV`() {
        assertEquals("300", params(BrowseFilters(sort = BrowseSort.RATING))["vote_count.gte"])
        assertEquals("200", params(BrowseFilters(type = MediaType.tvshow, sort = BrowseSort.RATING))["vote_count.gte"])
        assertNull(params(BrowseFilters(sort = BrowseSort.POPULARITY))["vote_count.gte"])
    }

    @Test
    fun `given a rating floor then sets the vote average and a vote count minimum`() {
        params(BrowseFilters(minRating = 8.0)).let {
            assertEquals("8", it["vote_average.gte"])
            assertEquals("100", it["vote_count.gte"])
        }
        assertEquals("50", params(BrowseFilters(type = MediaType.tvshow, minRating = 7.5))["vote_count.gte"])
        assertEquals("7.5", params(BrowseFilters(type = MediaType.tvshow, minRating = 7.5))["vote_average.gte"])
        // The stricter minimum wins when both apply.
        assertEquals("300", params(BrowseFilters(minRating = 6.0, sort = BrowseSort.RATING))["vote_count.gte"])
    }

    @Test
    fun `given popular now or all time then they set the ordering`() {
        assertEquals("popularity.desc", params(BrowseFilters(popular = BrowsePopular.NOW))["sort_by"])
        params(BrowseFilters(popular = BrowsePopular.ALL_TIME)).let {
            assertEquals("vote_count.desc", it["sort_by"])
            assertNull(it["vote_count.gte"])
        }
        assertEquals("100", params(BrowseFilters(popular = BrowsePopular.ALL_TIME, minRating = 7.0))["vote_count.gte"])
    }

    @Test
    fun `given several services then asks TMDB for titles on any of them`() {
        val p = params(BrowseFilters(providers = listOf(8, 15, 1899)))

        assertEquals("8|15|1899", p["with_watch_providers"])
        assertEquals("US", p["watch_region"])
        assertEquals("flatrate", p["with_watch_monetization_types"])
        // Same choice in any order: same request, same cache entry.
        val a = BrowseQueryBuilder.build(BrowseFilters().withProviders(listOf(1899, 8, 15)), 1, Lang.en, WatchRegion("US"), LocalDate.parse("2026-11-22"))
        val b = BrowseQueryBuilder.build(BrowseFilters().withProviders(listOf(15, 1899, 8)), 1, Lang.en, WatchRegion("US"), LocalDate.parse("2026-11-22"))
        assertEquals(a.cacheKey, b.cacheKey)
        assertEquals(listOf("provider"), BrowseQueryBuilder.ignored(BrowseFilters(popular = BrowsePopular.WEEK, providers = listOf(8, 15))))
    }

    @Test
    fun `given genre, service, language and runtime then adds their discover parameters`() {
        val p = params(
            BrowseFilters(genre = Genre.HORROR, providers = listOf(8), language = "ja", runtime = BrowseRuntime.SHORT)
        )

        assertEquals("27", p["with_genres"])
        assertEquals("8", p["with_watch_providers"])
        assertEquals("US", p["watch_region"])
        assertEquals("flatrate", p["with_watch_monetization_types"])
        assertEquals("ja", p["with_original_language"])
        assertEquals("40", p["with_runtime.gte"])
        assertEquals("89", p["with_runtime.lte"])

        params(BrowseFilters(runtime = BrowseRuntime.MEDIUM)).let {
            assertEquals("90", it["with_runtime.gte"])
            assertEquals("150", it["with_runtime.lte"])
        }
        params(BrowseFilters(runtime = BrowseRuntime.LONG)).let {
            assertEquals("151", it["with_runtime.gte"])
            assertNull(it["with_runtime.lte"])
        }
        // Runtime never reaches a TV query, even if set programmatically.
        assertNull(params(BrowseFilters(type = MediaType.tvshow, runtime = BrowseRuntime.LONG))["with_runtime.gte"])
        assertNull(params(BrowseFilters())["watch_region"])
    }

    @Test
    fun `given this week then uses trending with only language and page`() {
        val request = build(BrowseFilters(type = MediaType.tvshow, popular = BrowsePopular.WEEK, genre = Genre.DRAMA), page = 3)

        assertEquals("/trending/tv/week", request.path)
        assertEquals(mapOf("language" to "en", "page" to "3"), request.params)
    }

    @Test
    fun `given this week with service or runtime then reports them as ignored`() {
        assertEquals(
            listOf("provider", "runtime"),
            BrowseQueryBuilder.ignored(BrowseFilters(popular = BrowsePopular.WEEK, providers = listOf(8), runtime = BrowseRuntime.LONG)),
        )
        assertTrue(BrowseQueryBuilder.ignored(BrowseFilters(popular = BrowsePopular.WEEK, genre = Genre.DRAMA)).isEmpty())
        assertTrue(BrowseQueryBuilder.ignored(BrowseFilters(providers = listOf(8), runtime = BrowseRuntime.LONG)).isEmpty())
    }

    @Test
    fun `given this week with filters trending can check then matches them on each result`() {
        val filters = BrowseFilters(popular = BrowsePopular.WEEK, genre = Genre.HORROR, language = "en", minRating = 7.0, decade = BrowseDecade.Of(2020))
        val title = BrowseTitle(
            tmdbId = 1, type = MediaType.movie, title = "Scary", releaseDate = LocalDate.parse("2025-10-01"), posterPath = null,
            voteAverage = 7.4, voteCount = 900, genreIds = listOf(27, 53), originalLanguage = "en",
        )

        assertTrue(BrowseQueryBuilder.filtersTrendingLocally(filters))
        assertTrue(BrowseQueryBuilder.matchesTrending(filters, title, today))
        assertFalse(BrowseQueryBuilder.matchesTrending(filters, title.copy(genreIds = listOf(35)), today))
        assertFalse(BrowseQueryBuilder.matchesTrending(filters, title.copy(originalLanguage = "fr"), today))
        assertFalse(BrowseQueryBuilder.matchesTrending(filters, title.copy(voteAverage = 6.9), today))
        assertFalse(BrowseQueryBuilder.matchesTrending(filters, title.copy(voteCount = 12), today))
        assertFalse(BrowseQueryBuilder.matchesTrending(filters, title.copy(releaseDate = LocalDate.parse("2019-12-31")), today))
        assertFalse(BrowseQueryBuilder.matchesTrending(filters, title.copy(releaseDate = null), today))

        val upcoming = BrowseFilters(popular = BrowsePopular.WEEK, year = BrowseYear.Upcoming)
        assertTrue(BrowseQueryBuilder.matchesTrending(upcoming, title.copy(releaseDate = LocalDate.parse("2027-05-01")), today))
        assertFalse(BrowseQueryBuilder.matchesTrending(upcoming, title, today))
        assertFalse(BrowseQueryBuilder.filtersTrendingLocally(BrowseFilters(popular = BrowsePopular.WEEK, providers = listOf(8))))
    }

    @Test
    fun `given the same filters and page then the cache key is identical, otherwise it differs`() {
        val a = build(BrowseFilters(genre = Genre.DRAMA, minRating = 7.0))
        val b = build(BrowseFilters(minRating = 7.0, genre = Genre.DRAMA))

        assertEquals(a.cacheKey, b.cacheKey)
        assertTrue(a.cacheKey.startsWith("/discover/movie?include_adult=false&include_video=false&language=en&page=1"))
        assertFalse(a.cacheKey.contains("api_key"))
        assertTrue(a.cacheKey != build(BrowseFilters(genre = Genre.DRAMA, minRating = 7.0), page = 2).cacheKey)
    }
}
