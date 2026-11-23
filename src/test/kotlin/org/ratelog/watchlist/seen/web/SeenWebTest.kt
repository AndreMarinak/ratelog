package org.ratelog.watchlist.seen.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.Status
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.home.TitleMark
import org.ratelog.movie.detail.MovieDetailResponse
import org.ratelog.search.SearchResultItem
import org.ratelog.test.InMemoryHomeRepository
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.TvShowFactory
import org.ratelog.test.UserFactory
import org.ratelog.tvshow.detail.SeasonResponse
import org.ratelog.tvshow.detail.TvShowDetailResponse
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.SeenStateReader
import org.ratelog.watchlist.seen.StatusJsonController
import org.ratelog.watchlist.seen.TileActionsView
import org.ratelog.watchlist.seen.TitleActionsView
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.ModelAndView
import java.time.LocalDate
import java.util.Locale

/** The title page and search interceptors, and POST /status for fetch. */
class SeenWebTest {

    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val statuses = InMemoryMediaStatusRepository()
    private val clock = MutableClock() // today: 2026-11-22
    private val titles = TitleActionsInterceptor(movies, shows, clock)

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun get() = MockHttpServletRequest("GET", "/movie/1")

    private fun movieResponse(id: Long, tmdbId: Int, rated: Boolean = false, followed: Boolean = false) = MovieDetailResponse(
        id = id, tmdbId = tmdbId, name = "x", overview = null, firstAirYear = 2026, posterPath = null, tmdbVoteAverage = null,
        genres = emptyList(), directing = null, cinematography = null, acting = null, soundtrack = null, screenplay = null,
        score = if (rated) 8.0 else null, quickRating = rated, reviewTitle = null, reviewText = null, isFollowed = followed, hasRating = rated,
    )

    private fun season(n: Int, rated: Boolean) = SeasonResponse(n, rated, null, null, null, null, null, if (rated) 8.0 else null, rated, null, null)

    private fun showResponse(id: Long, tmdbId: Int, seasons: List<SeasonResponse>, followed: Boolean = false) = TvShowDetailResponse(
        id = id, tmdbId = tmdbId, name = "x", overview = null, firstAirYear = 2022, posterPath = null, tmdbVoteAverage = null,
        genres = emptyList(), seasons = seasons, overallScore = null, isFollowed = followed, hasRating = seasons.any { it.hasRating },
    )

    // --- Title page ---

    @Test
    fun `an upcoming movie gets the Upcoming date, no Seen and the bell`() {
        val doomsday = movies.save(MovieFactory.aMovie(tmdbId = 1003596, originalTitle = "Avengers: Doomsday", releaseDate = LocalDate.parse("2026-12-18"), status = Status.POST_PRODUCTION))
        val mav = ModelAndView("movie-detail", mapOf("movie" to movieResponse(doomsday.id!!.value, 1003596), "watchStatus" to WatchStatus.WANT_TO_WATCH))

        titles.postHandle(get(), MockHttpServletResponse(), Any(), mav)

        val a = mav.model["titleActions"] as TitleActionsView
        assertTrue(a.upcoming)
        assertFalse(a.canToggleSeen)
        assertEquals("Dec 18, 2026", a.upcomingDate)
        assertTrue(a.onWatchlist)
        assertTrue(a.showFollow)
        assertEquals("/movie/1003596", a.back)
    }

    @Test
    fun `a released, rated movie is seen and locked, without a bell unless followed`() {
        val lionKing = movies.save(MovieFactory.aMovie(tmdbId = 8587, originalTitle = "The Lion King", releaseDate = LocalDate.parse("1994-06-24"), status = Status.RELEASED))
        val plain = ModelAndView("movie-detail", mapOf("movie" to movieResponse(lionKing.id!!.value, 8587, rated = true)))
        titles.postHandle(get(), MockHttpServletResponse(), Any(), plain)
        val a = plain.model["titleActions"] as TitleActionsView
        assertTrue(a.seen && a.seenLocked)
        assertFalse(a.showFollow)

        val followed = ModelAndView("movie-detail", mapOf("movie" to movieResponse(lionKing.id!!.value, 8587, followed = true)))
        titles.postHandle(get(), MockHttpServletResponse(), Any(), followed)
        assertTrue((followed.model["titleActions"] as TitleActionsView).showFollow, "kept so it can be unfollowed")
    }

    @Test
    fun `a show uses its rated seasons and its TMDB status`() {
        val severance = shows.save(TvShowFactory.aTvShow(tmdbId = 95396, originalName = "Severance", firstAirDate = LocalDate.parse("2022-02-18"), status = Status.RETURNING_SERIES))
        val mav = ModelAndView("tvshow-detail", mapOf(
            "show" to showResponse(severance.id!!.value, 95396, listOf(season(1, rated = true), season(2, rated = false))),
            "watchStatus" to WatchStatus.WATCHING,
        ))

        titles.postHandle(MockHttpServletRequest("GET", "/tv/95396"), MockHttpServletResponse(), Any(), mav)

        val a = mav.model["titleActions"] as TitleActionsView
        assertTrue(a.isTv)
        assertTrue(a.watching)
        assertFalse(a.seen, "one rated season of two, still watching")
        assertFalse(a.seenLocked)
        assertTrue(a.showFollow, "returning series")
        assertEquals("/tv/95396", a.back)

        val ended = shows.save(TvShowFactory.aTvShow(tmdbId = 1396, originalName = "Breaking Bad", status = Status.ENDED))
        val endedMav = ModelAndView("tvshow-detail", mapOf("show" to showResponse(ended.id!!.value, 1396, emptyList())))
        titles.postHandle(MockHttpServletRequest("GET", "/tv/1396"), MockHttpServletResponse(), Any(), endedMav)
        assertFalse((endedMav.model["titleActions"] as TitleActionsView).showFollow)
    }

