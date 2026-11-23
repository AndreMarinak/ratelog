package org.ratelog.tag.detail

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.Title
import org.ratelog.api.media.MediaImporter
import org.ratelog.movie.MovieDescription
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.RatingFactory
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.user.User

class GetTagDetailHandlerTest {

    private val tags = InMemoryTagRepository()
    private val movies = InMemoryMovieRepository()
    private val movieDescriptions = InMemoryMovieDescriptionRepository()
    private val shows = InMemoryTvShowRepository()
    private val ratings = InMemoryRatingRepository()
    private val tvRatings = InMemoryTvRatingRepository()
    private val handler = GetTagDetailHandler(
        tags,
        MediaImporter(mock(), movies, movieDescriptions, shows, InMemoryTvDescriptionRepository()),
        ratings,
        tvRatings,
    )
    private val user = User.Id(1)
    private val tag = tags.save(Tag(null, user, TagName("comfort watch"), null, 0))

    @Test
    fun `lists tagged titles by title with the user's score when rated`() {
        val paddington = movies.save(MovieFactory.aMovie(tmdbId = 116149, originalTitle = "Paddington", releaseYear = 2014, posterPath = "/p.jpg"))
        val amelie = movies.save(MovieFactory.aMovie(tmdbId = 194, originalTitle = "Le Fabuleux Destin d'Amélie Poulain", releaseYear = 2001))
        movieDescriptions.saveAll(listOf(MovieDescription(null, amelie.tmdbId, Lang.en, Title("Amélie"), null)))
        val bluey = shows.save(TvShowFactory.aTvShow(tmdbId = 82728, originalName = "Bluey", firstAirYear = 2018))
        tags.assign(tag.id!!, MediaType.movie, paddington.id!!.value, 1)
        tags.assign(tag.id, MediaType.movie, amelie.id!!.value, 2)
        tags.assign(tag.id, MediaType.tvshow, bluey.id!!.value, 3)
        ratings.save(RatingFactory.aRating(movieId = paddington.id, userId = user, directing = 8.0, cinematography = 8.0, acting = 8.0, soundtrack = 8.0, screenplay = 8.0))
        ratings.save(RatingFactory.aRating(movieId = amelie.id, userId = User.Id(2)))
        tvRatings.save(
            TvRatingFactory.aTvRating(
                tvShowId = bluey.id, userId = user,
                seasonRatings = listOf(TvRatingFactory.aSeasonRating(tvShowId = bluey.id, seasonNumber = 1, userId = user, directing = 9.0, cinematography = 9.0, acting = 9.0, soundtrack = 9.0, screenplay = 9.0)),
            )
        )

        val detail = handler.handle(GetTagDetail(user, tag.id, Lang.en)).getOrNull()!!

        assertEquals(listOf("Amélie", "Bluey", "Paddington"), detail.titles.map { it.title })
        assertEquals(listOf(null, 9.0, 8.0), detail.titles.map { it.score })
        assertEquals(listOf(MediaType.movie, MediaType.tvshow, MediaType.movie), detail.titles.map { it.mediaType })
        assertEquals(116149, detail.titles[2].tmdbId)
        assertEquals(2014, detail.titles[2].year)
        assertEquals("/p.jpg", detail.titles[2].posterPath)
    }

    @Test
    fun `a tag without titles is empty`() {
        assertTrue(handler.handle(GetTagDetail(user, tag.id!!, Lang.en)).getOrNull()!!.titles.isEmpty())
    }

    @Test
    fun `other users' tags are private`() {
        assertEquals(GetTagDetailError.NotOwner, handler.handle(GetTagDetail(User.Id(2), tag.id!!, Lang.en)).leftOrNull())
    }

    @Test
    fun `unknown tag is not found`() {
        assertEquals(GetTagDetailError.TagNotFound, handler.handle(GetTagDetail(user, Tag.Id(999), Lang.en)).leftOrNull())
    }
}
