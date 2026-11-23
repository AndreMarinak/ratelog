package org.ratelog.tag.list

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.user.User

class ListTagsHandlerTest {

    private val tags = InMemoryTagRepository()
    private val handler = ListTagsHandler(tags)
    private val user = User.Id(1)

    @Test
    fun `lists the user's tags by name with title counts`() {
        val noir = tags.save(Tag(null, user, TagName("noir"), null, 0))
        val comfort = tags.save(Tag(null, user, TagName("Comfort watch"), null, 0))
        tags.save(Tag(null, User.Id(2), TagName("someone else's"), null, 0))
        tags.assign(comfort.id!!, MediaType.movie, 1, 0)
        tags.assign(comfort.id, MediaType.tvshow, 1, 0)
        tags.assign(comfort.id, MediaType.movie, 2, 0)

        val result = handler.handle(ListTags(user))

        assertEquals(listOf("Comfort watch", "noir"), result.map { it.tag.name.value })
        assertEquals(listOf(3, 0), result.map { it.titleCount })
        assertEquals(noir, result[1].tag)
    }
}
