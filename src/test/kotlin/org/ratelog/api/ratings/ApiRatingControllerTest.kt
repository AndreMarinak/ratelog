package org.ratelog.api.ratings

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.TmdbId
import org.ratelog.Title
import org.ratelog.api.ApiException
import org.ratelog.api.media.MediaImporter
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieDescription
import org.ratelog.movie.rating.add.AddRatingHandler
import org.ratelog.movie.rating.delete.DeleteRatingHandler
import org.ratelog.movie.rating.update.UpdateRatingHandler
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.test.UserFactory
import org.ratelog.tvshow.rating.show.DeleteShowRatingHandler
import org.ratelog.tvshow.rating.show.RateShowHandler
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tmdb.TmdbError
import org.ratelog.tvshow.TvDescription
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.addseason.AddSeasonRatingHandler
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRatingHandler
import org.ratelog.tvshow.rating.updateseason.UpdateSeasonRatingHandler
import org.springframework.http.HttpStatus

class ApiRatingControllerTest {

    private val tmdbClient: TmdbClient = mock()
    private val movies = InMemoryMovieRepository()
    private val movieDescriptions = InMemoryMovieDescriptionRepository()
    private val shows = InMemoryTvShowRepository()
    private val showDescriptions = InMemoryTvDescriptionRepository()
    private val ratings = InMemoryRatingRepository()
    private val tvRatings = InMemoryTvRatingRepository()
    private val user = UserFactory.aUser(id = 1)

    private val controller = ApiRatingController(
        mediaImporter = MediaImporter(tmdbClient, movies, movieDescriptions, shows, showDescriptions),
        ratingRepository = ratings,
        tvRatingRepository = tvRatings,
        tvShowRepository = shows,
        addRatingHandler = AddRatingHandler(ratings),
        updateRatingHandler = UpdateRatingHandler(ratings),
        deleteRatingHandler = DeleteRatingHandler(movies, ratings),
        addSeasonRatingHandler = AddSeasonRatingHandler(tvRatings),
        updateSeasonRatingHandler = UpdateSeasonRatingHandler(tvRatings),
        deleteSeasonRatingHandler = DeleteSeasonRatingHandler(shows, tvRatings),
        rateShowHandler = RateShowHandler(shows, tvRatings),
        deleteShowRatingHandler = DeleteShowRatingHandler(tvRatings),
    )

    private fun givenLocalMovie(tmdbId: Int = 949, title: String = "Heat") {
        movies.save(MovieFactory.aMovie(tmdbId = tmdbId, originalTitle = title, releaseYear = 1995))
        movieDescriptions.saveAll(listOf(MovieDescription(null, TmdbId(tmdbId), Lang.en, Title(title), null)))
    }

    private fun givenLocalShow(tmdbId: Int = 1396, name: String = "Breaking Bad", lastSeason: Int? = 5) {
        shows.save(TvShowFactory.aTvShow(tmdbId = tmdbId, originalName = name, lastSeasonNumber = lastSeason))
        showDescriptions.saveAll(listOf(TvDescription(null, TmdbId(tmdbId), Lang.en, Title(name), null)))
    }

    private fun movieId(tmdbId: Int): Movie.Id = movies.findByTmdbId(TmdbId(tmdbId))!!.id!!

    private fun showId(tmdbId: Int): TvShow.Id = shows.findByTmdbId(TmdbId(tmdbId))!!.id!!

    private fun assertApiError(status: HttpStatus, code: String, block: () -> Unit) {
        val e = assertThrows<ApiException> { block() }
        assertEquals(status, e.status)
        assertEquals(code, e.code)
    }

    @Test
    fun `PUT movie with only a score creates a quick rating, fetching the title from TMDB`() {
        whenever(tmdbClient.movieDetails(TmdbId(949))).thenReturn(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseYear = 1995).right())
        whenever(tmdbClient.movieTranslations(TmdbId(949), Title("Heat"))).thenReturn(emptyList<MovieDescription>().right())

