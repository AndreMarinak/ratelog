package org.ratelog.tvshow.detail

import arrow.core.right
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.*
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tvshow.TvDescription
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User
import java.time.Instant
import java.time.LocalDate

class DetailTvShowHandlerTest {

    private val tmdbClient: TmdbClient = mock()
    private lateinit var tvShowRepository: InMemoryTvShowRepository
    private lateinit var tvDescriptionRepository: InMemoryTvDescriptionRepository
    private lateinit var tvRatingRepository: InMemoryTvRatingRepository
    private lateinit var customListRepository: InMemoryCustomListRepository
    private lateinit var handler: DetailTvShowHandler

    @BeforeEach
    fun setUp() {
        tvShowRepository = InMemoryTvShowRepository()
        tvDescriptionRepository = InMemoryTvDescriptionRepository()
        tvRatingRepository = InMemoryTvRatingRepository()
        customListRepository = InMemoryCustomListRepository()
        handler = DetailTvShowHandler(tmdbClient, tvShowRepository, tvDescriptionRepository, tvRatingRepository, customListRepository)
        whenever(tmdbClient.tvWatchProviders(any(), any())).thenReturn(WatchProviders(WatchRegion("ES"), emptyList(), null).right())
    }

    @Test
    fun `should return tv show detail with translated name when description exists for user lang`() {
        val tmdbShow = TvShowFactory.aTvShow(
            tmdbId = 123,
            id = 123,
            originalName = "Original Name",
            firstAirDate = LocalDate.parse("2023-01-15"),
            posterPath = "/poster.jpg",
            tmdbVoteAverage = 7.5,
            genres = listOf(Genre.DRAMA),
            lastSeasonNumber = 2,
        )
        tvShowRepository.save(tmdbShow)
        tvDescriptionRepository.saveAll(listOf(
            TvDescription(null,TmdbId(123), Lang.en, Title("Translated Name"), Overview("Translated overview"))
        ))

        whenever(tmdbClient.tvShowDetails(TmdbId(123))).thenReturn(tmdbShow.right())

        val query = GetTvShowDetail(User.Id(1), TmdbId(123), Lang.en)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        result.fold(
            { fail("Should not return error") },
            { detail ->
                assertEquals("Translated Name", detail.title)
                assertEquals("Translated overview", detail.overview)
                assertEquals("Original Name", detail.originalTitle)
                assertEquals(2, detail.seasons.size)
            }
        )
    }

    @Test
    fun `should fallback to original name when no description exists for user lang`() {
        val tmdbShow = TvShowFactory.aTvShow(
            tmdbId = 123,
            id = 123,
            originalName = "Original Name",
            firstAirDate = LocalDate.parse("2023-01-15"),
            lastSeasonNumber = 2,
        )
        tvShowRepository.save(tmdbShow)

        val translations = listOf(
            TvDescription(null,TmdbId(123), Lang.en, Title("EN Name"), Overview("EN Overview"))
        )
        whenever(tmdbClient.tvShowDetails(TmdbId(123))).thenReturn(tmdbShow.right())
        whenever(tmdbClient.tvTranslations(TmdbId(123), Title("Original Name"))).thenReturn(translations.right())

        val query = GetTvShowDetail(User.Id(1), TmdbId(123), Lang.es)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        result.fold(
            { fail("Should not return error") },
            { detail ->
                assertEquals("Original Name", detail.title)
                assertNull(detail.overview)
            }
        )
    }

    @Test
    fun `should save tv show to repository when fetching details`() {
        val tmdbShow = TvShowFactory.aTvShow(
            tmdbId = 123,
            id = 123,
            originalName = "Test Show",
            firstAirDate = LocalDate.parse("2023-01-15"),
        )
        whenever(tmdbClient.tvShowDetails(TmdbId(123))).thenReturn(tmdbShow.right())
        whenever(tmdbClient.tvTranslations(TmdbId(123), Title("Test Show"))).thenReturn(emptyList<TvDescription>().right())

        val query = GetTvShowDetail(User.Id(1), TmdbId(123), Lang.en)
        handler.handle(query)

        val savedShow = tvShowRepository.findByTmdbId(TmdbId(123))
        assertNotNull(savedShow)
        assertEquals("Test Show", savedShow!!.originalName.value)
    }

    @Test
    fun `should return null overall score when show has no ratings`() {
        val tmdbShow = TvShowFactory.aTvShow(
            tmdbId = 123,
            id = 123,
            originalName = "Test Show",
            firstAirDate = LocalDate.parse("2023-01-15"),
        )
        tvShowRepository.save(tmdbShow)
        tvDescriptionRepository.saveAll(listOf(
            TvDescription(null,TmdbId(123), Lang.en, Title("Test Show"), null)
        ))

        whenever(tmdbClient.tvShowDetails(TmdbId(123))).thenReturn(tmdbShow.right())

        val query = GetTvShowDetail(User.Id(1), TmdbId(123), Lang.en)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        result.fold(
            { fail("Should not return error") },
            { detail ->
                assertNull(detail.overallScore)
            }
        )
    }

