package org.ratelog.tag.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ratelog.tag.Tag
import org.ratelog.tag.TagColor
import org.ratelog.tag.TagName
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.user.User

class UpdateTagHandlerTest {

    private val tags = InMemoryTagRepository()
    private val handler = UpdateTagHandler(tags)
    private val user = User.Id(1)
    private val comfort = tags.save(Tag(null, user, TagName("comfort watch"), TagColor.amber, 0))
    private val mind = tags.save(Tag(null, user, TagName("mind-benders"), null, 0))

    @Test
    fun `renames a tag and keeps its color`() {
        val updated = handler.handle(UpdateTagCommand(user, comfort.id!!, name = "cozy")).getOrNull()!!

        assertEquals("cozy", updated.name.value)
        assertEquals(TagColor.amber, updated.color)
        assertEquals("cozy", tags.findById(comfort.id)!!.name.value)
    }

    @Test
    fun `recolors a tag and keeps its name`() {
        val updated = handler.handle(UpdateTagCommand(user, mind.id!!, color = "violet")).getOrNull()!!

        assertEquals("mind-benders", updated.name.value)
        assertEquals(TagColor.violet, updated.color)
    }

    @Test
    fun `none removes the color`() {
        val updated = handler.handle(UpdateTagCommand(user, comfort.id!!, color = "none")).getOrNull()!!
        assertEquals(null, updated.color)
    }

    @Test
    fun `changing only the case of its own name is allowed`() {
        val updated = handler.handle(UpdateTagCommand(user, comfort.id!!, name = "Comfort Watch")).getOrNull()!!
        assertEquals("Comfort Watch", updated.name.value)
    }

    @Test
    fun `cannot rename onto another tag's name`() {
        val error = handler.handle(UpdateTagCommand(user, comfort.id!!, name = "MIND-BENDERS")).leftOrNull()

        assertEquals(UpdateTagError.NameTaken, error)
        assertEquals("comfort watch", tags.findById(comfort.id)!!.name.value)
    }

    @Test
    fun `invalid name or color is rejected`() {
        assertEquals(UpdateTagError.InvalidName, handler.handle(UpdateTagCommand(user, comfort.id!!, name = " ")).leftOrNull())
        assertEquals(UpdateTagError.InvalidColor, handler.handle(UpdateTagCommand(user, comfort.id, color = "plaid")).leftOrNull())
    }

    @Test
    fun `only the owner can update a tag`() {
        assertEquals(UpdateTagError.NotOwner, handler.handle(UpdateTagCommand(User.Id(2), comfort.id!!, name = "mine")).leftOrNull())
    }

    @Test
    fun `unknown tag is not found`() {
        assertEquals(UpdateTagError.TagNotFound, handler.handle(UpdateTagCommand(user, Tag.Id(999), name = "x")).leftOrNull())
    }
}
