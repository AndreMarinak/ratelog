package org.ratelog.tag.unassign

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.user.User

class UnassignTagHandlerTest {

    private val tags = InMemoryTagRepository()
    private val handler = UnassignTagHandler(tags)
    private val user = User.Id(1)
    private val tag = tags.save(Tag(null, user, TagName("comfort watch"), null, 0))

    @Test
    fun `detaches the tag from that title only`() {
        tags.assign(tag.id!!, MediaType.movie, 10, 0)
        tags.assign(tag.id, MediaType.movie, 11, 0)

        assertTrue(handler.handle(UnassignTagCommand(user, tag.id, MediaType.movie, 10)).isRight())

        assertTrue(tags.findForTitle(user, MediaType.movie, 10).isEmpty())
        assertEquals(listOf(tag), tags.findForTitle(user, MediaType.movie, 11))
        assertEquals(tag, tags.findById(tag.id))
    }

    @Test
    fun `detaching a tag the title doesn't have is a no-op`() {
        assertTrue(handler.handle(UnassignTagCommand(user, tag.id!!, MediaType.tvshow, 10)).isRight())
    }

    @Test
    fun `only the owner can detach`() {
        tags.assign(tag.id!!, MediaType.movie, 10, 0)

        assertEquals(UnassignTagError.NotOwner, handler.handle(UnassignTagCommand(User.Id(2), tag.id, MediaType.movie, 10)).leftOrNull())
        assertEquals(listOf(tag), tags.findForTitle(user, MediaType.movie, 10))
    }

    @Test
    fun `unknown tag is not found`() {
        assertEquals(UnassignTagError.TagNotFound, handler.handle(UnassignTagCommand(user, Tag.Id(999), MediaType.movie, 10)).leftOrNull())
    }
}
