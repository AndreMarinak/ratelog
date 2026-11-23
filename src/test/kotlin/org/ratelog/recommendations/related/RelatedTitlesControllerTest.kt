package org.ratelog.recommendations.related

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.recommendations.RecommendationsPage
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TmdbRecommendationsClient
import org.ratelog.recommendations.TmdbRecommendationsError
import org.ratelog.recommendations.web.RelatedRowViews
import org.ratelog.recommendations.web.RelatedSectionView
import org.ratelog.test.InMemoryBrowseLibraryRepository
import org.ratelog.test.UserFactory
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.ui.ConcurrentModel
import java.time.LocalDate
import java.util.Locale

class RelatedTitlesControllerTest {

    private val client: TmdbRecommendationsClient = mock()
    private val handler = GetRelatedTitlesHandler(client, InMemoryBrowseLibraryRepository())
    private val controller = RelatedTitlesController(handler, RelatedRowViews(BrowseBarViewsTest.testMessageSource()))
    private val user = UserFactory.aUser(id = 7, metadataLang = Lang.de)

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun recs(vararg ids: Int, type: MediaType = MediaType.movie) =
        RecommendationsPage(null, ids.map { RelatedTitle(it, type, "Title $it", LocalDate.parse("2015-01-01"), "/p$it.jpg", 7.0, 500) }, 1, 1)

    @Test
    fun `a movie's rows render as the fragment, in the viewer's metadata language`() {
        whenever(client.recommendations(eq(MediaType.movie), eq(106646), any(), any())).thenReturn(recs(1, 2, 3).right())
        val model = ConcurrentModel()

        val view = controller.movie(user, 106646, model)

        assertEquals("fragments/title-related :: rows", view)
        val section = model.getAttribute("related") as RelatedSectionView
        assertEquals(listOf("related-more"), section.rows.map { it.id })
        assertEquals(3, section.rows.single().tiles.size)
        val lang = argumentCaptor<Lang>()
        verify(client).recommendations(eq(MediaType.movie), eq(106646), lang.capture(), eq(1))
        assertEquals(Lang.de, lang.firstValue)
    }

    @Test
    fun `shows ask for TV recommendations`() {
        whenever(client.recommendations(eq(MediaType.tvshow), eq(1396), any(), any())).thenReturn(recs(60059, type = MediaType.tvshow).right())
        val model = ConcurrentModel()

        controller.tv(user, 1396, model)

        val tile = (model.getAttribute("related") as RelatedSectionView).rows.single().tiles.single()
        assertEquals("/tv/60059", tile.href)
    }

    @Test
    fun `TMDB errors, empty answers, bugs and bad ids all render nothing`() {
        val model = ConcurrentModel()

        whenever(client.recommendations(any(), eq(1), any(), any())).thenReturn(TmdbRecommendationsError.Unavailable.left())
        controller.movie(user, 1, model)
        assertNull(model.getAttribute("related"))

        whenever(client.recommendations(any(), eq(2), any(), any())).thenReturn(recs().right())
        controller.movie(user, 2, model)
        assertNull(model.getAttribute("related"))

        whenever(client.recommendations(any(), eq(3), any(), any())).thenThrow(IllegalStateException("boom"))
        assertEquals("fragments/title-related :: rows", controller.tv(user, 3, model))
        assertNull(model.getAttribute("related"))

        controller.movie(user, 0, model)
        assertNull(model.getAttribute("related"))
        verify(client, never()).recommendations(any(), eq(0), any(), any())
    }
}
