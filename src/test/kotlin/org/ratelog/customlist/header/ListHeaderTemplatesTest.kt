package org.ratelog.customlist.header

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.getlistdetail.ListDetail
import org.ratelog.customlist.getlistdetail.ListDetailItem
import org.ratelog.customlist.official.web.ListOfficialView
import org.ratelog.customlist.order.sort.ListSort
import org.ratelog.customlist.order.web.ListOrderView
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.customlist.progress.ListProgress
import org.ratelog.customlist.progress.ListProgressEntry
import org.ratelog.customlist.progress.web.ListProgressView
import org.ratelog.customlist.progress.web.toView
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale

/**
 * The compact list header (fork, 2026-11-23): title + one "⋯" List actions menu + meta line, and one toolbar row
 * (tabs, Sort, Mark what you've seen). Renders the real fragments with the real messages.
 */
class ListHeaderTemplatesTest {

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

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(template: String, fragment: String, vars: Map<String, Any?>): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, setOf(fragment), context)
    }

    private fun list(titles: Int = 5, isPublic: Boolean = true) = ListDetail(
        id = CustomList.Id(7), userId = User.Id(1), name = "Best Disney Movies", isPublic = isPublic, createdAtEpochMs = 1,
        items = (1..titles).map { i ->
            ListDetailItem(CustomListItem.Id(100L + i), i.toLong(), 10 * i, "movie", i, "Title $i", "/p$i.jpg", 2001, null, false)
        },
    )

    // #1 Watched, #2-#4 nothing (addable), #5 Watching: 1 of 5 seen, 3 to add.
    private fun progress(filter: ListFilter = ListFilter.ALL, tick: Boolean = false, entries: Int = 5): ListProgressView =
        ListProgress(
            listId = CustomList.Id(7),
            ownerId = User.Id(1),
            entries = (1..entries).map { rank ->
                val status = when (rank) { 1 -> WatchStatus.WATCHED; 5 -> WatchStatus.WATCHING; else -> null }
                ListProgressEntry(CustomListItem.Id(100L + rank), MediaType.movie, rank.toLong(), rank, status, null, seen = rank == 1)
            },
        ).toView(filter, tick)

    private fun order(isOwner: Boolean = true, reorder: Boolean = false, total: Int = 5) =
        ListOrderView.of(7, numbered = true, isOwner = isOwner, reorderRequested = reorder, sort = ListSort.LIST, total = total)

    private fun official(official: Boolean = false, canToggle: Boolean = false) =
        ListOfficialView(official = official, canToggle = canToggle, toggleHref = "/list/7/official")

    private fun head(
        isOwner: Boolean? = true,
        progress: ListProgressView? = progress(),
        order: ListOrderView? = order(isOwner == true),
        official: ListOfficialView? = official(),
        list: ListDetail = list(),
    ) = render(
        "fragments/list-header", "head",
        mapOf("list" to list, "isOwner" to isOwner, "progress" to progress, "order" to order, "official" to official),
    )

    private fun menu(html: String): String {
        val start = html.indexOf("data-list-actions")
        return if (start < 0) "" else html.substring(start, html.indexOf("</details>", start))
    }

    @Test
    fun `the owner gets one menu with add, Edit order, Edit and Delete, in that order`() {
        val html = head()
        val menu = menu(html)

        assertTrue(html.contains("""<h1 class="list-head-title">Best Disney Movies</h1>"""), html)
        assertEquals(1, Regex("data-list-actions").findAll(html).count(), "one menu")
        assertTrue(html.contains("""title="List actions" aria-label="List actions""""))

        val add = menu.indexOf("Add 3 unseen to watchlist")
        val reorder = menu.indexOf(""">Edit order<""")
        val edit = menu.indexOf("""data-open-modal="editModal"""")
        val delete = menu.indexOf("""data-open-modal="deleteListModal"""")
        assertTrue(add in 0 until reorder && reorder < edit && edit < delete, "add=$add reorder=$reorder edit=$edit delete=$delete")
        assertTrue(menu.contains(""">Edit list<"""))
        assertTrue(menu.contains(""">Delete list<"""))
        assertTrue(menu.contains("""class="list-actions-item is-danger""""))
        // Edit order stays a plain link that list-filter.js opens with location.replace (a.list-order-start).
        assertTrue(menu.contains("""<a class="list-actions-item list-order-start" href="/list/7?reorder=1">"""))
        assertFalse(menu.contains("/list/7/official"), "no official toggle for a regular owner")
        assertFalse(html.contains("??"), "every message key resolves")
    }

    @Test
    fun `adding the unseen asks first, saves in place and keeps the tab`() {
        val menu = menu(head(progress = progress(ListFilter.UNSEEN)))

        assertTrue(menu.contains("""action="/list/7/watchlist-unseen""""))
        assertTrue(menu.contains("""method="post""""))
        assertTrue(menu.contains("""data-inplace="#listRegion""""), "saves in place, with the flash as a toast")
        assertTrue(menu.contains("""name="_csrf" value="tok123""""))
        assertTrue(menu.contains("""name="show" value="unseen""""), "the redirect keeps the tab")
        val confirm = Regex("""data-confirm="([^"]*)"""").find(menu)!!.groupValues[1]
        assertTrue(confirm.startsWith("Add 3 titles from &quot;Best Disney Movies&quot; to your watchlist?"), confirm)

        assertFalse(menu(head(progress = progress(ListFilter.ALL))).contains("""name="show""""), "All sends no show field")
    }

    @Test
    fun `one addable title reads naturally, and nothing to add means no item`() {
        val one = ListProgress(
            CustomList.Id(7), User.Id(1),
            listOf(ListProgressEntry(CustomListItem.Id(101), MediaType.movie, 1, 1, null, null, seen = false)),
        ).toView(ListFilter.ALL)
        assertTrue(menu(head(progress = one)).contains(">Add 1 unseen to watchlist<"))

        val none = ListProgress(
            CustomList.Id(7), User.Id(1),
            listOf(ListProgressEntry(CustomListItem.Id(101), MediaType.movie, 1, 1, WatchStatus.WATCHED, null, seen = true)),
        ).toView(ListFilter.ALL)
        val menu = menu(head(progress = none))
        assertFalse(menu.contains("watchlist-unseen"))
        assertTrue(menu.contains("editModal"), "the owner still has the menu")
    }

    @Test
    fun `someone else's list only offers the add, and no menu at all when there is nothing to add`() {
        val visitor = head(isOwner = false, official = official(official = true))
        val menu = menu(visitor)
        assertTrue(menu.contains("Add 3 unseen to watchlist"))
        listOf("editModal", "deleteListModal", "reorder=1", "/list/7/official").forEach { assertFalse(menu.contains(it), it) }
        assertTrue(visitor.contains(">Official list<"), "the badge sits on the meta line")

        val allSeen = ListProgress(
            CustomList.Id(7), User.Id(1),
            listOf(ListProgressEntry(CustomListItem.Id(101), MediaType.movie, 1, 1, WatchStatus.WATCHED, null, seen = true)),
        ).toView(ListFilter.ALL)
        assertFalse(head(isOwner = false, progress = allSeen).contains("data-list-actions"), "an empty menu isn't shown")
        assertFalse(head(isOwner = null, progress = null).contains("data-list-actions"))
    }

    @Test
    fun `admins get Make official in the menu, on any list`() {
        val menu = menu(head(isOwner = false, official = official(official = false, canToggle = true)))

        assertTrue(menu.contains("""action="/list/7/official""""))
        assertTrue(menu.contains("""name="official" value="true""""))
        assertTrue(menu.contains("""<button type="submit" class="list-actions-item""""), "styled as a menu item")
        assertTrue(menu.contains(">Make official<"))
        assertTrue(menu.contains("""data-inplace="#listRegion""""))
        assertTrue(menu.contains("data-confirm="))

        val owner = menu(head(official = official(official = true, canToggle = true)))
        assertTrue(owner.contains(">Remove official<"))
        assertTrue(owner.indexOf(">Remove official<") < owner.indexOf("deleteListModal"), "Delete stays last")
    }

    @Test
    fun `the meta line carries Public, the badge and the progress line with the data list-tick js updates`() {
        val html = head(official = official(official = true))
        val meta = html.substring(html.indexOf("list-head-meta"))

        assertTrue(meta.contains(""">Public<"""))
        assertTrue(meta.contains(">Official list<"))
        assertTrue(meta.contains("You&#39;ve seen 1 of 5") || meta.contains("You've seen 1 of 5"), meta)
        assertTrue(meta.contains("data-progress-text"))
        assertTrue(meta.contains("data-list-progress-bar"))
        assertTrue(meta.contains("""aria-valuenow="1""""))
        assertTrue(meta.contains("width: 20%"))

        val privateEmpty = head(list = list(titles = 0, isPublic = false), progress = progress(entries = 0))
        assertFalse(privateEmpty.contains("list-head-meta"), "nothing to say: no empty line")
        assertTrue(privateEmpty.contains("data-list-actions"), "the owner can still edit or delete an empty list")
    }

    @Test
    fun `reorder mode keeps Edit and Delete, and drops the add and Edit order`() {
        val menu = menu(head(order = order(reorder = true)))

        assertTrue(menu.contains("editModal"))
        assertTrue(menu.contains("deleteListModal"))
        assertFalse(menu.contains("watchlist-unseen"))
        assertFalse(menu.contains("reorder=1"))
    }

    // --- The toolbar row (fragments/list-progress :: summary) ---

    private fun summary(progress: ListProgressView?, order: ListOrderView?) = render(
        "fragments/list-progress", "summary",
        mapOf("progress" to progress, "listName" to "Best Disney Movies", "order" to order),
    )

    @Test
    fun `tabs, Sort and Mark what you've seen share one toolbar, with no bulk button`() {
        val html = summary(progress(), order())
        val toolbar = html.substring(html.indexOf("list-progress-toolbar"))

        val tabs = toolbar.indexOf("list-filter-tabs")
        val sort = toolbar.indexOf("data-list-sort")
        val mark = toolbar.indexOf("list-tick-start")
        assertTrue(tabs in 0 until sort && sort < mark, "tabs=$tabs sort=$sort mark=$mark")
        assertTrue(toolbar.contains(""">Mark what you&#39;ve seen<""") || toolbar.contains(""">Mark what you've seen<"""))
        assertTrue(toolbar.contains(""">Mark seen<"""), "the short label for phones")
        assertFalse(html.contains("watchlist-unseen"), "the bulk add lives in the menu now")
        assertFalse(html.contains("Edit order"), "so does Edit order")
        assertFalse(html.contains("list-progress-line"), "the progress line moved up to the meta line")

        val ticking = summary(progress(tick = true), order())
        assertFalse(ticking.contains("list-tick-start"))
        assertTrue(ticking.contains("data-list-sort"), "Sort stays in tick-off mode")
        assertTrue(ticking.contains("list-tick-bar"))

        assertFalse(summary(progress(), null).contains("data-list-sort"), "no Sort without the order view")
        assertFalse(summary(progress(), order(total = 1)).contains("data-list-sort"), "nor for one title")
    }

    @Test
    fun `the list page uses the header, and shows the Sort row on its own only without the toolbar`() {
        val detail = javaClass.getResource("/templates/list-detail.html")!!.readText()

        val head = detail.indexOf("fragments/list-header :: head(")
        val summary = detail.indexOf("fragments/list-progress :: summary(\${listProgress}, \${list.name}, \${listOrder})")
        val alone = detail.indexOf("fragments/list-order :: toolbar(")
        assertTrue(head in 0 until summary && summary < alone, "head=$head summary=$summary alone=$alone")
        assertTrue(detail.contains("""th:if="${'$'}{listProgress == null or listProgress.total == 0}""""))
        assertFalse(detail.contains("data-open-modal=\"editModal\""), "Edit / Delete are menu items")
        assertTrue(detail.contains("""<script src="/list-header.js" defer></script>"""))
        assertTrue(detail.contains("""<link rel="stylesheet" href="/list-header.css">"""))
    }
}
