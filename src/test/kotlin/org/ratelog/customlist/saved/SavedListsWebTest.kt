package org.ratelog.customlist.saved

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.api.ApiException
import org.ratelog.api.lists.ApiSavedListController
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.browse.ListCardView
import org.ratelog.customlist.official.OfficialListsFixture
import org.ratelog.customlist.saved.browse.GetSavedListsHandler
import org.ratelog.customlist.saved.set.SetListSavedController
import org.ratelog.customlist.saved.set.SetListSavedHandler
import org.ratelog.customlist.saved.web.ListSavedModelAdvice
import org.ratelog.customlist.saved.web.ListSavedView
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import java.util.Locale

/** The Save toggle's endpoints (form + fetch), the list page state and the API. */
class SavedListsWebTest {

    private val f = OfficialListsFixture()
    private val messages = ResourceBundleMessageSource().apply {
        setBasenames("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
    private val setHandler = SetListSavedHandler(f.lists, f.official, f.saved, f.clock)
    private val controller = SetListSavedController(setHandler, messages)
    private val savedHandler = GetSavedListsHandler(f.saved, f.summaries, f.p.handler)
    private val api = ApiSavedListController(setHandler, savedHandler)

    private val afi: CustomList = f.list(f.admin, "AFI 100", isPublic = true).also { f.markOfficial(it) }
    private val ordinary: CustomList = f.list(f.admin, "Ordinary", isPublic = true)

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    // --- POST /list/{id}/saved ---

    @Test
    fun `a form post saves and goes back with a flash message`() {
        val redirect = RedirectAttributesModelMap()
        val request = MockHttpServletRequest().apply { addHeader("Referer", "http://localhost/lists?limit=48") }

        val view = controller.set(afi.id!!.value, f.andre, true, null, request, redirect)

        assertEquals("redirect:/lists?limit=48", view)
        assertEquals(SetListSavedController.SAVED_PLAIN, redirect.flashAttributes["success"])
        assertTrue(f.saved.isSaved(f.andre.id!!, afi.id!!))
    }

    @Test
    fun `fetch gets the stored state and the button texts`() {
        val saved = controller.setJson(afi.id!!.value, f.andre, true)
        val body = saved.body!!

        assertEquals(HttpStatus.OK, saved.statusCode)
        assertTrue(body.ok && body.saved)
        assertEquals("Saved", body.label)
        assertEquals("Saved \"AFI 100\". It's first in Official lists now.", body.message)
        assertTrue(body.title.contains("AFI 100"))

        val off = controller.setJson(afi.id!!.value, f.andre, false).body!!
        assertFalse(off.saved)
        assertEquals("Save", off.label)
        assertEquals("Took \"AFI 100\" off your saved lists.", off.message)
    }

    @Test
    fun `fetch errors are 400 for ordinary lists and 404 for missing ones`() {
        val bad = controller.setJson(ordinary.id!!.value, f.andre, true)
        val missing = controller.setJson(999, f.andre, true)

        assertEquals(HttpStatus.BAD_REQUEST, bad.statusCode)
        assertEquals("Only official lists can be saved.", bad.body!!.message)
        assertFalse(bad.body!!.ok)
        assertEquals(HttpStatus.NOT_FOUND, missing.statusCode)
    }

    // --- List page ---

    @Test
    fun `the list page knows whether you saved it`() {
        val advice = ListSavedModelAdvice(f.saved)
        val model = ExtendedModelMap()
        advice.addListSaved(f.andre, afi.id!!.value, model)
        assertEquals(ListSavedView(afi.id!!.value, saved = false), model["listSaved"])

        f.saved.save(f.andre.id!!, afi.id!!, 1)
        advice.addListSaved(f.andre, afi.id!!.value, model)
        assertEquals("/list/${afi.id!!.value}/saved", (model["listSaved"] as ListSavedView).href)
        assertTrue((model["listSaved"] as ListSavedView).saved)
    }

    // --- API ---

    @Test
    fun `PUT, GET and DELETE saved lists`() {
        val put = api.save(f.andre, afi.id!!.value)
        assertTrue(put.saved && put.changed)
        assertFalse(api.save(f.andre, afi.id!!.value).changed)

        val list = api.saved(f.andre)
        assertEquals(listOf("AFI 100"), list.map { it.name })
        assertTrue(list.single().isOfficial)
        assertEquals(afi.items.size, list.single().progress.total)
        assertTrue(list.single().savedAt.endsWith("Z"))

        val deleted = api.unsave(f.andre, afi.id!!.value).body!!
        assertFalse(deleted.saved)
        assertTrue(api.saved(f.andre).isEmpty())
    }

    @Test
    fun `the API refuses ordinary lists and hides missing ones`() {
        val notOfficial = assertThrows(ApiException::class.java) { api.save(f.andre, ordinary.id!!.value) }
        val missing = assertThrows(ApiException::class.java) { api.save(f.andre, 999) }

        assertEquals("not_official", notOfficial.code)
        assertEquals(HttpStatus.BAD_REQUEST, notOfficial.status)
        assertEquals(HttpStatus.NOT_FOUND, missing.status)
    }
}
