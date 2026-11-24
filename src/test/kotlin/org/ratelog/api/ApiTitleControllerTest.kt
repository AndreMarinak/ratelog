package org.ratelog.api

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.api.media.MediaImporter
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.extras.ExternalScores
import org.ratelog.extras.gettitleinfo.GetTitleInfo
import org.ratelog.extras.gettitleinfo.GetTitleInfoError
import org.ratelog.extras.gettitleinfo.GetTitleInfoHandler
import org.ratelog.extras.gettitleinfo.TitleInfo
import org.ratelog.movie.MovieDescription
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryListMembershipRepository
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
import org.ratelog.test.UserFactory
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tmdb.TmdbError
import org.ratelog.tvshow.TvDescription
import org.springframework.http.HttpStatus
import java.time.Instant

class ApiTitleControllerTest {

    private val tmdbClient: TmdbClient = mock()
    private val getTitleInfoHandler: GetTitleInfoHandler = mock()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val ratings = InMemoryRatingRepository()
    private val tvRatings = InMemoryTvRatingRepository()
    private val lists = InMemoryCustomListRepository()
    private val controller = ApiTitleController(
        MediaImporter(tmdbClient, movies, InMemoryMovieDescriptionRepository(), shows, InMemoryTvDescriptionRepository()),
        getTitleInfoHandler,
        ratings,
        tvRatings,
        InMemoryListMembershipRepository(lists),
        org.ratelog.test.InMemoryOfficialTitleListsRepository(
            lists,
            org.ratelog.test.InMemoryOfficialListRepository(lists),
            org.ratelog.test.InMemorySavedListRepository(lists),
        ),
    )
    private val user = UserFactory.aUser(id = 1)

    private val scores = ExternalScores(imdbId = "tt0133093", imdbRating = 8.7, imdbVotes = 2_134_567, rtCritics = 83, rtAudience = 85, metacritic = 73, letterboxd = 4.2)

    @BeforeEach
    fun setUp() {
        whenever(tmdbClient.movieTranslations(any(), any())).thenReturn(emptyList<MovieDescription>().right())
        whenever(tmdbClient.tvTranslations(any(), any())).thenReturn(emptyList<TvDescription>().right())
        whenever(tmdbClient.movieDetails(TmdbId(603))).thenReturn(
            MovieFactory.aMovie(tmdbId = 603, originalTitle = "The Matrix", releaseYear = 1999, posterPath = "/p.jpg").right()
        )
        whenever(tmdbClient.tvShowDetails(TmdbId(1399))).thenReturn(
            TvShowFactory.aTvShow(tmdbId = 1399, originalName = "Game of Thrones", firstAirYear = 2011).right()
        )
    }

    @Test
    fun `given a rated movie then returns extras, external scores and the user's rating`() {
        // Given
        whenever(getTitleInfoHandler.handle(GetTitleInfo(MediaType.movie, TmdbId(603)))).thenReturn(
            TitleInfo(
                type = MediaType.movie, tmdbId = 603, title = "The Matrix", tmdbVoteAverage = 8.2, tmdbVoteCount = 26_543,
                runtimeMinutes = 136, numberOfSeasons = null, trailerYoutubeKey = "vKQi3bBA1y8", imdbId = "tt0133093",
                scores = scores, scoresFetchedAt = Instant.parse("2026-11-22T12:00:00Z"),
            ).right()
        )
        controller.title(user, "movie", 603)
        val movie = movies.findByTmdbId(TmdbId(603))!!
        ratings.save(RatingFactory.aRating(movieId = movie.id!!, userId = user.id!!, directing = 9.0, acting = 9.0, reviewTitle = "Whoa"))

        // When
        val dto = controller.title(user, "film", 603)

        // Then
        assertEquals("The Matrix", dto.title)
        assertEquals(1999, dto.year)
        assertEquals("movie", dto.type)
        assertEquals("/movie/603", dto.url)
        assertEquals(8.2, dto.tmdbVoteAverage)
        assertEquals(136, dto.runtimeMinutes)
        assertNull(dto.episodeRuntimeMinutes)
        assertEquals("https://www.youtube.com/watch?v=vKQi3bBA1y8", dto.trailerUrl)
        assertEquals("https://www.imdb.com/title/tt0133093/", dto.imdbUrl)
        assertEquals(83, dto.externalScores?.rottenTomatoesCritics)
        assertEquals(2_134_567L, dto.externalScores?.imdbVotes)
        assertEquals("2026-11-22T12:00:00Z", dto.externalScores?.fetchedAt)
        assertEquals(6.6, dto.userRating?.score)
        assertEquals("Whoa", dto.userRating?.reviewTitle)
        assertNull(dto.userRating?.seasons)
    }

