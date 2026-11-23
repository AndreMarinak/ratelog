package org.ratelog.watchlist.getwatchlist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.Overview
import org.ratelog.Title
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieDescription
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.RatingFactory
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus

class GetWatchlistHandlerTest {

    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val movieDescriptions = InMemoryMovieDescriptionRepository()
    private val ratings = InMemoryRatingRepository()
    private val shows = InMemoryTvShowRepository()
    private val tvRatings = InMemoryTvRatingRepository()
    private val handler = GetWatchlistHandler(
        statuses, movies, movieDescriptions, ratings, shows, InMemoryTvDescriptionRepository(), tvRatings,
    )

    private val user = User.Id(1)
    private lateinit var heat: Movie
    private lateinit var alien: Movie
    private lateinit var severance: TvShow

    @BeforeEach
    fun setUp() {
        heat = movies.save(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseYear = 1995, posterPath = "/heat.jpg"))
        alien = movies.save(MovieFactory.aMovie(tmdbId = 348, originalTitle = "Alien", releaseYear = 1979))
        severance = shows.save(TvShowFactory.aTvShow(tmdbId = 95396, originalName = "Severance", firstAirYear = 2022))

        statuses.save(MediaStatus(user, MediaType.movie, heat.id!!.value, WatchStatus.WANT_TO_WATCH, updatedAtEpochMs = 100))
        statuses.save(MediaStatus(user, MediaType.tvshow, severance.id!!.value, WatchStatus.WANT_TO_WATCH, updatedAtEpochMs = 300))
        statuses.save(MediaStatus(user, MediaType.movie, alien.id!!.value, WatchStatus.WATCHED, updatedAtEpochMs = 200))
        // Another user's statuses never show up.
        statuses.save(MediaStatus(User.Id(2), MediaType.movie, alien.id!!.value, WatchStatus.WANT_TO_WATCH, updatedAtEpochMs = 400))
    }

    private fun query(status: WatchStatus?, type: MediaType? = null, lang: Lang = Lang.en) =
        handler.handle(GetWatchlist(user, status, type, lang))

    @Test
    fun `filters by status, newest first, movies and tv together`() {
        val result = query(WatchStatus.WANT_TO_WATCH)

        assertEquals(listOf("Severance", "Heat"), result.items.map { it.title })
        assertEquals(listOf(MediaType.tvshow, MediaType.movie), result.items.map { it.mediaType })
    }

    @Test
    fun `filters by media type`() {
        assertEquals(listOf("Heat"), query(WatchStatus.WANT_TO_WATCH, MediaType.movie).items.map { it.title })
        assertEquals(listOf("Severance"), query(WatchStatus.WANT_TO_WATCH, MediaType.tvshow).items.map { it.title })
    }

    @Test
    fun `null status returns every status`() {
        assertEquals(listOf("Severance", "Alien", "Heat"), query(null).items.map { it.title })
    }

    @Test
    fun `counts every status for the type filter`() {
        val all = query(WatchStatus.WATCHED).counts
        assertEquals(mapOf(WatchStatus.WANT_TO_WATCH to 2, WatchStatus.WATCHING to 0, WatchStatus.WATCHED to 1, WatchStatus.DROPPED to 0), all)

        val moviesOnly = query(WatchStatus.WATCHED, MediaType.movie).counts
        assertEquals(1, moviesOnly[WatchStatus.WANT_TO_WATCH])
        assertEquals(1, moviesOnly[WatchStatus.WATCHED])
    }

    @Test
    fun `items carry poster, year, tmdb id and the user's score if rated`() {
        ratings.save(RatingFactory.aRating(movieId = alien.id!!, userId = user, directing = 9.0, cinematography = 9.0, acting = 9.0, soundtrack = 9.0, screenplay = 9.0))
        tvRatings.save(
            TvRatingFactory.aTvRating(
                tvShowId = severance.id!!, userId = user,
                seasonRatings = listOf(TvRatingFactory.aSeasonRating(tvShowId = severance.id!!, seasonNumber = 1, userId = user, directing = 8.0, cinematography = 8.0, acting = 8.0, soundtrack = 8.0, screenplay = 8.0)),
            )
        )

        val items = query(null).items.associateBy { it.title }

        assertEquals(9.0, items.getValue("Alien").score)
        assertEquals(8.0, items.getValue("Severance").score)
        assertNull(items.getValue("Heat").score)
        assertEquals(949, items.getValue("Heat").tmdbId)
        assertEquals(1995, items.getValue("Heat").year)
        assertEquals("/heat.jpg", items.getValue("Heat").posterPath)
        assertEquals(2022, items.getValue("Severance").year)
    }

    @Test
    fun `another user's rating is not shown as the score`() {
        ratings.save(RatingFactory.aRating(movieId = heat.id!!, userId = User.Id(2)))

        assertNull(query(WatchStatus.WANT_TO_WATCH).items.single { it.title == "Heat" }.score)
    }

    @Test
    fun `uses the translated title in the user's language, falling back to the original`() {
        movieDescriptions.saveAll(listOf(MovieDescription(null, heat.tmdbId, Lang.es, Title("Heat (Fuego contra fuego)"), Overview(""))))

        assertEquals("Heat (Fuego contra fuego)", query(WatchStatus.WANT_TO_WATCH, MediaType.movie, Lang.es).items.single().title)
        assertEquals("Heat", query(WatchStatus.WANT_TO_WATCH, MediaType.movie, Lang.fr).items.single().title)
    }

    @Test
    fun `empty status tab is empty`() {
        val result = query(WatchStatus.DROPPED)

        assertTrue(result.items.isEmpty())
        assertEquals(0, result.counts[WatchStatus.DROPPED])
    }
}
