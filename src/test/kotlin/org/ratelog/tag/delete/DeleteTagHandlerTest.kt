package org.ratelog.tag.delete

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.user.User

class DeleteTagHandlerTest {

    private val tags = InMemoryTagRepository()
    private val handler = DeleteTagHandler(tags)
    private val user = User.Id(1)
    private val tag = tags.save(Tag(null, user, TagName("comfort watch"), null, 0))

    @Test
    fun `deletes the tag and removes it from every title`() {
        tags.assign(tag.id!!, MediaType.movie, 10, 0)
        tags.assign(tag.id, MediaType.tvshow, 20, 0)

        val result = handler.handle(DeleteTagCommand(user, tag.id))

        assertTrue(result.isRight())
        assertNull(tags.findById(tag.id))
        assertTrue(tags.findForTitle(user, MediaType.movie, 10).isEmpty())
        assertTrue(tags.findAssignments(tag.id).isEmpty())
    }

    @Test
    fun `only the owner can delete a tag`() {
        assertEquals(DeleteTagError.NotOwner, handler.handle(DeleteTagCommand(User.Id(2), tag.id!!)).leftOrNull())
        assertNotNull(tags.findById(tag.id))
    }

    @Test
    fun `unknown tag is not found`() {
        assertEquals(DeleteTagError.TagNotFound, handler.handle(DeleteTagCommand(user, Tag.Id(999))).leftOrNull())
    }
}
