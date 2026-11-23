package org.ratelog.tag.assign

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagColor
import org.ratelog.tag.TagName
import org.ratelog.tag.TagRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.user.User

class AssignTagsHandlerTest {

    private val tags = InMemoryTagRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val handler = AssignTagsHandler(tags, movies, shows)
    private val user = User.Id(1)
    private val heat = movies.save(MovieFactory.aMovie(originalTitle = "Heat")).id!!.value
    private val dark = shows.save(TvShowFactory.aTvShow(originalName = "Dark")).id!!.value

    @Test
    fun `attaches an existing tag, matching the name ignoring case`() {
        val existing = tags.save(Tag(null, user, TagName("Comfort Watch"), TagColor.amber, 0))

        val result = handler.handle(AssignTagsCommand(user, MediaType.movie, heat, listOf("comfort watch"))).getOrNull()!!

        assertEquals(listOf(existing), result.tags)
        assertTrue(result.created.isEmpty())
        assertEquals(listOf(existing), tags.findForTitle(user, MediaType.movie, heat))
    }

    @Test
    fun `typing a new name creates the tag`() {
        val result = handler.handle(AssignTagsCommand(user, MediaType.tvshow, dark, listOf("mind-benders"))).getOrNull()!!

        assertEquals(listOf("mind-benders"), result.created.map { it.name.value })
        assertEquals(listOf("mind-benders"), tags.findByUserId(user).map { it.name.value })
        assertEquals(listOf("mind-benders"), tags.findForTitle(user, MediaType.tvshow, dark).map { it.name.value })
    }

    @Test
    fun `duplicate names in one request create a single tag`() {
        val result = handler.handle(AssignTagsCommand(user, MediaType.movie, heat, listOf("Heist", "heist ", "HEIST"))).getOrNull()!!

        assertEquals(1, result.tags.size)
        assertEquals(1, tags.findByUserId(user).size)
    }

    @Test
    fun `assigning the same tag twice is a no-op`() {
        handler.handle(AssignTagsCommand(user, MediaType.movie, heat, listOf("heist")))
        handler.handle(AssignTagsCommand(user, MediaType.movie, heat, listOf("heist")))

        val tag = tags.findByUserId(user).single()
        assertEquals(1, tags.findAssignments(tag.id!!).size)
    }

    @Test
    fun `adding a tag keeps the title's other tags`() {
        handler.handle(AssignTagsCommand(user, MediaType.movie, heat, listOf("heist")))
        handler.handle(AssignTagsCommand(user, MediaType.movie, heat, listOf("date night")))

        assertEquals(listOf("date night", "heist"), tags.findForTitle(user, MediaType.movie, heat).map { it.name.value })
    }

    @Test
    fun `an invalid name fails the whole request`() {
        val error = handler.handle(AssignTagsCommand(user, MediaType.movie, heat, listOf("ok", " "))).leftOrNull()

        assertEquals(AssignTagsError.InvalidName, error)
        assertTrue(tags.findByUserId(user).isEmpty())
    }

    @Test
    fun `unknown title is not found`() {
        val error = handler.handle(AssignTagsCommand(user, MediaType.movie, 999, listOf("heist"))).leftOrNull()

        assertEquals(AssignTagsError.TitleNotFound, error)
        assertTrue(tags.findByUserId(user).isEmpty())
    }

    @Test
    fun `new tags respect the per-user limit, existing ones still work`() {
        repeat(TagRepository.MAX_TAGS_PER_USER) { tags.save(Tag(null, user, TagName("tag $it"), null, 0)) }

        assertEquals(
            AssignTagsError.TagLimitExceeded,
            handler.handle(AssignTagsCommand(user, MediaType.movie, heat, listOf("brand new"))).leftOrNull(),
        )
        assertTrue(handler.handle(AssignTagsCommand(user, MediaType.movie, heat, listOf("tag 1"))).isRight())
    }

    @Test
    fun `another user's tag with the same name is not reused`() {
        val theirs = tags.save(Tag(null, User.Id(2), TagName("heist"), null, 0))

        val result = handler.handle(AssignTagsCommand(user, MediaType.movie, heat, listOf("heist"))).getOrNull()!!

        assertTrue(result.tags.single().id != theirs.id)
        assertEquals(user, result.tags.single().userId)
    }
}
