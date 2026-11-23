package org.ratelog.tag.create

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.tag.Tag
import org.ratelog.tag.TagColor
import org.ratelog.tag.TagName
import org.ratelog.tag.TagRepository
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.user.User

class CreateTagHandlerTest {

    private val tags = InMemoryTagRepository()
    private val handler = CreateTagHandler(tags)
    private val user = User.Id(1)

    @Test
    fun `creates a tag with a normalized name and color`() {
        val tag = handler.handle(CreateTagCommand(user, "  comfort   watch ", "amber")).getOrNull()!!

        assertEquals("comfort watch", tag.name.value)
        assertEquals(TagColor.amber, tag.color)
        assertEquals(listOf(tag), tags.findByUserId(user))
    }

    @Test
    fun `color is optional`() {
        val tag = handler.handle(CreateTagCommand(user, "date night")).getOrNull()!!
        assertEquals(null, tag.color)
    }

    @Test
    fun `rejects empty and too long names`() {
        assertEquals(CreateTagError.InvalidName, handler.handle(CreateTagCommand(user, "  ")).leftOrNull())
        assertEquals(CreateTagError.InvalidName, handler.handle(CreateTagCommand(user, "x".repeat(41))).leftOrNull())
        assertTrue(tags.findByUserId(user).isEmpty())
    }

    @Test
    fun `rejects colors outside the palette`() {
        assertEquals(CreateTagError.InvalidColor, handler.handle(CreateTagCommand(user, "noir", "chartreuse")).leftOrNull())
    }

    @Test
    fun `names are unique per user ignoring case`() {
        val first = handler.handle(CreateTagCommand(user, "Mind-benders")).getOrNull()!!

        val error = handler.handle(CreateTagCommand(user, "mind-BENDERS")).leftOrNull()

        assertEquals(CreateTagError.AlreadyExists(first), error)
        assertEquals(1, tags.findByUserId(user).size)
    }

    @Test
    fun `two users can have a tag with the same name`() {
        handler.handle(CreateTagCommand(user, "comfort watch"))
        val other = handler.handle(CreateTagCommand(User.Id(2), "comfort watch"))

        assertTrue(other.isRight())
    }

    @Test
    fun `stops at the per-user tag limit`() {
        repeat(TagRepository.MAX_TAGS_PER_USER) {
            tags.save(Tag(null, user, TagName("tag $it"), null, 0))
        }

        assertEquals(CreateTagError.TagLimitExceeded, handler.handle(CreateTagCommand(user, "one more")).leftOrNull())
    }
}
