package org.ratelog.customlist.order.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.getlistdetail.ListDetail
import org.ratelog.customlist.getlistdetail.ListDetailItem
import org.ratelog.customlist.order.sort.ListSort
import org.ratelog.user.User
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.Context
import org.thymeleaf.context.WebContext
import org.thymeleaf.web.servlet.JakartaServletWebApplication
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale

/** Renders the real list-order fragments and list modals with the real messages. */
class ListOrderTemplatesTest {

    private val messages = ResourceBundleMessageSource().apply {
        setBasenames("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
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

    private fun render(template: String, fragment: String, vars: Map<String, Any?>, web: Boolean = false): String {
        // The modals use @{...} links, which need a web context.
        val context = (if (web) webContext() else Context(Locale.ENGLISH)).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, setOf(fragment), context)
    }

    private fun webContext(): WebContext {
        val application = JakartaServletWebApplication.buildApplication(MockServletContext())
        return WebContext(application.buildExchange(MockHttpServletRequest(), MockHttpServletResponse()), Locale.ENGLISH)
    }

    private fun item(id: Long, title: String, year: Int?, poster: String? = "/p$id.jpg") = ListDetailItem(
        id = CustomListItem.Id(id), mediaId = id, tmdbId = id.toInt() * 10, mediaType = "movie", position = id.toInt(),
        title = title, posterPath = poster, year = year, score = null, isFollowed = false,
    )

    private val items = listOf(item(11, "Heat", 1995), item(12, "Alien", 1979, poster = null), item(13, "Coco", null))

    private fun view(
        numbered: Boolean = true,
        isOwner: Boolean = true,
        reorder: Boolean = false,
        sort: ListSort = ListSort.LIST,
        total: Int = 3,
        show: String? = null,
    ) = ListOrderView.of(7, numbered, isOwner, reorder, sort, total, show = show)

    private fun toolbar(view: ListOrderView?) = render("fragments/list-order", "toolbar", mapOf("order" to view))
    private fun editor(view: ListOrderView?, extra: Map<String, Any?> = emptyMap()) =
        render("fragments/list-order", "editor", mapOf("order" to view, "items" to items, "_csrf" to csrf) + extra)

    // --- Toolbar: Sort menu (Edit order is an item of the header's "⋯" menu since 2026-11-23, see ListHeaderTemplatesTest) ---

    @Test
    fun `the toolbar has every sort option as a plain link`() {
        val html = toolbar(view(show = "unseen", sort = ListSort.NEWEST))

        listOf(
            "/list/7?show=unseen", "/list/7?show=unseen&sort=reverse", "/list/7?show=unseen&sort=title",
            "/list/7?show=unseen&sort=newest", "/list/7?show=unseen&sort=oldest", "/list/7?show=unseen&sort=rating",
            "/list/7?show=unseen&sort=added",
        ).forEach { assertTrue(html.contains("""href="${it.replace("&", "&amp;")}""""), it) }
        listOf("List order", "Reverse order", "Title (A-Z)", "Newest release", "Oldest release", "Your rating", "Recently added")
            .forEach { assertTrue(html.contains(">$it<"), it) }
        assertEquals(1, Regex("""aria-current="true"""").findAll(html).count())
        assertTrue(Regex("""<span class="list-sort-value">Newest release</span>""").containsMatchIn(html), "the button shows the current sort")
        assertTrue(html.contains("is-sorted"))
        assertFalse(html.contains("reorder=1"), "Edit order moved to the header menu")
        assertFalse(html.contains("??"), "every message key resolves")
        assertTrue(render("fragments/list-order", "sort", mapOf("order" to view())).contains("data-list-sort"), "the Sort menu alone")
    }

    @Test
    fun `no toolbar while reordering or for one title`() {
        val visitor = toolbar(view(isOwner = false))
        assertTrue(visitor.contains("list-sort"))
        assertFalse(visitor.contains("reorder=1"))
        assertFalse(visitor.contains("is-sorted"))

        assertFalse(toolbar(view(reorder = true)).contains("list-order-toolbar"))
        assertFalse(toolbar(view(total = 1)).contains("list-order-toolbar"))
        assertFalse(toolbar(null).contains("list-order-toolbar"))
    }

    // --- Reorder mode ---

    @Test
    fun `reorder mode renders a form per row that works without JS`() {
        val html = editor(view(reorder = true))

        assertEquals(3, Regex("""<li class="list-order-row"""").findAll(html).count())
        assertTrue(html.contains("""id="order-item-11""""))
        assertTrue(html.contains("""action="/list/7/item/11/move""""))
        assertTrue(html.contains("""name="_csrf" value="tok123""""))
        assertTrue(Regex("""name="position"[^>]*value="1"""").containsMatchIn(html), "the number field holds the place")
        assertTrue(Regex("""id="order-pos-13" value="3"""").containsMatchIn(html))
        assertEquals(3, Regex("""max="3"""").findAll(html).count())
        // Up / down once per row; top / bottom twice (inline on wide screens, in the "more" menu on phones).
        mapOf("top" to 6, "up" to 3, "down" to 3, "bottom" to 6).forEach { (to, count) ->
            assertEquals(count, Regex("""name="to" value="$to"""").findAll(html).count(), to)
        }
        assertEquals(3, Regex("""<details class="list-order-more list-order-narrow"""").findAll(html).count())
        assertTrue(html.contains("""aria-label="More moves for &quot;Alien&quot;""""))
        assertTrue(html.contains("""data-lucide="grip-vertical""""))
        // The default (first) submit button posts the number, not an arrow.
        val form = html.substring(html.indexOf("""action="/list/7/item/12/move""""))
        assertTrue(form.indexOf("list-order-go") < form.indexOf("""value="top""""))
        assertTrue(html.contains("""aria-label="Move &quot;Heat&quot; up one""""))
        assertTrue(html.contains("Place of &quot;Coco&quot;") || html.contains("Place of \"Coco\""))
        assertTrue(html.contains("https://image.tmdb.org/t/p/w92/p11.jpg"))
        assertTrue(html.contains(""">A</span>"""), "no poster: first letter")
        assertTrue(html.contains("""href="/list/7""""), "Done")
        assertFalse(html.contains("??"), "every message key resolves")
    }

    @Test
    fun `the first row can't go up and the last can't go down`() {
        val html = editor(view(reorder = true))
        val rows = html.split("""<li class="list-order-row"""").drop(1)

        fun disabled(row: String, to: String) = Regex("""value="$to"[^>]*disabled""").containsMatchIn(row)
        assertTrue(disabled(rows[0], "top") && disabled(rows[0], "up"))
        assertFalse(disabled(rows[0], "down") || disabled(rows[0], "bottom"))
        assertFalse(disabled(rows[1], "up") || disabled(rows[1], "down"))
        assertTrue(disabled(rows[2], "down") && disabled(rows[2], "bottom"))
    }

    @Test
    fun `a plain list says its numbers only show here, and the flash shows after a plain post`() {
        val html = editor(view(reorder = true, numbered = false), mapOf("listOrderNotice" to "Moved \"Heat\" to #1."))
        assertTrue(html.contains("the numbers only show while you edit the order"))
        assertTrue(html.contains("""class="feedback success""""))
        assertTrue(html.contains("Moved &quot;Heat&quot; to #1.") || html.contains("Moved \"Heat\" to #1."))

        assertFalse(editor(view(reorder = true)).contains("feedback"))
    }

    @Test
    fun `nothing renders outside reorder mode`() {
        assertFalse(editor(view(reorder = false)).contains("list-order-editor"))
        assertFalse(editor(view(reorder = true, isOwner = false)).contains("list-order-editor"))
        assertFalse(editor(null).contains("list-order-editor"))
    }

    // --- The Numbered list checkbox ---

    private val listDetail = ListDetail(CustomList.Id(7), User.Id(1), "Heist classics", isPublic = false, createdAtEpochMs = 0L, items = items)

    @Test
    fun `the edit modal shows Numbered list with the list's value and the marker`() {
        val numbered = render("fragments/edit-list-modal", "editListModal", mapOf("list" to listDetail, "listOrder" to view(), "_csrf" to csrf), web = true)
        assertTrue(numbered.contains("""name="_numbered" value="1""""))
        assertTrue(Regex("""name="numbered" value="true" checked""").containsMatchIn(numbered))
        assertTrue(numbered.contains(">Numbered list<"))

        val plain = render("fragments/edit-list-modal", "editListModal", mapOf("list" to listDetail, "listOrder" to view(numbered = false), "_csrf" to csrf), web = true)
        assertTrue(plain.contains("""name="numbered" value="true">"""), "unchecked for a plain list")
    }

    @Test
    fun `the create modal starts plain`() {
        val html = render("fragments/create-list-modal", "createListModal", mapOf("_csrf" to csrf), web = true)
        // New lists start plain: the box is there, unticked, with the marker so an unticked submit saves "plain".
        assertTrue(html.contains("""name="numbered" value="true">"""), "unticked by default")
        assertFalse(Regex("""name="numbered" value="true" checked""").containsMatchIn(html), "not ticked by default")
        assertTrue(html.contains("""name="_numbered""""), "marker sent")
        assertTrue(html.contains("""name="_numbered" value="1""""))
        assertFalse(html.contains("??"))
    }

    // --- Include points in the list page ---

    @Test
    fun `the list page wires the toolbar, the editor, the plain-list class and the assets`() {
        val page = javaClass.getResource("/templates/list-detail.html")!!.readText()

        assertTrue(page.contains("""id="listRegion" th:classappend="${'$'}{listOrder?.regionClass}""""))
        val summary = page.indexOf("fragments/list-progress :: summary")
        val toolbar = page.indexOf("fragments/list-order :: toolbar")
        val grid = page.indexOf("""<div class="list-grid"""")
        val editor = page.indexOf("fragments/list-order :: editor")
        assertTrue(summary in 0 until toolbar && toolbar < grid && grid < editor, "summary, toolbar, grid, editor")
        assertTrue(page.contains("""th:unless="${'$'}{listOrder?.reordering == true}""""), "the grid is not rendered in reorder mode")
        assertTrue(page.contains("/list-order.css"))
        assertTrue(page.contains("/list-order.js"))
    }

    @Test
    fun `the stylesheet hides ranks on plain lists and the tabs while reordering`() {
        val css = javaClass.getResource("/static/list-order.css")!!.readText()
        assertTrue(Regex("""\.is-plain-list \.list-rank \{\s*display: none;""").containsMatchIn(css))
        assertTrue(Regex("""\.is-reordering \.list-progress \{\s*display: none;""").containsMatchIn(css))
        assertTrue(css.contains("touch-action: none"), "touch drags start on the grip only")
    }
}
