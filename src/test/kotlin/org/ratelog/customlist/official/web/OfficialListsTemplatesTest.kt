package org.ratelog.customlist.official.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.browse.web.DiscoverRowView
import org.ratelog.customlist.browse.ListCardView
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.context.LazyContextVariable
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Renders the real official-lists fragments, list card and Discover rows with the real messages. */
class OfficialListsTemplatesTest {

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

    private fun render(template: String, fragment: String, vars: Map<String, Any?>): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, setOf(fragment), context).also { assertFalse(it.contains("??"), "every message key resolves: $it") }
    }

    private fun card(name: String, official: Boolean = true, owner: String? = null, seen: Int? = 3, isPublic: Boolean = true) =
        ListCardView("/list/7", name, 25, isPublic, owner, listOf(null, null, null, null), seen, isOfficial = official)

    private fun row(total: Int = 3, id: String = "official-lists") =
        OfficialListsRowView(listOf(card("AFI 100"), card("Sight and Sound")), total = total, id = id)

    // --- List card ---

    @Test
    fun `an official card shows the badge and the viewer's progress instead of owner or visibility`() {
        val html = render("fragments/list-card", "listCard", mapOf("card" to card("AFI 100", owner = "admin")))

        assertTrue(html.contains("list-tile-visibility list-tile-official"))
        assertTrue(html.contains(">Official<"))
        assertTrue(html.contains("""data-lucide="badge-check""""))
        assertTrue(html.contains(">3/25 seen<"))
        assertFalse(html.contains("by admin"))
        assertFalse(html.contains(">Public<"))
    }

    @Test
    fun `ordinary cards are unchanged`() {
        val theirs = render("fragments/list-card", "listCard", mapOf("card" to card("Theirs", official = false, owner = "marisol")))
        assertTrue(theirs.contains("by marisol"))
        assertFalse(theirs.contains("list-tile-official"))

        val mine = render("fragments/list-card", "listCard", mapOf("card" to card("Mine", official = false)))
        assertTrue(mine.contains(">Public<"))
        assertFalse(mine.contains("list-tile-official"))
    }

    // --- Lists tab row ---

    @Test
    fun `the Lists tab row has a heading, See all and the cards`() {
        val html = render("fragments/official-lists", "listsSection", mapOf("row" to row(total = 26)))

        assertTrue(html.contains("""<section class="home-row official-row lists-section" id="official-lists""""), html)
        assertTrue(html.contains("""aria-labelledby="official-lists-title""""))
        assertTrue(html.contains(""">Official lists</h2>"""))
        assertTrue(html.contains("""href="/lists/official""""))
        assertTrue(html.contains(">See all 26<"))
        assertTrue(html.contains("""aria-label="See all 26 official lists""""))
        assertTrue(html.contains("home-row-track is-lists"))
        assertEquals(2, Regex("""class="list-tile"""").findAll(html).count())
        assertTrue(html.contains("""<script src="/home.js" defer>"""), "home.js runs the row arrows on /lists")
        assertTrue(html.contains("/official-lists.css"))
    }

    @Test
    fun `no row renders without official lists`() {
        val html = render("fragments/official-lists", "listsSection", mapOf("row" to null))

        assertFalse(html.contains("official-row"))
        assertFalse(html.contains("home.js"))
    }

    // --- List page header ---

    private fun header(view: ListOfficialView?) =
        render("fragments/official-lists", "listHeader", mapOf("official" to view, "listName" to "AFI's 100 Years", "_csrf" to csrf))

    @Test
    fun `everyone sees the badge on an official list, only admins get the toggle`() {
        val forUser = header(ListOfficialView(official = true, canToggle = false, toggleHref = "/list/7/official"))
        assertTrue(forUser.contains("list-official-badge"))
        assertTrue(forUser.contains(">Official list<"))
        assertFalse(forUser.contains("<form"), "no toggle for a regular user")
        assertFalse(forUser.contains("/list/7/official"))

        val forAdmin = header(ListOfficialView(official = true, canToggle = true, toggleHref = "/list/7/official"))
        assertTrue(forAdmin.contains("""action="/list/7/official""""))
        assertTrue(forAdmin.contains("""method="post""""))
        assertTrue(forAdmin.contains("""name="_csrf" value="tok123""""))
        assertTrue(forAdmin.contains("""name="official" value="false""""), "the toggle un-marks an official list")
        assertTrue(forAdmin.contains(">Remove official<"))
        val confirm = Regex("""data-confirm="([^"]*)"""").find(forAdmin)!!.groupValues[1]
        assertTrue(confirm.startsWith("Remove &quot;AFI&#39;s 100 Years&quot; from the official lists?") ||
            confirm.startsWith("Remove &quot;AFI's 100 Years&quot; from the official lists?"), confirm)
    }

    @Test
    fun `admins can mark an ordinary list, everyone else sees nothing`() {
        val forAdmin = header(ListOfficialView(official = false, canToggle = true, toggleHref = "/list/7/official"))
        assertFalse(forAdmin.contains("list-official-badge"))
        assertTrue(forAdmin.contains("""name="official" value="true""""))
        assertTrue(forAdmin.contains(">Make official<"))
        assertTrue(forAdmin.contains("Every account will see it on their Lists tab and on Discover."), "a public list: everyone will see it")
        assertFalse(forAdmin.contains("becomes public"), "marking no longer publishes a list")

        val forUser = header(ListOfficialView(official = false, canToggle = false, toggleHref = "/list/7/official"))
        assertFalse(forUser.contains("list-official-bar"))
        assertFalse(header(null).contains("list-official-bar"))
    }

    // --- Discover ---

    private fun discoverRow(id: String) = DiscoverRowView(id = id, title = id, moreHref = "/discover", srcHref = "/discover/row?row=$id", tiles = emptyList())

    private fun lazy(value: OfficialListsRowView?, loads: AtomicInteger) = object : LazyContextVariable<OfficialListsRowView?>() {
        override fun loadValue(): OfficialListsRowView? {
            loads.incrementAndGet()
            return value
        }
    }

    @Test
    fun `the Discover row comes right after Popular now`() {
        val loads = AtomicInteger()
        val html = render(
            "fragments/discover-rows", "rows",
            mapOf(
                "rows" to listOf(discoverRow("discover-row-popular"), discoverRow("discover-row-horror"), discoverRow("discover-row-comedy")),
                "officialListsRow" to lazy(row(id = "discover-official-lists"), loads),
                "_csrf" to csrf,
            ),
        )

        val popular = html.indexOf("""id="discover-row-popular"""")
        val official = html.indexOf("""id="discover-official-lists"""")
        val horror = html.indexOf("""id="discover-row-horror"""")
        assertTrue(popular in 0 until official && official < horror, "popular=$popular official=$official horror=$horror")
        assertEquals(1, Regex("""id="discover-official-lists"""").findAll(html).count(), "only once")
        assertTrue(html.contains("""class="home-row official-row discover-row""""), "discover.js runs its arrows")
        assertFalse(Regex("""id="discover-official-lists"[^>]*data-src""").containsMatchIn(html), "never a pending row")
        assertEquals(1, loads.get())
    }

    @Test
    fun `the Discover row still shows when every TMDB row failed`() {
        val html = render(
            "fragments/discover-rows", "rows",
            mapOf("rows" to emptyList<DiscoverRowView>(), "officialListsRow" to lazy(row(id = "discover-official-lists"), AtomicInteger())),
        )

        assertTrue(html.contains("""id="discover-official-lists""""))
    }

    @Test
    fun `no official lists, no Discover row, and lazy rows never load it`() {
        val none = render(
            "fragments/discover-rows", "rows",
            mapOf("rows" to listOf(discoverRow("discover-row-popular")), "officialListsRow" to lazy(null, AtomicInteger())),
        )
        assertFalse(none.contains("official-row"))

        val loads = AtomicInteger()
        render("fragments/discover-rows", "lazyRow", mapOf("row" to discoverRow("discover-row-horror"), "officialListsRow" to lazy(row(), loads), "_csrf" to csrf))
        assertEquals(0, loads.get(), "/discover/row fragments don't query official lists")
    }

    // --- Include points ---

    @Test
    fun `the Lists tab puts official lists above My lists, and the list page puts the badge under the title`() {
        fun source(path: String) = javaClass.getResource("/templates/$path")!!.readText()
        val lists = source("lists.html")
        val officialAt = lists.indexOf("fragments/official-lists :: listsSection")
        assertTrue(officialAt in 0 until lists.indexOf("""aria-labelledby="my-lists-title""""), "official lists come first")

        val detail = source("list-detail.html")
        val title = detail.indexOf("th:text=\"\${list.name}\">List Name</h1>")
        val badge = detail.indexOf("fragments/official-lists :: listHeader")
        assertTrue(title in 0 until badge && badge < detail.indexOf("data-open-modal=\"editModal\""), "right under the title")
    }

    // --- Hidden (private) official lists, 2026-11-23 ---

    @Test
    fun `a private official card reads Official, Hidden, muted, on its owner's lists`() {
        val html = render("fragments/list-card", "listCard", mapOf("card" to card("Drafts", isPublic = false)))

        assertTrue(html.contains("list-tile-visibility list-tile-official is-hidden"), html)
        assertTrue(html.contains(">Official · Hidden<"))
        assertTrue(html.contains("""data-lucide="eye-off""""))
        assertTrue(html.contains("only you can see it"))
        assertFalse(html.contains(">Official<"), "not the shared badge")
        assertFalse(html.contains(">Private<"), "one pill, not two")

        val shown = render("fragments/list-card", "listCard", mapOf("card" to card("AFI 100")))
        assertFalse(shown.contains("is-hidden"))
        assertFalse(shown.contains("Hidden"))
    }

    @Test
    fun `the list page says Official list, Hidden, with a hint, to the owner of a private official list`() {
        val hidden = header(ListOfficialView(official = true, canToggle = false, toggleHref = "/list/7/official", listPublic = false))

        assertTrue(hidden.contains("""class="list-official-badge is-hidden""""), hidden)
        assertTrue(hidden.contains(">Official list · Hidden<"))
        assertTrue(hidden.contains("""data-lucide="eye-off""""))
        assertTrue(hidden.contains("""class="list-official-hint">Only you can see it. Make it public to show it in Official lists.<"""))

        val shown = header(ListOfficialView(official = true, canToggle = false, toggleHref = "/list/7/official", listPublic = true))
        assertTrue(shown.contains(">Official list<"))
        assertFalse(shown.contains("is-hidden"))
        assertFalse(shown.contains("list-official-hint"))
    }

    @Test
    fun `the admin toggle's confirmation says what happens to who can see it`() {
        fun confirmOf(view: ListOfficialView) = Regex("""data-confirm="([^"]*)"""").find(header(view))!!.groupValues[1]
        fun view(official: Boolean, listPublic: Boolean) =
            ListOfficialView(official = official, canToggle = true, toggleHref = "/list/7/official", listPublic = listPublic)

        assertTrue(confirmOf(view(official = false, listPublic = true)).endsWith("an official list? Every account will see it on their Lists tab and on Discover."))
        assertTrue(confirmOf(view(official = false, listPublic = false)).endsWith("It is private, so it stays hidden (only you can see it) until you make it public."))
        assertTrue(confirmOf(view(official = true, listPublic = true)).endsWith("It stays public; its owner can make it private again."))
        assertTrue(confirmOf(view(official = true, listPublic = false)).endsWith("from the official lists? It stays private."))
    }
}
