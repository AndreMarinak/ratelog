package org.ratelog.tvshow.top

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.user.User

class TopTvShowTagFilterTest {

    private val tags = InMemoryTagRepository()
    private val ratings = InMemoryTvRatingRepository(tags)
    private val shows = InMemoryTvShowRepository()
    private val handler = TopTvShowHandler(ratings, shows, InMemoryTvDescriptionRepository())
    private val user = User.Id(1)
    private val mind = tags.save(Tag(null, user, TagName("mind-benders"), null, 0))

    private fun rated(name: String, score: Double): Long {
        val show = shows.save(TvShowFactory.aTvShow(originalName = name))
        val season = TvRatingFactory.aSeasonRating(
            tvShowId = show.id!!, seasonNumber = 1, userId = user,
            directing = score, cinematography = score, acting = score, soundtrack = score, screenplay = score,
        )
        ratings.save(TvRatingFactory.aTvRating(tvShowId = show.id, userId = user, seasonRatings = listOf(season)))
        return show.id.value
    }

    @Test
    fun `tag filter narrows the tv ranking to tagged shows`() {
        val dark = rated("Dark", 9.5)
        rated("Bluey", 9.0)
        val severance = rated("Severance", 8.5)
        tags.assign(mind.id!!, MediaType.tvshow, dark, 0)
        tags.assign(mind.id, MediaType.tvshow, severance, 0)

        val result = handler.handle(TopTvShow(user, null, null, 10, null, Lang.en, tagId = mind.id))

        assertEquals(listOf("Dark", "Severance"), result.map { it.title })
        assertEquals(listOf(1, 3), result.map { it.rank.value })
    }

    @Test
    fun `a movie with the same local id is not a match`() {
        val dark = rated("Dark", 9.5)
        tags.assign(mind.id!!, MediaType.movie, dark, 0)

        assertEquals(0, handler.handle(TopTvShow(user, null, null, 10, null, Lang.en, tagId = mind.id)).size)
    }
}
