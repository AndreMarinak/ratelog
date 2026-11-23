package org.ratelog.import.letterboxd

import arrow.core.right
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.*
import org.ratelog.movie.Movie
import org.ratelog.tvshow.TvShow
import org.ratelog.test.*
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tmdb.TmdbGenreResponse
import org.ratelog.tmdb.TmdbMovieResponse
import org.ratelog.tmdb.TmdbSearchResponse
import org.ratelog.tmdb.TmdbTvShowResponse
import org.ratelog.user.User
import java.time.Instant
import java.time.LocalDate

class LetterboxdImportHandlerTest {

    private lateinit var csvParser: LetterboxdCsvParser
    private lateinit var movieRepository: InMemoryMovieRepository
    private lateinit var movieDescriptionRepository: InMemoryMovieDescriptionRepository
    private lateinit var ratingRepository: InMemoryRatingRepository
    private lateinit var tvShowRepository: InMemoryTvShowRepository
    private lateinit var tvDescriptionRepository: InMemoryTvDescriptionRepository
    private lateinit var tvRatingRepository: InMemoryTvRatingRepository
    private lateinit var tmdbClient: TmdbClient
    private lateinit var handler: LetterboxdImportHandler

    @BeforeEach
    fun setUp() {
        csvParser = LetterboxdCsvParser()
        movieRepository = InMemoryMovieRepository()
        movieDescriptionRepository = InMemoryMovieDescriptionRepository()
        ratingRepository = InMemoryRatingRepository()
        tvShowRepository = InMemoryTvShowRepository()
        tvDescriptionRepository = InMemoryTvDescriptionRepository()
        tvRatingRepository = InMemoryTvRatingRepository()
        tmdbClient = mock()
        handler = LetterboxdImportHandler(
            csvParser,
            tmdbClient,
            movieRepository,
            movieDescriptionRepository,
            ratingRepository,
            tvShowRepository,
            tvDescriptionRepository,
            tvRatingRepository,
        )
    }

    @Test
    fun `should skip movie when rating already exists`() {
        val movie = Movie(
            id = Movie.Id(1),
            tmdbId = TmdbId(278),
            originalTitle = Title("The Shawshank Redemption"),
            releaseDate = LocalDate.of(1994, 9, 23),
            releaseYear = 1994,
            posterPath = Url("/poster.jpg"),
            tmdbVoteAverage = 8.7,
            genres = listOf(Genre.DRAMA),
            status = Status.RELEASED,
        )
        movieRepository.save(movie)

        val existingRating = RatingFactory.aRating(
            id = 1,
            movieId = Movie.Id(1),
            userId = User.Id(1),
            directing = 5.0,
            cinematography = 5.0,
            acting = 5.0,
            soundtrack = 5.0,
            screenplay = 5.0,
            createdAt = Instant.now(),
            reviewTitle = null, reviewText = null,
        )
        ratingRepository.save(existingRating)

        val csv = """
            Date,Name,Year,Letterboxd URI,Rating
            2024-01-15,The Shawshank Redemption,1994,https://letterboxd.com/film/the-shawshank-redemption/,4.0
        """.trimIndent()

        val movieSearchResponse = TmdbMovieResponse(
            id = 278,
            title = "The Shawshank Redemption",
            originalTitle = "The Shawshank Redemption",
            releaseDate = "1994-09-23",
            overview = "Great movie",
            posterPath = "/poster.jpg",
            voteAverage = 8.7,
            genres = listOf(TmdbGenreResponse(18, "Drama")),
        )
        val searchResponse = TmdbSearchResponse(results = listOf(movieSearchResponse), totalPages = 1)
        whenever(tmdbClient.searchMovies("The Shawshank Redemption", Lang.en)).thenReturn((searchResponse.results to searchResponse.totalPages).right())

        val command = ImportLetterboxdCommand(
            userId = User.Id(1),
            csvContent = csv.toByteArray(),
        )

        val result = handler.handle(command)

        assertTrue(result.isRight())
        val importResult = result.getOrNull()!!
        assertEquals(0, importResult.importedMovies)
        assertEquals(1, importResult.skippedDuplicates)
    }

    @Test
    fun `should add movie to notFound when not found in TMDB`() {
        val csv = """
            Date,Name,Year,Letterboxd URI,Rating
            2024-01-15,Unknown Movie,2000,https://letterboxd.com/film/unknown/,4.0
        """.trimIndent()

        whenever(tmdbClient.searchMovies("Unknown Movie", Lang.en)).thenReturn((emptyList<TmdbMovieResponse>() to 0).right())
        whenever(tmdbClient.searchTvShows("Unknown Movie", Lang.en)).thenReturn((emptyList<TmdbTvShowResponse>() to 0).right())

        val command = ImportLetterboxdCommand(
            userId = User.Id(1),
            csvContent = csv.toByteArray(),
        )

        val result = handler.handle(command)

        assertTrue(result.isRight())
        val importResult = result.getOrNull()!!
        assertEquals(0, importResult.importedMovies)
        assertEquals(1, importResult.notFound.size)
        assertEquals("Unknown Movie", importResult.notFound[0])
    }

    @Test
    fun `should import a movie as a quick rating with the stars doubled`() {
        movieRepository.save(MovieFactory.aMovie(id = 1, tmdbId = 278, originalTitle = "The Shawshank Redemption", releaseYear = 1994))
        val csv = """
            Date,Name,Year,Letterboxd URI,Rating
            2024-01-15,The Shawshank Redemption,1994,https://letterboxd.com/film/the-shawshank-redemption/,4.5
        """.trimIndent()
        val match = TmdbMovieResponse(id = 278, title = "The Shawshank Redemption", originalTitle = "The Shawshank Redemption", releaseDate = "1994-09-23")
        whenever(tmdbClient.searchMovies("The Shawshank Redemption", Lang.en)).thenReturn((listOf(match) to 1).right())

        val result = handler.handle(ImportLetterboxdCommand(User.Id(1), csv.toByteArray()))

        assertEquals(1, result.getOrNull()!!.importedMovies)
        val rating = ratingRepository.findByMovieIdAndUserId(Movie.Id(1), User.Id(1))!!
        assertTrue(rating.isQuick)
        assertNull(rating.directing)
        assertNull(rating.screenplay)
        assertEquals(9.0, rating.score!!.value)
    }

    @Test
    fun `should import a tv show as quick ratings for every season`() {
        tvShowRepository.save(TvShowFactory.aTvShow(id = 7, tmdbId = 1396, originalName = "Breaking Bad", lastSeasonNumber = 2))
        val csv = """
            Date,Name,Year,Letterboxd URI,Rating
            2024-01-15,Breaking Bad,2008,https://letterboxd.com/film/breaking-bad/,3.5
        """.trimIndent()
        whenever(tmdbClient.searchMovies("Breaking Bad", Lang.en)).thenReturn((emptyList<TmdbMovieResponse>() to 0).right())
        val show = TmdbTvShowResponse(id = 1396, name = "Breaking Bad", originalName = "Breaking Bad", firstAirDate = "2008-01-20")
        whenever(tmdbClient.searchTvShows("Breaking Bad", Lang.en)).thenReturn((listOf(show) to 1).right())

        val result = handler.handle(ImportLetterboxdCommand(User.Id(1), csv.toByteArray()))

        assertEquals(1, result.getOrNull()!!.importedTvShows)
        val rating = tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(7), User.Id(1))!!
        assertEquals(2, rating.seasonRatings.size)
        assertTrue(rating.seasonRatings.all { it.isQuick && it.score.value == 7.0 })
        assertEquals(7.0, rating.score!!.value)
    }
}