    @Test
    fun `nothing is added when the page failed, for other views or for posts`() {
        val failed = ModelAndView("movie-detail", mapOf("error" to "error.load.movie"))
        titles.postHandle(get(), MockHttpServletResponse(), Any(), failed)
        assertNull(failed.model["titleActions"])

        val other = ModelAndView("watchlist")
        titles.postHandle(get(), MockHttpServletResponse(), Any(), other)
        assertNull(other.model["titleActions"])

        val post = ModelAndView("movie-detail", mapOf("movie" to movieResponse(1, 1)))
        titles.postHandle(MockHttpServletRequest("POST", "/movie/1"), MockHttpServletResponse(), Any(), post)
        assertNull(post.model["titleActions"])

        titles.postHandle(get(), MockHttpServletResponse(), Any(), null)
    }

    // --- Search ---

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `search results get quick actions keyed by type and TMDB id`() {
        val user = UserFactory.aUser(id = 1)
        val home = InMemoryHomeRepository().apply {
            setMark(user.id!!, MediaType.movie, 299534, TitleMark(WatchStatus.WANT_TO_WATCH, null))
            setMark(user.id!!, MediaType.tvshow, 1396, TitleMark(WatchStatus.WATCHING, 9.0))
        }
        val search = SearchTileActionsInterceptor(home, { user }, clock)
        val results = listOf(
            SearchResultItem(299534, "Avengers: Endgame", null, 2019, null, "movie", "2019-04-24"),
            SearchResultItem(1003596, "Avengers: Doomsday", null, 2026, null, "movie", "2026-12-18"),
            SearchResultItem(1396, "Breaking Bad", null, 2008, null, "tvshow", "2008-01-20"),
            SearchResultItem(1, "Undated", null, null, null, "movie", ""),
        )
        val mav = ModelAndView("search", mapOf("results" to results))

        search.postHandle(MockHttpServletRequest("GET", "/"), MockHttpServletResponse(), Any(), mav)

        val actions = mav.model["resultActions"] as Map<String, TileActionsView>
        assertEquals(setOf("movie-299534", "movie-1003596", "tvshow-1396", "movie-1"), actions.keys)
        assertTrue(actions.getValue("movie-299534").onWatchlist)
        assertTrue(actions.getValue("movie-1003596").upcoming)
        assertEquals("Dec 18", actions.getValue("movie-1003596").upcomingDate)
        assertEquals("tv", actions.getValue("tvshow-1396").type)
        assertEquals("circle-play", actions.getValue("tvshow-1396").badgeIcon)
        assertFalse(actions.getValue("movie-1").upcoming, "a blank date is no date")
    }

    @Test
    fun `search adds nothing without results or a user`() {
        val home = InMemoryHomeRepository()
        val noUser = SearchTileActionsInterceptor(home, { null }, clock)
        val mav = ModelAndView("search", mapOf("results" to listOf(SearchResultItem(1, "x", null, null, null, "movie"))))
        noUser.postHandle(MockHttpServletRequest("GET", "/"), MockHttpServletResponse(), Any(), mav)
        assertNull(mav.model["resultActions"])

        val withUser = SearchTileActionsInterceptor(home, { UserFactory.aUser(id = 1) }, clock)
        val homePage = ModelAndView("search", mapOf("home" to "dashboard"))
        withUser.postHandle(MockHttpServletRequest("GET", "/"), MockHttpServletResponse(), Any(), homePage)
        assertNull(homePage.model["resultActions"])
    }

    // --- POST /status for fetch ---

    @Test
    fun `status changes answer the title's new state`() {
        val heat = movies.save(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat"))
        val severance = shows.save(TvShowFactory.aTvShow(tmdbId = 95396, originalName = "Severance"))
        val controller = StatusJsonController(
            SetWatchStatusHandler(statuses, movies, shows),
            SeenStateReader(statuses, InMemoryRatingRepository(), InMemoryTvRatingRepository()),
            BrowseBarViewsTest.testMessageSource(),
        )
        val user = UserFactory.aUser(id = 1)

        val on = controller.setStatus(user, "movie", heat.id!!.value, "want_to_watch").body!!
        assertTrue(on.onWatchlist)
        assertEquals("want_to_watch", on.status)
        assertEquals(0, on.seenDelta)

        statuses.save(MediaStatus(User.Id(1), MediaType.movie, heat.id!!.value, WatchStatus.WATCHED, 1L))
        val moved = controller.setStatus(user, "movie", heat.id!!.value, "want_to_watch").body!!
        assertEquals(-1, moved.seenDelta, "Watched -> Want to watch stops counting as seen")

        val watching = controller.setStatus(user, "tvshow", severance.id!!.value, "watching").body!!
        assertEquals("watching", watching.status)
        assertFalse(watching.seen)

        val cleared = controller.setStatus(user, "tvshow", severance.id!!.value, "").body!!
        assertNull(cleared.status)

        assertEquals(HttpStatus.BAD_REQUEST, controller.setStatus(user, "book", 1, "watched").statusCode)
        assertEquals(HttpStatus.BAD_REQUEST, controller.setStatus(user, "movie", heat.id!!.value, "binge").statusCode)
        assertEquals(HttpStatus.NOT_FOUND, controller.setStatus(user, "movie", 999, "watched").statusCode)
    }
}
