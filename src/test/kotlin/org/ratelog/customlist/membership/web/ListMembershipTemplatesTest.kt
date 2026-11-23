package org.ratelog.customlist.membership.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.browse.web.BrowseBarViewsTest
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale

/** Renders the real add-to-list sheet and its button with the real messages. */
class ListMembershipTemplatesTest {

    private val messages = BrowseBarViewsTest.testMessageSource()
    private val appContext = StaticApplicationContext().apply { refresh() }
    private val engine = SpringTemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/"
            suffix = ".html"
            templateMode = TemplateMode.HTML
            characterEncoding = "UTF-8"
            isCacheable = false
        })
        setTemplateEngineMessageSource(messages)
    }
    private val csrf = DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123")

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(fragment: String, vars: Map<String, Any?>): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", csrf)
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process("fragments/add-to-list-modal", setOf(fragment), context)
            .also { assertFalse(it.contains("??"), "every message key resolves: $it") }
    }

    private fun view(vararg rows: ListMembershipRow, tv: Boolean = false) = ListMembershipView(
        mediaType = if (tv) "tvshow" else "movie",
        mediaId = 42,
        back = if (tv) "/tv/82728" else "/movie/354912",
        rows = rows.toList(),
    )

    private val disney = ListMembershipRow(7, "Best Disney Movies", inList = true, rank = 3, order = 1)
    private val pixar = ListMembershipRow(8, "Pixar ranked", inList = true, rank = 12, order = 0)
    private val kids = ListMembershipRow(9, "Watch with kids", inList = false, rank = null, order = 2)

    private fun sheet(m: ListMembershipView?) = render("addToListModal", mapOf("listMembership" to m, "mediaId" to 42L, "mediaType" to "movie"))

    /** The form of one list, from its opening tag to its end. */
    private fun formOf(html: String, listId: Long): String {
        val start = html.indexOf("""action="/list/$listId/membership"""")
        assertTrue(start >= 0, "a form for list $listId: $html")
        return html.substring(html.lastIndexOf("<form", start), html.indexOf("</form>", start))
    }

    @Test
    fun `every list is a POST form to its membership endpoint with CSRF, the title and where to come back`() {
        val html = sheet(view(disney, pixar, kids))

        listOf(7L, 8L, 9L).forEach { id ->
            val form = formOf(html, id)
            assertTrue(form.contains("""method="post""""), form)
            assertTrue(form.contains("data-list-member"))
            assertTrue(form.contains("""name="_csrf" value="tok123""""))
            assertTrue(form.contains("""name="mediaId" value="42""""))
            assertTrue(form.contains("""name="mediaType" value="movie""""))
            assertTrue(form.contains("""name="back" value="/movie/354912""""))
        }
        assertFalse(html.contains("""action="/list/7/item""""), "the old add-only form is gone")
    }

    @Test
    fun `lists with the title are ticked, show the rank, and ask before removing`() {
        val html = sheet(view(disney, pixar, kids))

        val inIt = formOf(html, 7)
        assertTrue(inIt.contains("""name="inList" value="false""""), "a tap takes it out")
        assertTrue(inIt.contains("list-member-toggle is-in"), inIt)
        assertTrue(inIt.contains("""aria-pressed="true""""))
        assertTrue(inIt.contains("""data-confirm="Remove from &quot;Best Disney Movies&quot;? It&#39;s #3 there.""""), inIt)
        assertTrue(inIt.contains("""title="Remove from &quot;Best Disney Movies&quot;""""))
        assertTrue(Regex("""<span class="list-member-rank">#3</span>""").containsMatchIn(inIt), inIt)
        assertTrue(inIt.contains("""data-order="1""""))

        val notInIt = formOf(html, 9)
        assertTrue(notInIt.contains("""name="inList" value="true""""), "a tap adds it")
        assertFalse(notInIt.contains("is-in"))
        assertTrue(notInIt.contains("""aria-pressed="false""""))
        assertFalse(notInIt.contains("data-confirm"), "adding never asks")
        assertTrue(notInIt.contains("""title="Add to &quot;Watch with kids&quot;""""))
        assertTrue(Regex("""<span class="list-member-rank" hidden="hidden"></span>""").containsMatchIn(notInIt), notInIt)
        assertTrue(notInIt.contains("""data-title-add="Add to &quot;Watch with kids&quot;""""))
        assertTrue(notInIt.contains("""data-title-remove="Remove from &quot;Watch with kids&quot;""""))
    }

    @Test
    fun `the sheet says how many of your lists have it`() {
        assertTrue(sheet(view(disney, pixar, kids)).contains("""<p class="list-member-summary is-in" data-list-summary>In 2 of your lists</p>"""))
        assertTrue(sheet(view(disney, kids.copy(listId = 10))).contains(">In one of your lists<"))
        val none = sheet(view(kids))
        assertTrue(none.contains("""<p class="list-member-summary" data-list-summary>Not in any of your lists yet</p>"""), none)
    }

    @Test
    fun `New list sits at the top, above your lists, and refreshes the sheet and the button`() {
        val html = sheet(view(disney, kids))

        val newList = html.indexOf("""action="/list/new-with-item"""")
        assertTrue(newList >= 0)
        assertTrue(newList < html.indexOf("list-selection"), "above the lists")
        assertTrue(html.indexOf("data-list-summary") < newList, "the summary line stays under the heading")
        assertTrue(html.contains("""data-inplace="#addToListModal, [data-list-button]""""))
        assertTrue(html.contains("""data-inplace-done="New list created with this title""""))
        assertTrue(html.contains("""<label for="newListName" class="visually-hidden">Start a new list with this title</label>"""))
        assertTrue(html.contains("""placeholder="New list name""""))
        assertTrue(html.contains(">Create &amp; add<"))
    }

    @Test
    fun `without lists the sheet offers New list and says so`() {
        val html = sheet(view())

        assertFalse(html.contains("data-list-member"))
        assertFalse(html.contains("data-list-summary"))
        assertTrue(html.contains("You don&#39;t have any lists yet. Name one above to start it with this title."), html)
        assertTrue(html.contains("""action="/list/new-with-item""""))
        assertTrue(sheet(null).contains("You don&#39;t have any lists yet."), "no membership in the model: same empty sheet")
    }

    @Test
    fun `a TV title posts tvshow and comes back to the TV page`() {
        val html = sheet(view(kids, tv = true))
        val form = formOf(html, 9)
        assertTrue(form.contains("""name="mediaType" value="tvshow""""))
        assertTrue(form.contains("""name="back" value="/tv/82728""""))
    }

    // --- The title page's button ---

    @Test
    fun `the button reads Add to list until the title is in a list`() {
        val html = render("addToListButton", mapOf("m" to view(kids)))

        assertTrue(html.contains("""data-open-modal="addToListModal""""), html)
        assertTrue(html.contains("data-list-button"))
        assertTrue(html.contains("""aria-haspopup="dialog""""))
        assertTrue(html.contains("""data-lucide="list-plus""""))
        assertTrue(html.contains("""<span class="title-toggle-label">Add to list</span>"""))
        assertTrue(html.contains("""title="Add it to one of your lists""""))
        assertFalse(html.contains("is-on"))
    }

    @Test
    fun `in one or more lists it reads In N lists, ticked, with the accent`() {
        val two = render("addToListButton", mapOf("m" to view(disney, pixar, kids)))
        assertTrue(two.contains("""class="title-action title-toggle title-toggle-lists is-on""""), two)
        assertTrue(two.contains("""data-lucide="list-checks""""))
        assertTrue(two.contains("""<span class="title-toggle-label">In 2 lists</span>"""))
        assertTrue(two.contains("""title="In 2 of your lists. Tap to see which or to change it.""""))

        val one = render("addToListButton", mapOf("m" to view(disney, kids)))
        assertTrue(one.contains(">In 1 list<"))
        assertTrue(one.contains("""title="In one of your lists. Tap to see which or to change it.""""))
    }

    @Test
    fun `without membership the button is the plain Add to list`() {
        val html = render("addToListButton", mapOf("m" to null))
        assertTrue(html.contains(">Add to list<"))
        assertTrue(html.contains("""data-lucide="list-plus""""))
        assertFalse(html.contains("is-on"))
    }

    @Test
    fun `both title pages use the button, the sheet's styles and its script`() {
        listOf("movie-detail", "tvshow-detail").forEach { page ->
            val source = javaClass.classLoader.getResource("templates/$page.html")!!.readText()
            assertTrue(source.contains("~{fragments/add-to-list-modal :: addToListButton(\${listMembership})}"), page)
            assertTrue(source.contains("""<link rel="stylesheet" href="/list-membership.css">"""), page)
            assertTrue(source.contains("""<script src="/list-membership.js" defer></script>"""), page)
            assertTrue(source.contains("""<script src="/confirm.js" defer></script>"""), "$page: removing asks first")
            assertFalse(source.contains("data-open-modal=\"addToListModal\""), "$page: the old plain button is gone")
            assertEquals(1, Regex("addToListButton").findAll(source).count(), page)
        }
    }
}
