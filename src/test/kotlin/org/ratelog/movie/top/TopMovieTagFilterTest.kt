package org.ratelog.movie.top

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.RatingFactory
import org.ratelog.user.User

class TopMovieTagFilterTest {

    private val tags = InMemoryTagRepository()
    private val ratings = InMemoryRatingRepository(tags)
    private val movies = InMemoryMovieRepository()
    private val handler = TopMovieHandler(ratings, movies, InMemoryMovieDescriptionRepository())
    private val user = User.Id(1)
    private val comfort = tags.save(Tag(null, user, TagName("comfort watch"), null, 0))

    private fun rated(title: String, score: Double, rater: User.Id = user): Long {
        val movie = movies.save(MovieFactory.aMovie(originalTitle = title))
        ratings.save(RatingFactory.aRating(movieId = movie.id!!, userId = rater, directing = score, cinematography = score, acting = score, soundtrack = score, screenplay = score))
        return movie.id.value
    }

    @Test
    fun `tag filter narrows the ranking to tagged titles and keeps overall ranks`() {
        val paddington = rated("Paddington 2", 9.0)
        rated("Heat", 8.0)
        val amelie = rated("Amelie", 7.0)
        tags.assign(comfort.id!!, MediaType.movie, paddington, 0)
        tags.assign(comfort.id, MediaType.movie, amelie, 0)

        val result = handler.handle(TopMovie(user, null, null, 10, null, Lang.en, tagId = comfort.id))

        assertEquals(listOf("Paddington 2", "Amelie"), result.map { it.title })
        assertEquals(listOf(1, 3), result.map { it.rank.value })
    }

    @Test
    fun `limit applies after the tag filter`() {
        val a = rated("A", 9.0)
        rated("B", 8.0)
        val c = rated("C", 7.0)
        val d = rated("D", 6.0)
        listOf(a, c, d).forEach { tags.assign(comfort.id!!, MediaType.movie, it, 0) }

        val result = handler.handle(TopMovie(user, null, null, 2, null, Lang.en, tagId = comfort.id))

        assertEquals(listOf("A", "C"), result.map { it.title })
    }

    @Test
    fun `no tag means the full ranking`() {
        val a = rated("A", 9.0)
        rated("B", 8.0)
        tags.assign(comfort.id!!, MediaType.movie, a, 0)

        assertEquals(2, handler.handle(TopMovie(user, null, null, 10, null, Lang.en)).size)
    }

    @Test
    fun `another user's tag matches nothing`() {
        val a = rated("A", 9.0)
        val theirs = tags.save(Tag(null, User.Id(2), TagName("theirs"), null, 0))
        tags.assign(theirs.id!!, MediaType.movie, a, 0)

        assertTrue(handler.handle(TopMovie(user, null, null, 10, null, Lang.en, tagId = theirs.id)).isEmpty())
    }

    @Test
    fun `a tv show with the same local id is not a match`() {
        val a = rated("A", 9.0)
        tags.assign(comfort.id!!, MediaType.tvshow, a, 0)

        assertTrue(handler.handle(TopMovie(user, null, null, 10, null, Lang.en, tagId = comfort.id)).isEmpty())
    }
}