    @Test
    fun `given a show then reports episode runtime, seasons and season ratings`() {
        // Given
        whenever(getTitleInfoHandler.handle(GetTitleInfo(MediaType.tvshow, TmdbId(1399)))).thenReturn(
            TitleInfo(
                type = MediaType.tvshow, tmdbId = 1399, title = "Game of Thrones", tmdbVoteAverage = 8.5, tmdbVoteCount = 25_000,
                runtimeMinutes = 60, numberOfSeasons = 8, trailerYoutubeKey = null, imdbId = "tt0944947",
                scores = null, scoresFetchedAt = null,
            ).right()
        )
        controller.title(user, "tv", 1399)
        val show = shows.findByTmdbId(TmdbId(1399))!!
        tvRatings.save(
            TvRatingFactory.aTvRating(
                tvShowId = show.id!!, userId = user.id!!,
                seasonRatings = listOf(
                    TvRatingFactory.aSeasonRating(tvShowId = show.id, seasonNumber = 2, userId = user.id, directing = 10.0),
                    TvRatingFactory.aSeasonRating(tvShowId = show.id, seasonNumber = 1, userId = user.id, reviewText = "Great start"),
                ),
            )
        )

        // When
        val dto = controller.title(user, "tv", 1399)

        // Then
        assertEquals("tv", dto.type)
        assertEquals("/tv/1399", dto.url)
        assertNull(dto.runtimeMinutes)
        assertEquals(60, dto.episodeRuntimeMinutes)
        assertEquals(8, dto.numberOfSeasons)
        assertNull(dto.trailerUrl)
        assertNull(dto.externalScores)
        assertEquals(listOf(1, 2), dto.userRating?.seasons?.map { it.season })
        assertEquals("Great start", dto.userRating?.seasons?.first()?.reviewText)
    }

    @Test
    fun `given extras are unavailable then still returns the title with null extras`() {
        whenever(getTitleInfoHandler.handle(any())).thenReturn(GetTitleInfoError.NotAvailable.left())

        val dto = controller.title(user, "movie", 603)

        assertEquals("The Matrix", dto.title)
        assertNull(dto.tmdbVoteAverage)
        assertNull(dto.externalScores)
        assertNull(dto.userRating)
        assertEquals(emptyList<TitleListDto>(), dto.inLists)
    }

    @Test
    fun `given the title is in some of your lists then inLists names them with its rank`() {
        whenever(getTitleInfoHandler.handle(any())).thenReturn(GetTitleInfoError.NotAvailable.left())
        controller.title(user, "movie", 603)
        val matrix = movies.findByTmdbId(TmdbId(603))!!.id!!.value
        fun list(name: String, createdAt: Long, owner: Long, vararg mediaIds: Long): CustomList {
            val list = lists.save(CustomList(null, org.ratelog.user.User.Id(owner), ListName(name), false, createdAt))
            mediaIds.forEachIndexed { i, id -> lists.addItem(CustomListItem(null, list.id!!, id, MediaType.movie, i + 1, createdAt)) }
            return list
        }
        val sciFi = list("Sci-fi canon", 2_000, 1, 900, 901, matrix)
        val rewatch = list("Rewatch", 3_000, 1, matrix)
        list("Without it", 4_000, 1, 900)
        list("Someone else's", 5_000, 2, matrix)

        val dto = controller.title(user, "movie", 603)

        assertEquals(
            listOf(
                TitleListDto(rewatch.id!!.value, "Rewatch", 1, "/list/${rewatch.id!!.value}"),
                TitleListDto(sciFi.id!!.value, "Sci-fi canon", 3, "/list/${sciFi.id!!.value}"),
            ),
            dto.inLists,
            "your own lists only, newest first",
        )
    }

    @Test
    fun `given TMDB is not configured then fails with tmdb_not_configured`() {
        whenever(tmdbClient.movieDetails(TmdbId(550))).thenReturn(TmdbError.ApiKeyMissing.left())

        val error = assertThrows<ApiException> { controller.title(user, "movie", 550) }

        assertEquals("tmdb_not_configured", error.code)
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status)
    }

    @Test
    fun `given an unknown title then fails with not_found`() {
        whenever(tmdbClient.movieDetails(TmdbId(550))).thenReturn(TmdbError.MovieNotFound.left())

        val error = assertThrows<ApiException> { controller.title(user, "movie", 550) }

        assertEquals("not_found", error.code)
    }

    @Test
    fun `given a bad type or id then fails with a 400`() {
        assertEquals("invalid_type", assertThrows<ApiException> { controller.title(user, "book", 603) }.code)
        assertEquals("invalid_parameter", assertThrows<ApiException> { controller.title(user, "movie", 0) }.code)
    }
}
