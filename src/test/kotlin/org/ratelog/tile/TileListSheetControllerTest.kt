package org.ratelog.tile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.media.MediaImporter
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.membership.web.ListMembershipView
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryListMembershipRepository
import org.ratelog.test.UserFactory
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.ui.ExtendedModelMap

class TileListSheetControllerTest {

    private val lists = InMemoryCustomListRepository()
    private val membership = InMemoryListMembershipRepository(lists)
    private val importer: MediaImporter = mock()
    private val controller = TileListSheetController(importer, lists, membership)
    private val user = UserFactory.aUser(id = 1)

    init {
        whenever(importer.ensure(eq(603), eq(MediaType.movie), any()))
            .thenReturn(Result.success(LocalMedia(42, 603, MediaType.movie, "The Matrix", 1999, null)))
        whenever(importer.ensure(eq(1396), eq(MediaType.tvshow), any()))
            .thenReturn(Result.success(LocalMedia(9, 1396, MediaType.tvshow, "Breaking Bad", 2008, null)))
        whenever(importer.ensure(eq(404), any(), any())).thenReturn(Result.failure(IllegalStateException("not on TMDB")))
    }

    private var clock = 1000L

    private fun list(name: String, vararg mediaIds: Long, type: MediaType = MediaType.movie): CustomList {
        clock += 1000
        val created = lists.save(CustomList(null, user.id!!, ListName(name), isPublic = false, createdAtEpochMs = clock))
        mediaIds.forEachIndexed { index, mediaId -> lists.addItem(CustomListItem(null, created.id!!, mediaId, type, index + 1, clock)) }
        return created
    }

    @Test
    fun `renders the title page's sheet for the tile's title, ticked where it already is`() {
        list("Heist films")
        list("Sci-fi", 42)
        val model = ExtendedModelMap()
        val response = MockHttpServletResponse()

        val view = controller.sheet(user, 603, "movie", "/discover?type=movie", model, response)

        assertEquals("fragments/tile-rate :: listSheet", view)
        assertEquals(200, response.status)
        val m = model["listMembership"] as ListMembershipView
        assertEquals("movie", m.mediaType)
        assertEquals(42L, m.mediaId)
        assertEquals("/discover?type=movie", m.back, "plain form posts come back to the tile's page")
        assertEquals(1, m.count)
        assertEquals(listOf("Sci-fi", "Heist films"), m.rows.map { it.name }, "lists with the title first")
        assertEquals(42L, model["sheetMediaId"])
        assertEquals("movie", model["sheetMediaType"])
        assertEquals("movie:603", model["sheetKey"])
        assertEquals("The Matrix", model["sheetTitle"])
    }

    @Test
    fun `shows use the tv key, and an unsafe back falls back to the title page`() {
        val model = ExtendedModelMap()
        controller.sheet(user, 1396, "tv", "javascript:alert(1)", model, MockHttpServletResponse())

        assertEquals("tv:1396", model["sheetKey"])
        assertEquals("tvshow", model["sheetMediaType"])
        assertEquals("/tv/1396", (model["listMembership"] as ListMembershipView).back)
        assertTrue((model["listMembership"] as ListMembershipView).rows.isEmpty())
    }

    @Test
    fun `an unknown title or type answers 404 with an empty fragment`() {
        val response = MockHttpServletResponse()
        assertEquals("fragments/tile-rate :: listSheetMissing", controller.sheet(user, 404, "movie", null, ExtendedModelMap(), response))
        assertEquals(404, response.status)

        val badType = MockHttpServletResponse()
        val model = ExtendedModelMap()
        assertEquals("fragments/tile-rate :: listSheetMissing", controller.sheet(user, 603, "book", null, model, badType))
        assertEquals(404, badType.status)
        assertNull(model["listMembership"])
    }
}