        val response = controller.rateMovie(user, 949, RatingRequest(score = 8.5, reviewText = "Great heist"))

        assertEquals(HttpStatus.CREATED, response.statusCode)
        val body = response.body!!
        assertEquals("quick", body.mode)
        assertEquals(8.5, body.score)
        assertNull(body.categories)
        assertEquals("Heat", body.title)
        assertEquals("Great heist", body.reviewText)
        assertTrue(ratings.findByMovieIdAndUserId(movieId(949), user.id!!)!!.isQuick)
    }

    @Test
    fun `PUT movie again with categories switches to detailed and keeps the review when it is left out`() {
        givenLocalMovie()
        controller.rateMovie(user, 949, RatingRequest(score = 8.5, reviewTitle = "Title", reviewText = "Text"))

        val response = controller.rateMovie(user, 949, RatingRequest(directing = 9.0, cinematography = 8.0, acting = 9.0, soundtrack = 7.0, screenplay = 8.0))

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertEquals("detailed", body.mode)
        assertEquals(8.2, body.score)
        assertEquals(CategoriesDto(9.0, 8.0, 9.0, 7.0, 8.0), body.categories)
        assertEquals("Title", body.reviewTitle)
        assertEquals("Text", body.reviewText)
    }

    @Test
    fun `PUT movie with an empty review field clears it`() {
        givenLocalMovie()
        controller.rateMovie(user, 949, RatingRequest(score = 7.0, reviewTitle = "Title", reviewText = "Text"))

        val body = controller.rateMovie(user, 949, RatingRequest(score = 7.0, reviewText = "")).body!!

        assertEquals("Title", body.reviewTitle)
        assertNull(body.reviewText)
    }

    @Test
    fun `PUT movie with a score and some categories is rejected`() {
        givenLocalMovie()

        assertApiError(HttpStatus.BAD_REQUEST, "invalid_rating") {
            controller.rateMovie(user, 949, RatingRequest(score = 7.0, directing = 8.0))
        }
        assertApiError(HttpStatus.BAD_REQUEST, "invalid_rating") {
            controller.rateMovie(user, 949, RatingRequest(directing = 8.0, acting = 7.0))
        }
        assertApiError(HttpStatus.BAD_REQUEST, "invalid_rating") {
            controller.rateMovie(user, 949, RatingRequest())
        }
        assertNull(ratings.findByMovieIdAndUserId(movieId(949), user.id!!))
    }

    @Test
    fun `PUT movie with an out of range score is rejected`() {
        givenLocalMovie()

        assertApiError(HttpStatus.BAD_REQUEST, "invalid_score") {
            controller.rateMovie(user, 949, RatingRequest(score = 11.0))
        }
    }

    @Test
    fun `PUT movie that TMDB does not know returns not found`() {
        whenever(tmdbClient.movieDetails(TmdbId(999999))).thenReturn(TmdbError.MovieNotFound.left())

        assertApiError(HttpStatus.NOT_FOUND, "not_found") {
            controller.rateMovie(user, 999999, RatingRequest(score = 7.0))
        }
    }

    @Test
    fun `DELETE movie removes the rating, and a second DELETE is not found`() {
        givenLocalMovie()
        controller.rateMovie(user, 949, RatingRequest(score = 7.0))

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteMovie(user, 949).statusCode)

        assertNull(ratings.findByMovieIdAndUserId(movieId(949), user.id!!))
        assertApiError(HttpStatus.NOT_FOUND, "not_found") { controller.deleteMovie(user, 949) }
        assertApiError(HttpStatus.NOT_FOUND, "not_found") { controller.movie(user, 949) }
    }

    @Test
    fun `PUT season with only a score creates a quick season rating and returns the show`() {
        givenLocalShow()

        val response = controller.rateSeason(user, 1396, 2, RatingRequest(score = 9.5))

        assertEquals(HttpStatus.CREATED, response.statusCode)
        val body = response.body!!
        assertEquals("tv", body.type)
        assertEquals(9.5, body.score)
        assertEquals(listOf(2), body.seasons.map { it.season })
        assertEquals("quick", body.seasons.single().mode)
        assertNull(body.seasons.single().categories)
    }

    @Test
    fun `PUT season mixes quick and detailed seasons into the show score`() {
        givenLocalShow()
        controller.rateSeason(user, 1396, 1, RatingRequest(score = 9.0))

        controller.rateSeason(user, 1396, 2, RatingRequest(directing = 6.0, cinematography = 6.0, acting = 6.0, soundtrack = 6.0, screenplay = 6.0))
        val response = controller.rateSeason(user, 1396, 1, RatingRequest(score = 8.0))

        assertEquals(HttpStatus.OK, response.statusCode)
        val body = response.body!!
        assertEquals(7.0, body.score)
        assertEquals(listOf("quick", "detailed"), body.seasons.map { it.mode })
    }

    @Test
    fun `PUT season beyond the show's last season or below 1 is rejected`() {
        givenLocalShow(lastSeason = 5)

        assertApiError(HttpStatus.BAD_REQUEST, "invalid_season") { controller.rateSeason(user, 1396, 6, RatingRequest(score = 8.0)) }
        assertApiError(HttpStatus.BAD_REQUEST, "invalid_season") { controller.rateSeason(user, 1396, 0, RatingRequest(score = 8.0)) }
        assertNull(tvRatings.findByTvShowIdAndUserId(showId(1396), user.id!!))
    }

    @Test
    fun `PUT season with an out of range category is rejected`() {
        givenLocalShow()

        assertApiError(HttpStatus.BAD_REQUEST, "invalid_score") {
            controller.rateSeason(user, 1396, 1, RatingRequest(directing = 12.0, cinematography = 6.0, acting = 6.0, soundtrack = 6.0, screenplay = 6.0))
        }
    }

    @Test
    fun `DELETE season removes it, and removes the show rating with the last season`() {
        givenLocalShow()
        controller.rateSeason(user, 1396, 1, RatingRequest(score = 9.0))
        controller.rateSeason(user, 1396, 2, RatingRequest(score = 7.0))

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteSeason(user, 1396, 1).statusCode)
        assertEquals(7.0, controller.show(user, 1396).score)

        controller.deleteSeason(user, 1396, 2)
        assertNull(tvRatings.findByTvShowIdAndUserId(showId(1396), user.id!!))
        assertApiError(HttpStatus.NOT_FOUND, "not_found") { controller.deleteSeason(user, 1396, 2) }
    }

    @Test
    fun `GET ratings lists the user's ratings, filtered by type`() {
        givenLocalMovie(949, "Heat")
        givenLocalMovie(500, "Reservoir Dogs")
        givenLocalShow()
        controller.rateMovie(user, 949, RatingRequest(score = 8.0))
        controller.rateMovie(user, 500, RatingRequest(directing = 9.0, cinematography = 9.0, acting = 9.0, soundtrack = 9.0, screenplay = 9.0))
        controller.rateSeason(user, 1396, 1, RatingRequest(score = 9.0))
        val otherUser = UserFactory.aUser(id = 2)
        controller.rateMovie(otherUser, 949, RatingRequest(score = 2.0))

        val all = controller.list(user, null)
        val moviesOnly = controller.list(user, "movie")

        assertEquals(listOf("Reservoir Dogs", "Heat"), all.movies!!.map { it.title })
        assertEquals(listOf("detailed", "quick"), all.movies!!.map { it.mode })
        assertEquals(listOf("Breaking Bad"), all.tv!!.map { it.title })
        assertEquals(2, moviesOnly.movies!!.size)
        assertNull(moviesOnly.tv)
        assertApiError(HttpStatus.BAD_REQUEST, "invalid_type") { controller.list(user, "book") }
    }

    @Test
    fun `GET a movie rating that does not exist is not found`() {
        givenLocalMovie()

        assertApiError(HttpStatus.NOT_FOUND, "not_found") { controller.movie(user, 949) }
        assertApiError(HttpStatus.NOT_FOUND, "not_found") { controller.movie(user, 12345) }
    }

    @Test
    fun `invalid tmdb ids are rejected`() {
        assertApiError(HttpStatus.BAD_REQUEST, "invalid_parameter") { controller.rateMovie(user, 0, RatingRequest(score = 5.0)) }
        assertApiError(HttpStatus.BAD_REQUEST, "invalid_parameter") { controller.deleteMovie(user, -3) }
    }

    // --- Fork (2026-11-23): a whole-show score ---

    @Test
    fun `PUT tv rates the show as a whole, GET returns it as the show's score`() {
        givenLocalShow()

        val created = controller.rateShow(user, 1396, ShowRatingRequest(score = 8.0))

        assertEquals(HttpStatus.CREATED, created.statusCode)
        assertEquals(8.0, created.body!!.score)
        assertEquals("show", created.body!!.scoreSource)
        assertEquals(8.0, created.body!!.showScore)
        assertTrue(created.body!!.seasons.isEmpty())
        assertEquals(HttpStatus.OK, controller.rateShow(user, 1396, ShowRatingRequest(score = 7.5)).statusCode)
        val shown = controller.show(user, 1396)
        assertEquals(7.5, shown.score)
        assertEquals("show", shown.scoreSource)
        assertEquals(1, controller.list(user, "tv").tv!!.size, "listed with the other rated shows")
    }

    @Test
    fun `rated seasons replace the whole-show score with their average, and deleting them brings it back`() {
        givenLocalShow()
        controller.rateShow(user, 1396, ShowRatingRequest(score = 8.0))

        controller.rateSeason(user, 1396, 1, RatingRequest(score = 6.0))
        val averaged = controller.rateSeason(user, 1396, 2, RatingRequest(score = 7.0)).body!!
        assertEquals(6.5, averaged.score)
        assertEquals("seasons", averaged.scoreSource)
        assertEquals(8.0, averaged.showScore, "kept, unused")

        controller.deleteSeason(user, 1396, 1)
        controller.deleteSeason(user, 1396, 2)
        val back = controller.show(user, 1396)
        assertEquals(8.0, back.score)
        assertEquals("show", back.scoreSource)
    }

    @Test
    fun `DELETE tv removes only the whole-show score, 404 when there is none`() {
        givenLocalShow()
        controller.rateShow(user, 1396, ShowRatingRequest(score = 8.0))
        controller.rateSeason(user, 1396, 1, RatingRequest(score = 6.0))

        assertEquals(HttpStatus.NO_CONTENT, controller.deleteShowRating(user, 1396).statusCode)
        val left = controller.show(user, 1396)
        assertEquals(6.0, left.score)
        assertNull(left.showScore)
        assertApiError(HttpStatus.NOT_FOUND, "not_found") { controller.deleteShowRating(user, 1396) }

        controller.deleteSeason(user, 1396, 1)
        assertNull(tvRatings.findByTvShowIdAndUserId(showId(1396), user.id!!), "nothing left: the rating is gone")
    }

    @Test
    fun `PUT tv without a score or out of range is rejected`() {
        givenLocalShow()

        assertApiError(HttpStatus.BAD_REQUEST, "invalid_score") { controller.rateShow(user, 1396, ShowRatingRequest()) }
        assertApiError(HttpStatus.BAD_REQUEST, "invalid_score") { controller.rateShow(user, 1396, ShowRatingRequest(score = 11.0)) }
        assertNull(tvRatings.findByTvShowIdAndUserId(showId(1396), user.id!!))
    }
}
