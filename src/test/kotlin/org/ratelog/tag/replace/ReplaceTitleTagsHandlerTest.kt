package org.ratelog.tag.replace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.tag.assign.AssignTagsHandler
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.user.User

class ReplaceTitleTagsHandlerTest {

    private val tags = InMemoryTagRepository()
    private val movies = InMemoryMovieRepository()
    private val handler = ReplaceTitleTagsHandler(tags, AssignTagsHandler(tags, movies, InMemoryTvShowRepository()))
    private val user = User.Id(1)
    private val heat = movies.save(MovieFactory.aMovie(originalTitle = "Heat")).id!!.value
    private val heist = tags.save(Tag(null, user, TagName("heist"), null, 0))
    private val noir = tags.save(Tag(null, user, TagName("noir"), null, 0))

    private fun titleTags() = tags.findForTitle(user, MediaType.movie, heat).map { it.name.value }

    @Test
    fun `replaces the title's tags, creating missing ones`() {
        tags.assign(heist.id!!, MediaType.movie, heat, 0)
        tags.assign(noir.id!!, MediaType.movie, heat, 0)

        val result = handler.handle(ReplaceTitleTagsCommand(user, MediaType.movie, heat, listOf("Heist", "comfort watch"))).getOrNull()!!

        assertEquals(listOf("comfort watch", "heist"), titleTags())
        assertEquals(listOf("comfort watch"), result.created.map { it.name.value })
        // A removed tag still exists, it just isn't on this title any more.
        assertEquals(noir, tags.findById(noir.id))
    }

    @Test
    fun `an empty list clears the title's tags`() {
        tags.assign(heist.id!!, MediaType.movie, heat, 0)

        assertTrue(handler.handle(ReplaceTitleTagsCommand(user, MediaType.movie, heat, emptyList())).isRight())

        assertTrue(titleTags().isEmpty())
    }

    @Test
    fun `other users' tags on the same title are left alone`() {
        val theirs = tags.save(Tag(null, User.Id(2), TagName("theirs"), null, 0))
        tags.assign(theirs.id!!, MediaType.movie, heat, 0)

        handler.handle(ReplaceTitleTagsCommand(user, MediaType.movie, heat, listOf("heist")))

        assertEquals(listOf(theirs), tags.findForTitle(User.Id(2), MediaType.movie, heat))
    }

    @Test
    fun `an invalid name changes nothing`() {
        tags.assign(heist.id!!, MediaType.movie, heat, 0)

        val error = handler.handle(ReplaceTitleTagsCommand(user, MediaType.movie, heat, listOf("x".repeat(41)))).leftOrNull()

        assertEquals(ReplaceTitleTagsError.InvalidName, error)
        assertEquals(listOf("heist"), titleTags())
    }

    @Test
    fun `unknown title is not found`() {
        assertEquals(
            ReplaceTitleTagsError.TitleNotFound,
            handler.handle(ReplaceTitleTagsCommand(user, MediaType.movie, 999, listOf("heist"))).leftOrNull(),
        )
    }
}
