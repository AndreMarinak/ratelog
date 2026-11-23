package org.ratelog.tvshow.top

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ratelog.*
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.tvshow.TvDescription
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User
import java.time.Instant

class TopTvShowHandlerTest {

    private lateinit var tvRatingRepository: InMemoryTvRatingRepository
    private lateinit var tvShowRepository: InMemoryTvShowRepository
    private lateinit var tvDescriptionRepository: InMemoryTvDescriptionRepository
    private lateinit var handler: TopTvShowHandler

    @BeforeEach
    fun setUp() {
        tvRatingRepository = InMemoryTvRatingRepository()
        tvShowRepository = InMemoryTvShowRepository()
        tvDescriptionRepository = InMemoryTvDescriptionRepository()
        handler = TopTvShowHandler(tvRatingRepository, tvShowRepository, tvDescriptionRepository)
    }

    @Test
    fun `should return empty list when user has no ratings`() {
        val query = TopTvShow(User.Id(1), null, null, 10, null, Lang.en)

        val result = handler.handle(query)

        assertEquals(0, result.size)
    }

    @Test
    fun `should return ranked tv shows when user has ratings`() {
        val show1 = TvShowFactory.aTvShow(id = 1, tmdbId = 123, originalName = "Show A")
        val show2 = TvShowFactory.aTvShow(id = 2, tmdbId = 456, originalName = "Show B")
        tvShowRepository.save(show1)
        tvShowRepository.save(show2)
        tvDescriptionRepository.saveAll(listOf(
            TvDescription(null,TmdbId(123), Lang.en, Title("Show A"), null),
            TvDescription(null,TmdbId(456), Lang.en, Title("Show B"), null),
        ))

        val rating1 = TvRatingFactory.aTvRating(id = 1, tvShowId = TvShow.Id(1), userId = User.Id(1), createdAt = Instant.now())
        val rating2 = TvRatingFactory.aTvRating(id = 2, tvShowId = TvShow.Id(2), userId = User.Id(1), createdAt = Instant.now())
        tvRatingRepository.save(rating1)
        tvRatingRepository.save(rating2)

        val query = TopTvShow(User.Id(1), null, null, 10, null, Lang.en)
        val result = handler.handle(query)

        assertEquals(2, result.size)
        assertEquals(1, result[0].rank.value)
        assertEquals(2, result[1].rank.value)
    }

    @Test
    fun `should respect limit parameter`() {
        val show = TvShowFactory.aTvShow(id = 1, tmdbId = 123, originalName = "Show A")
        tvShowRepository.save(show)
        tvDescriptionRepository.saveAll(listOf(
            TvDescription(null,TmdbId(123), Lang.en, Title("Show A"), null),
        ))

        val rating = TvRatingFactory.aTvRating(id = 1, tvShowId = TvShow.Id(1), userId = User.Id(1), createdAt = Instant.now())
        tvRatingRepository.save(rating)

        val query = TopTvShow(User.Id(1), null, null, 0, null, Lang.en)
        val result = handler.handle(query)

        assertEquals(0, result.size)
    }

    private fun givenMixedShows() {
        listOf(1L to "All Detailed", 2L to "All Quick", 3L to "Mixed").forEach { (id, name) ->
            tvShowRepository.save(TvShowFactory.aTvShow(id = id, tmdbId = 200 + id.toInt(), originalName = name))
        }
        val user = User.Id(1)
        tvRatingRepository.save(
            TvRatingFactory.aTvRating(
                id = 1, tvShowId = TvShow.Id(1), userId = user,
                seasonRatings = listOf(TvRatingFactory.aSeasonRating(tvShowId = TvShow.Id(1), seasonNumber = 1, userId = user, directing = 6.0, cinematography = 7.0, acting = 7.0, soundtrack = 7.0, screenplay = 8.0)),
            )
        )
        tvRatingRepository.save(
            TvRatingFactory.aTvRating(
                id = 2, tvShowId = TvShow.Id(2), userId = user,
                seasonRatings = listOf(
                    TvRatingFactory.aQuickSeasonRating(tvShowId = TvShow.Id(2), seasonNumber = 1, userId = user, score = 9.5),
                    TvRatingFactory.aQuickSeasonRating(tvShowId = TvShow.Id(2), seasonNumber = 2, userId = user, score = 8.5),
                ),
            )
        )
        // Mixed: overall (4 + 9) / 2 = 6.5; directing only counts the detailed season (9.0).
        tvRatingRepository.save(
            TvRatingFactory.aTvRating(
                id = 3, tvShowId = TvShow.Id(3), userId = user,
                seasonRatings = listOf(
                    TvRatingFactory.aSeasonRating(tvShowId = TvShow.Id(3), seasonNumber = 1, userId = user, directing = 9.0, cinematography = 1.0, acting = 1.0, soundtrack = 4.0, screenplay = 5.0),
                    TvRatingFactory.aQuickSeasonRating(tvShowId = TvShow.Id(3), seasonNumber = 2, userId = user, score = 9.0),
                ),
            )
        )
    }

    @Test
    fun `should rank shows by overall score including quick seasons`() {
        givenMixedShows()

        val result = handler.handle(TopTvShow(User.Id(1), null, null, 10, null, Lang.en))

        assertEquals(listOf("All Quick", "All Detailed", "Mixed"), result.map { it.title })
        assertEquals(9.0, result[0].rating.score!!.value)
        assertEquals(6.5, result[2].rating.score!!.value)
    }

    @Test
    fun `should rank by a category averaged over detailed seasons and leave quick-only shows out`() {
        givenMixedShows()

        val result = handler.handle(TopTvShow(User.Id(1), null, "directing", 10, null, Lang.en))

        assertEquals(listOf("Mixed", "All Detailed"), result.map { it.title })
        assertEquals(listOf(1, 2), result.map { it.rank.value })
        assertEquals(9.0, result[0].rating.categoryAverage(RatingCategory.directing)!!.value)
    }
}
