package org.ratelog.movie.top

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ratelog.*
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieDescription
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.RatingFactory
import org.ratelog.user.User
import java.time.Instant

class TopMovieHandlerTest {

    private lateinit var ratingRepository: InMemoryRatingRepository
    private lateinit var movieRepository: InMemoryMovieRepository
    private lateinit var movieDescriptionRepository: InMemoryMovieDescriptionRepository
    private lateinit var handler: TopMovieHandler

    @BeforeEach
    fun setUp() {
        ratingRepository = InMemoryRatingRepository()
        movieRepository = InMemoryMovieRepository()
        movieDescriptionRepository = InMemoryMovieDescriptionRepository()
        handler = TopMovieHandler(ratingRepository, movieRepository, movieDescriptionRepository)
    }

    @Test
    fun `should return empty list when user has no ratings`() {
        val query = TopMovie(User.Id(1), null, null, 10, null, Lang.en)

        val result = handler.handle(query)

        assertEquals(0, result.size)
    }

    @Test
    fun `should return ranked movies when user has ratings`() {
        val movie1 = MovieFactory.aMovie(id = 1, tmdbId = 123, originalTitle = "Movie A")
        val movie2 = MovieFactory.aMovie(id = 2, tmdbId = 456, originalTitle = "Movie B")
        movieRepository.save(movie1)
        movieRepository.save(movie2)
        movieDescriptionRepository.saveAll(listOf(
            MovieDescription(null,TmdbId(123), Lang.en, Title("Movie A"), null),
            MovieDescription(null,TmdbId(456), Lang.en, Title("Movie B"), null),
        ))

        val rating1 = RatingFactory.aRating(id = 1, movieId = Movie.Id(1), userId = User.Id(1), directing = 5.0, cinematography = 5.0, acting = 5.0, soundtrack = 5.0, screenplay = 5.0, createdAt = Instant.now(), reviewTitle = null, reviewText = null)
        val rating2 = RatingFactory.aRating(id = 2, movieId = Movie.Id(2), userId = User.Id(1), directing = 8.0, cinematography = 8.0, acting = 8.0, soundtrack = 8.0, screenplay = 8.0, createdAt = Instant.now(), reviewTitle = null, reviewText = null)
        ratingRepository.save(rating1)
        ratingRepository.save(rating2)

        val query = TopMovie(User.Id(1), null, null, 10, null, Lang.en)
        val result = handler.handle(query)

        assertEquals(2, result.size)
        assertEquals(1, result[0].rank.value)
        assertEquals(8.0, result[0].rating.score!!.value)
        assertEquals(2, result[1].rank.value)
        assertEquals(5.0, result[1].rating.score!!.value)
    }

    @Test
    fun `should respect limit parameter`() {
        val movie = MovieFactory.aMovie(id = 1, tmdbId = 123, originalTitle = "Movie A")
        movieRepository.save(movie)
        movieDescriptionRepository.saveAll(listOf(
            MovieDescription(null,TmdbId(123), Lang.en, Title("Movie A"), null),
        ))

        val rating = RatingFactory.aRating(id = 1, movieId = Movie.Id(1), userId = User.Id(1), directing = 5.0, cinematography = 5.0, acting = 5.0, soundtrack = 5.0, screenplay = 5.0, createdAt = Instant.now(), reviewTitle = null, reviewText = null)
        ratingRepository.save(rating)

        val query = TopMovie(User.Id(1), null, null, 0, null, Lang.en)
        val result = handler.handle(query)

        assertEquals(0, result.size)
    }

    private fun givenMixedRatings() {
        listOf(1L to "Detailed Seven", 2L to "Quick Nine", 3L to "Detailed Eight", 4L to "Quick Six").forEach { (id, title) ->
            movieRepository.save(MovieFactory.aMovie(id = id, tmdbId = 100 + id.toInt(), originalTitle = title))
        }
        ratingRepository.save(RatingFactory.aRating(id = 1, movieId = Movie.Id(1), userId = User.Id(1), directing = 9.0, cinematography = 7.0, acting = 6.0, soundtrack = 6.0, screenplay = 7.0))
        ratingRepository.save(RatingFactory.aQuickRating(id = 2, movieId = Movie.Id(2), userId = User.Id(1), score = 9.0))
        ratingRepository.save(RatingFactory.aRating(id = 3, movieId = Movie.Id(3), userId = User.Id(1), directing = 6.0, cinematography = 8.0, acting = 9.0, soundtrack = 8.0, screenplay = 9.0))
        ratingRepository.save(RatingFactory.aQuickRating(id = 4, movieId = Movie.Id(4), userId = User.Id(1), score = 6.0))
    }

    @Test
    fun `should rank quick and detailed ratings together by overall score`() {
        givenMixedRatings()

        val result = handler.handle(TopMovie(User.Id(1), null, null, 10, null, Lang.en))

        assertEquals(listOf("Quick Nine", "Detailed Eight", "Detailed Seven", "Quick Six"), result.map { it.title })
        assertEquals(listOf(1, 2, 3, 4), result.map { it.rank.value })
    }

    @Test
    fun `should leave quick ratings out when ranking by a category`() {
        givenMixedRatings()

        val result = handler.handle(TopMovie(User.Id(1), null, "directing", 10, null, Lang.en))

        assertEquals(listOf("Detailed Seven", "Detailed Eight"), result.map { it.title })
        assertEquals(listOf(1, 2), result.map { it.rank.value })
    }

    @Test
    fun `should rank by overall score when the category is blank or unknown`() {
        givenMixedRatings()

        val blank = handler.handle(TopMovie(User.Id(1), null, "", 10, null, Lang.en))
        val unknown = handler.handle(TopMovie(User.Id(1), null, "overall", 10, null, Lang.en))

        assertEquals(listOf("Quick Nine", "Detailed Eight", "Detailed Seven", "Quick Six"), blank.map { it.title })
        assertEquals(blank.map { it.title }, unknown.map { it.title })
    }
}
