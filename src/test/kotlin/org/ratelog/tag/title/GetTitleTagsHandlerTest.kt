package org.ratelog.tag.title

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.user.User

class GetTitleTagsHandlerTest {

    private val tags = InMemoryTagRepository()
    private val handler = GetTitleTagsHandler(tags)
    private val user = User.Id(1)

    @Test
    fun `returns the title's tags and the user's other tags as suggestions`() {
        val heist = tags.save(Tag(null, user, TagName("heist"), null, 0))
        val noir = tags.save(Tag(null, user, TagName("noir"), null, 0))
        val cozy = tags.save(Tag(null, user, TagName("cozy"), null, 0))
        val theirs = tags.save(Tag(null, User.Id(2), TagName("theirs"), null, 0))
        tags.assign(heist.id!!, MediaType.movie, 7, 0)
        tags.assign(theirs.id!!, MediaType.movie, 7, 0)
        tags.assign(noir.id!!, MediaType.tvshow, 7, 0)

        val result = handler.handle(GetTitleTags(user, MediaType.movie, 7))

        assertEquals(listOf(heist), result.assigned)
        assertEquals(listOf(cozy, noir), result.suggestions)
    }
}
