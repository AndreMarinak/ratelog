package org.ratelog.tvshow.rating.addseason

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ratelog.SeasonNumber
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User

class AddSeasonRatingHandlerTest {

    private lateinit var tvRatingRepository: InMemoryTvRatingRepository
    private lateinit var handler: AddSeasonRatingHandler

    @BeforeEach
    fun setUp() {
        tvRatingRepository = InMemoryTvRatingRepository()
        handler = AddSeasonRatingHandler(tvRatingRepository)
    }

    @Test
    fun `should add season rating successfully when all values are valid`() {
        val command = AddSeasonRating(
            tvShowId = TvShow.Id(1),
            seasonNumber = SeasonNumber(1),
            userId = User.Id(1),
            directing = org.ratelog.Score(5.0),
            cinematography = org.ratelog.Score(6.0),
            acting = org.ratelog.Score(7.0),
            soundtrack = org.ratelog.Score(8.0),
            screenplay = org.ratelog.Score(9.0),
            reviewTitle = null, reviewText = null
        )

        val result = handler.handle(command)

        assertTrue(result.isRight())
        val savedRating = tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(1), User.Id(1))
        assertNotNull(savedRating)
        assertEquals(1, savedRating!!.seasonRatings.size)
        assertEquals(7.0, savedRating.score!!.value)
    }

    @Test
    fun `should add multiple season ratings to same show`() {
        val command1 = AddSeasonRating(
            tvShowId = TvShow.Id(1),
            seasonNumber = SeasonNumber(1),
            userId = User.Id(1),
            directing = org.ratelog.Score(5.0),
            cinematography = org.ratelog.Score(5.0),
            acting = org.ratelog.Score(5.0),
            soundtrack = org.ratelog.Score(5.0),
            screenplay = org.ratelog.Score(5.0),
            reviewTitle = null, reviewText = null
        )
        handler.handle(command1)

        val command2 = AddSeasonRating(
            tvShowId = TvShow.Id(1),
            seasonNumber = SeasonNumber(2),
            userId = User.Id(1),
            directing = org.ratelog.Score(8.0),
            cinematography = org.ratelog.Score(8.0),
            acting = org.ratelog.Score(8.0),
            soundtrack = org.ratelog.Score(8.0),
            screenplay = org.ratelog.Score(8.0),
            reviewTitle = null, reviewText = null
        )

        val result = handler.handle(command2)

        assertTrue(result.isRight())
        val savedRating = tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(1), User.Id(1))
        assertEquals(2, savedRating!!.seasonRatings.size)
        assertEquals(6.5, savedRating.score!!.value)
    }

    @Test
    fun `should add season rating with review when review is provided`() {
        val command = AddSeasonRating(
            tvShowId = TvShow.Id(1),
            seasonNumber = SeasonNumber(1),
            userId = User.Id(1),
            directing = org.ratelog.Score(5.0),
            cinematography = org.ratelog.Score(6.0),
            acting = org.ratelog.Score(7.0),
            soundtrack = org.ratelog.Score(8.0),
            screenplay = org.ratelog.Score(9.0),
            reviewTitle = "Great season",
            reviewText = "Great season!"
        )

        val result = handler.handle(command)

        assertTrue(result.isRight())
        val savedRating = tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(1), User.Id(1))
        assertNotNull(savedRating)
        assertEquals(1, savedRating!!.seasonRatings.size)
        assertEquals("Great season", savedRating.seasonRatings[0].review!!.title!!.value)
        assertEquals("Great season!", savedRating.seasonRatings[0].review!!.text!!.value)
    }

    @Test
    fun `should sanitize season review by removing HTML tags`() {
        val command = AddSeasonRating(
            tvShowId = TvShow.Id(1),
            seasonNumber = SeasonNumber(1),
            userId = User.Id(1),
            directing = org.ratelog.Score(5.0),
            cinematography = org.ratelog.Score(5.0),
            acting = org.ratelog.Score(5.0),
            soundtrack = org.ratelog.Score(5.0),
            screenplay = org.ratelog.Score(5.0),
            reviewTitle = null,
            reviewText = "<script>alert('xss')</script>Good season"
        )

        val result = handler.handle(command)

        assertTrue(result.isRight())
        val savedRating = tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(1), User.Id(1))
        assertNotNull(savedRating)
        assertEquals("alert('xss')Good season", savedRating!!.seasonRatings[0].review!!.text!!.value)
    }

    @Test
    fun `should allow line breaks in season review text and collapse more than two consecutive`() {
        val command = AddSeasonRating(
            tvShowId = TvShow.Id(1),
            seasonNumber = SeasonNumber(1),
            userId = User.Id(1),
            directing = org.ratelog.Score(5.0),
            cinematography = org.ratelog.Score(5.0),
            acting = org.ratelog.Score(5.0),
            soundtrack = org.ratelog.Score(5.0),
            screenplay = org.ratelog.Score(5.0),
            reviewTitle = null,
            reviewText = "Line1\n\nLine2\n\n\nLine3"
        )

        val result = handler.handle(command)

        assertTrue(result.isRight())
        val savedRating = tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(1), User.Id(1))
        assertNotNull(savedRating)
        assertEquals("Line1\n\nLine2\n\nLine3", savedRating!!.seasonRatings[0].review!!.text!!.value)
    }

    @Test
    fun `should not save season review when review is blank`() {
        val command = AddSeasonRating(
            tvShowId = TvShow.Id(1),
            seasonNumber = SeasonNumber(1),
            userId = User.Id(1),
            directing = org.ratelog.Score(5.0),
            cinematography = org.ratelog.Score(5.0),
            acting = org.ratelog.Score(5.0),
            soundtrack = org.ratelog.Score(5.0),
            screenplay = org.ratelog.Score(5.0),
            reviewTitle = "   ",
            reviewText = "   "
        )

        val result = handler.handle(command)

        assertTrue(result.isRight())
        val savedRating = tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(1), User.Id(1))
        assertNotNull(savedRating)
        assertNull(savedRating!!.seasonRatings[0].review)
    }

    private fun quickSeason(seasonNumber: Int, score: Double?, directing: Double? = null) = AddSeasonRating(
        tvShowId = TvShow.Id(1),
        seasonNumber = SeasonNumber(seasonNumber),
        userId = User.Id(1),
        directing = directing?.let { org.ratelog.Score(it) },
        cinematography = null,
        acting = null,
        soundtrack = null,
        screenplay = null,
        reviewTitle = null, reviewText = null,
        score = score?.let { org.ratelog.Score(it) },
    )

    @Test
    fun `should add a quick season rating with only the overall score`() {
        val result = handler.handle(quickSeason(1, 9.0))

        assertTrue(result.isRight())
        val saved = tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(1), User.Id(1))!!
        val season = saved.seasonRatings.single()
        assertTrue(season.isQuick)
        assertNull(season.directing)
        assertEquals(9.0, season.score.value)
        assertEquals(9.0, saved.score!!.value)
    }

    @Test
    fun `should average quick and detailed seasons into the show score`() {
        handler.handle(
            AddSeasonRating(
                tvShowId = TvShow.Id(1), seasonNumber = SeasonNumber(1), userId = User.Id(1),
                directing = org.ratelog.Score(6.0), cinematography = org.ratelog.Score(6.0), acting = org.ratelog.Score(6.0),
                soundtrack = org.ratelog.Score(6.0), screenplay = org.ratelog.Score(6.0),
                reviewTitle = null, reviewText = null,
            )
        )

        handler.handle(quickSeason(2, 9.0))

        val saved = tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(1), User.Id(1))!!
        assertEquals(2, saved.seasonRatings.size)
        assertFalse(saved.seasonRatings.first { it.seasonNumber == SeasonNumber(1) }.isQuick)
        assertTrue(saved.seasonRatings.first { it.seasonNumber == SeasonNumber(2) }.isQuick)
        assertEquals(7.5, saved.score!!.value)
    }

    @Test
    fun `should return InvalidRatingCombination when a score is sent with some categories`() {
        val result = handler.handle(quickSeason(1, 9.0, directing = 7.0))

        assertEquals(AddSeasonRatingHandlerError.InvalidRatingCombination, result.leftOrNull())
        assertNull(tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(1), User.Id(1)))
    }

    @Test
    fun `should return InvalidRatingCombination when neither score nor categories are sent`() {
        val result = handler.handle(quickSeason(1, null))

        assertEquals(AddSeasonRatingHandlerError.InvalidRatingCombination, result.leftOrNull())
    }
}
