package org.ratelog.customlist.suggest.web

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.suggest.GetListSuggestionsHandler
import org.ratelog.recommendations.RecommendationsPage
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TmdbRecommendationsClient
import org.ratelog.recommendations.TmdbRecommendationsError
import org.ratelog.test.InMemoryBrowseLibraryRepository
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryListTitlesRepository
import org.ratelog.test.UserFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.ui.ConcurrentModel
import java.time.LocalDate
import java.util.Locale

class ListSuggestionsControllerTest {

    private val lists = InMemoryCustomListRepository()
    private val client: TmdbRecommendationsClient = mock()
    private val handler = GetListSuggestionsHandler(lists, InMemoryListTitlesRepository(lists), client, InMemoryBrowseLibraryRepository(), parallel = 1)
    private val controller = ListSuggestionsController(handler, ListSuggestViews(BrowseBarViewsTest.testMessageSource()))
    private val user = UserFactory.aUser(id = 7, metadataLang = Lang.de)
    private val other = UserFactory.aUser(id = 8)

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun list(owner: org.ratelog.user.User = user, vararg tmdbIds: Int): CustomList {
        val list = lists.save(CustomList(null, owner.id!!, ListName("Pixar"), isPublic = true, createdAtEpochMs = 1))
        tmdbIds.forEachIndexed { i, id -> lists.addItem(CustomListItem(null, list.id!!, id.toLong(), MediaType.movie, i + 1, 1)) }
        return list
    }

    private fun recs(vararg ids: Int) =
        RecommendationsPage(null, ids.map { RelatedTitle(it, MediaType.movie, "Title $it", LocalDate.parse("2015-01-01"), "/p$it.jpg", 7.0, 500) }, 1, 1)

    private fun row(list: CustomList, skip: String? = null, viewer: org.ratelog.user.User = user): ListSuggestView? {
        val model = ConcurrentModel()
        assertEquals("fragments/list-suggest :: row", controller.row(viewer, list.id!!.value, skip, model))
        return model.getAttribute("listSuggest") as ListSuggestView?
    }

    @Test
    fun `the owner's row renders as the fragment, in the viewer's metadata language`() {
        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(recs(1, 2, 3).right())
        val pixar = list(user, 862)

        val view = row(pixar)!!

        assertEquals(listOf("movie:1", "movie:2", "movie:3"), view.tiles.map { it.key })
        assertEquals(listOf("m1", "m2", "m3"), view.tiles.map { it.skipKey })
        assertEquals("Based on the title in this list", view.basis)
        assertEquals("/list/${pixar.id!!.value}/suggestions/add", view.addAction)
        assertEquals("/list/${pixar.id!!.value}", view.back)
        assertFalse(view.hasMore)
        assertFalse(view.canRestart)
        val lang = argumentCaptor<Lang>()
        verify(client).recommendations(eq(MediaType.movie), eq(862), lang.capture(), eq(1))
        assertEquals(Lang.de, lang.firstValue)
    }

    @Test
    fun `skip leaves the shown titles out and offers to start over once nothing is left`() {
        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(recs(1, 2, 3).right())
        val pixar = list(user, 862)

        val next = row(pixar, skip = "m1.m2")!!
        assertEquals(listOf("movie:3"), next.tiles.map { it.key })
        assertTrue(next.canRestart)

        val end = row(pixar, skip = "m1.m2.m3")!!
        assertTrue(end.exhausted)
        assertTrue(end.tiles.isEmpty())
    }

    @Test
    fun `nothing for someone else's list, an empty list, unknown ids or TMDB errors`() {
        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(recs(1).right())
        assertNull(row(list(other, 862)), "not the viewer's list")
        assertNull(row(list(user)), "empty list")
        assertNull(row(list(user, 862), viewer = other))

        val model = ConcurrentModel()
        controller.row(user, 0, null, model)
        assertNull(model.getAttribute("listSuggest"))

        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(TmdbRecommendationsError.Unavailable.left())
        assertNull(row(list(user, 585)))
    }

    @Test
    fun `a bug answers an empty fragment instead of an error page`() {
        whenever(client.recommendations(any(), any(), any(), any())).thenThrow(IllegalStateException("boom https://api.themoviedb.org/3?api_key=secret"))
        assertNull(row(list(user, 862)))
    }

    @Test
    fun `no recommendations at all means no row`() {
        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(recs().right())
        assertNull(row(list(user, 862)))
        verify(client, never()).collection(any(), any())
    }
}