    @Test
    fun `should filter out season 0 from seasons list`() {
        val tmdbShow = TvShowFactory.aTvShow(
            tmdbId = 123,
            id = 123,
            originalName = "Test Show",
            firstAirDate = LocalDate.parse("2023-01-15"),
            lastSeasonNumber = 2,
        )
        tvShowRepository.save(tmdbShow)
        tvDescriptionRepository.saveAll(listOf(
            TvDescription(null,TmdbId(123), Lang.en, Title("Test Show"), null)
        ))

        whenever(tmdbClient.tvShowDetails(TmdbId(123))).thenReturn(tmdbShow.right())

        val query = GetTvShowDetail(User.Id(1), TmdbId(123), Lang.en)
        val result = handler.handle(query)

        assertTrue(result.isRight())
        result.fold(
            { fail("Should not return error") },
            { detail ->
                assertEquals(2, detail.seasons.size)
                assertEquals(1, detail.seasons[0].seasonNumber)
                assertEquals(2, detail.seasons[1].seasonNumber)
            }
        )
    }

    @Test
    fun `should persist translations when no descriptions exist`() {
        val tmdbShow = TvShowFactory.aTvShow(
            tmdbId = 123,
            id = 123,
            originalName = "Test Show",
            firstAirDate = LocalDate.parse("2023-01-15"),
            lastSeasonNumber = 2,
        )
        val translations = listOf(
            TvDescription(null,TmdbId(123), Lang.en, Title("EN Name"), Overview("EN Overview")),
            TvDescription(null,TmdbId(123), Lang.es, Title("ES Name"), Overview("ES Overview")),
        )
        whenever(tmdbClient.tvShowDetails(TmdbId(123))).thenReturn(tmdbShow.right())
        whenever(tmdbClient.tvTranslations(TmdbId(123), Title("Test Show"))).thenReturn(translations.right())

        val query = GetTvShowDetail(User.Id(1), TmdbId(123), Lang.en)
        handler.handle(query)

        val saved = tvDescriptionRepository.findAllByTmdbId(TmdbId(123))
        assertEquals(2, saved.size)
    }

    @Test
    fun `should not fetch translations when descriptions already exist`() {
        val tmdbShow = TvShowFactory.aTvShow(
            tmdbId = 123,
            id = 123,
            originalName = "Test Show",
            firstAirDate = LocalDate.parse("2023-01-15"),
            lastSeasonNumber = 2,
        )
        tvShowRepository.save(tmdbShow)
        tvDescriptionRepository.saveAll(listOf(
            TvDescription(null,TmdbId(123), Lang.en, Title("EN Name"), Overview("EN Overview"))
        ))

        whenever(tmdbClient.tvShowDetails(TmdbId(123))).thenReturn(tmdbShow.right())

        val query = GetTvShowDetail(User.Id(1), TmdbId(123), Lang.en)
        handler.handle(query)

        verify(tmdbClient, org.mockito.kotlin.never()).tvTranslations(TmdbId(123), Title("a-title"))
    }

    @Test
    fun `should flag quick seasons and keep categories for detailed ones`() {
        val tmdbShow = TvShowFactory.aTvShow(id = 123, tmdbId = 123, originalName = "Test Show", lastSeasonNumber = 3)
        tvShowRepository.save(tmdbShow)
        tvDescriptionRepository.saveAll(listOf(TvDescription(null, TmdbId(123), Lang.en, Title("Test Show"), null)))
        tvRatingRepository.save(
            TvRatingFactory.aTvRating(
                id = 1, tvShowId = TvShow.Id(123), userId = User.Id(1),
                seasonRatings = listOf(
                    TvRatingFactory.aSeasonRating(tvShowId = TvShow.Id(123), seasonNumber = 1, userId = User.Id(1), directing = 6.0, cinematography = 6.0, acting = 6.0, soundtrack = 6.0, screenplay = 6.0),
                    TvRatingFactory.aQuickSeasonRating(tvShowId = TvShow.Id(123), seasonNumber = 2, userId = User.Id(1), score = 9.0),
                ),
            )
        )
        whenever(tmdbClient.tvShowDetails(TmdbId(123))).thenReturn(tmdbShow.right())

        val detail = handler.handle(GetTvShowDetail(User.Id(1), TmdbId(123), Lang.en)).getOrNull()!!

        val (detailed, quick, unrated) = detail.seasons
        assertFalse(detailed.rating!!.quickRating)
        assertEquals(6.0, detailed.rating!!.directing)
        assertTrue(quick.rating!!.quickRating)
        assertNull(quick.rating!!.directing)
        assertEquals(9.0, quick.rating!!.score)
        assertNull(unrated.rating)
        assertEquals(7.5, detail.overallScore)
    }
}
