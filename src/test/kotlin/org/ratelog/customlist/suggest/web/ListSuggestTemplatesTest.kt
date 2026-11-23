package org.ratelog.customlist.suggest.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.browse.LibraryMark
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.getlistdetail.ListDetail
import org.ratelog.customlist.getlistdetail.ListDetailItem
import org.ratelog.customlist.order.sort.ListSort
import org.ratelog.customlist.order.web.ListOrderView
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.customlist.progress.web.ListProgressView
import org.ratelog.customlist.suggest.ListSuggestion
import org.ratelog.customlist.suggest.ListSuggestions
import org.ratelog.customlist.suggest.SuggestionReason
import org.ratelog.recommendations.RelatedTitle
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Renders fragments/list-suggest.html with the real messages. */
class ListSuggestTemplatesTest {

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
    private val views = ListSuggestViews(messages, Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC))

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(fragment: String, vars: Map<String, Any?>): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123"))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process("fragments/list-suggest", setOf(fragment), context)
    }

    // --- Placeholder: only on your own list with titles, not in Edit order or tick-off ---

    private fun detail(vararg titles: String) = ListDetail(
        id = CustomList.Id(7), userId = User.Id(1), name = "Pixar", isPublic = false, createdAtEpochMs = 1,
        items = titles.mapIndexed { i, t ->
            ListDetailItem(CustomListItem.Id(i + 1L), i + 1L, 100 + i, "movie", i + 1, t, "/p$i.jpg", 2001, null, false)
        },
    )

    private fun progress(tick: Boolean) = ListProgressView(
        listId = 7, total = 2, seen = 0, percent = 0, filter = ListFilter.ALL, filterParam = null, tabs = emptyList(),
        addableCount = 0, addHref = "/list/7/watchlist-unseen", visibleCount = 2, emptyKey = null, entries = emptyMap(), tick = tick,
    )

    private fun order(reorder: Boolean) = ListOrderView.of(7, numbered = true, isOwner = true, reorderRequested = reorder, sort = ListSort.LIST, total = 2)

    private fun placeholder(
        list: ListDetail? = detail("Toy Story", "Up"),
        isOwner: Boolean? = true,
        tick: Boolean = false,
        reorder: Boolean = false,
    ) = render("placeholder", mapOf("list" to list, "isOwner" to isOwner, "progress" to progress(tick), "order" to order(reorder)))

    @Test
    fun `the owner's list gets a skeleton that points at the row, with its own styles and script`() {
        val html = placeholder()

        assertTrue(html.contains("""data-src="/list/7/suggestions""""), html)
        assertTrue(html.contains("""data-list-id="7""""))
        assertTrue(html.contains("list-suggest is-pending"))
        assertTrue(html.contains("""aria-busy="true""""))
        assertEquals(8, Regex("""class="discover-skeleton"""").findAll(html).count())
        assertTrue(html.contains("""href="/list-suggest.css""""))
        assertTrue(html.contains("""src="/list-suggest.js""""))
        // list-suggest.js replaces the section: styles inside it would go with it.
        assertTrue(html.indexOf("""href="/list-suggest.css"""") < html.indexOf("<section"), "styles sit outside the swapped section")
        assertTrue(html.contains("<noscript>"), "hidden without JS")
        assertFalse(html.contains("Toy Story"), "no suggestions in the page itself: they load later")
    }

    @Test
    fun `no row for other people's lists, empty lists, Edit order or tick-off, or a missing list`() {
        assertFalse(placeholder(isOwner = false).contains("list-suggest"), "not yours")
        assertFalse(placeholder(isOwner = null).contains("list-suggest"), "logged out / unknown")
        assertFalse(placeholder(list = detail()).contains("list-suggest"), "empty list")
        assertFalse(placeholder(reorder = true).contains("list-suggest"), "Edit order")
        assertFalse(placeholder(tick = true).contains("list-suggest"), "tick-off mode")
        assertFalse(placeholder(list = null).contains("list-suggest"), "no list (error page)")
        assertFalse(
            render("placeholder", mapOf("list" to detail("Up"), "isOwner" to true, "progress" to null, "order" to null)).isBlank(),
            "no progress or order (logged-out models) still renders for the owner",
        )
    }

    @Test
    fun `the list page includes the placeholder after the list region`() {
        val page = javaClass.classLoader.getResource("templates/list-detail.html")!!.readText()
        val include = page.indexOf("fragments/list-suggest :: placeholder(\${list}, \${isOwner}, \${listProgress}, \${listOrder})")
        assertTrue(include > 0, "include line")
        assertTrue(include > page.indexOf("""id="listRegion""""), "after the list")
        assertTrue(include < page.indexOf("edit-list-modal"), "before the owner's modals")
    }

    // --- Row ---

    private fun title(id: Int, name: String, date: String?, type: MediaType = MediaType.movie, poster: String? = "/p$id.jpg") =
        RelatedTitle(id, type, name, date?.let(LocalDate::parse), poster, 7.5, 900)

    private fun suggestions(
        list: List<ListSuggestion>,
        remaining: Int = 0,
        skipped: Boolean = false,
        basedOn: Int = 3,
        size: Int = 3,
    ) = ListSuggestions(CustomList.Id(7), "Pixar", size, basedOn, list, remaining, skipped)

    private val batch = listOf(
        ListSuggestion(title(10193, "Toy Story 3", "2010-06-16"), null, SuggestionReason.SERIES, 2),
        ListSuggestion(title(2062, "Ratatouille", "2007-06-28"), LibraryMark(8.5, rated = true, status = WatchStatus.WATCHED), SuggestionReason.RECOMMENDED, 3),
        ListSuggestion(title(1084244, "Toy Story 5", "2027-06-18"), null, SuggestionReason.SERIES, 0),
        ListSuggestion(title(60059, "Better Call Saul", "2015-02-08", type = MediaType.tvshow, poster = null), null, SuggestionReason.RECOMMENDED, 1),
    )

    private fun row(view: ListSuggestView?) = render("row", mapOf("listSuggest" to view))

    @Test
    fun `nothing to show renders nothing`() {
        assertFalse(row(null).contains("<section"))
        assertEquals(null, views.row(suggestions(emptyList())), "no suggestions and nothing skipped: no row at all")
    }

    @Test
    fun `tiles link to the title, carry the quick actions, a not-interested button and an add form`() {
        val html = row(views.row(suggestions(batch, remaining = 12)))

        assertTrue(html.contains(">Suggested for this list<"), html)
        assertTrue(html.contains(">Based on the 3 titles in this list<"))
        assertTrue(html.contains("data-list-suggest"))
        assertEquals(4, Regex("""class="home-tile-wrap list-suggest-tile""").findAll(html).count())
        assertTrue(html.contains("""data-key="movie:10193""""))
        assertTrue(html.contains("""data-suggest-key="m10193""""))
        assertTrue(html.contains("""data-suggest-key="t60059""""))
        assertTrue(html.contains("""href="/movie/10193""""))
        assertTrue(html.contains("""href="/tv/60059""""))
        assertTrue(html.contains("""srcset="https://image.tmdb.org/t/p/w342/p2062.jpg 342w, https://image.tmdb.org/t/p/w500/p2062.jpg 500w""""))
        assertTrue(html.contains("""class="poster-fallback">B<"""), "posterless tile shows its initial")
        assertTrue(html.contains("is-series"))

        // "+ Add": a CSRF form that posts back to the list without JS.
        assertEquals(4, Regex("""<form class="list-suggest-add" method="post" action="/list/7/suggestions/add" data-suggest-add>""").findAll(html).count())
        assertTrue(html.contains("""name="_csrf" value="tok123""""))
        assertTrue(html.contains("""name="tmdbId" value="60059""""))
        assertTrue(html.contains("""name="type" value="tv""""))
        assertTrue(html.contains("""name="back" value="/list/7""""))
        assertTrue(html.contains("""aria-label="Add &quot;Ratatouille&quot; to this list""""), html)
        assertTrue(html.contains("<span>Add</span>"))

        // Quick actions and badges: Seen eye (on for Ratatouille, with its score), Upcoming on Toy Story 5.
        assertTrue(html.contains("""action="/seen/toggle""""))
        assertTrue(html.contains(">8.5<"))
        val upcoming = html.substringAfter("""data-key="movie:1084244"""").substringBefore("list-suggest-add")
        assertTrue(upcoming.contains(">Upcoming<"), upcoming)
        assertTrue(Regex("""data-suggest-dismiss\s+title="Not interested" aria-label="Not interested in &quot;Toy Story 3&quot;"""").containsMatchIn(html), html)

        // More suggestions, scroll arrows; no "Start over" yet.
        assertTrue(html.contains("data-suggest-more"))
        assertTrue(html.contains(""">More suggestions<"""))
        assertTrue(html.contains("""aria-label="More suggestions""""))
        assertTrue(html.contains("data-row-next"))
        assertFalse(html.contains("data-suggest-restart"))
        assertTrue(html.contains("""data-label-added="Added""""))
        assertFalse(html.contains("??"), "every message key resolves")
    }

    @Test
    fun `the last batch offers to start over, and an exhausted row says so`() {
        val last = row(views.row(suggestions(batch.take(1), remaining = 0, skipped = true)))
        assertFalse(last.contains("data-suggest-more"))
        assertTrue(last.contains("data-suggest-restart"))
        assertTrue(last.contains(">Start over<"))

        val end = row(views.row(suggestions(emptyList(), skipped = true)))
        assertTrue(end.contains("That&#39;s all the suggestions for now."), end)
        assertTrue(end.contains("data-suggest-restart"))
        assertFalse(end.contains("data-suggest-track"))
        assertFalse(end.contains("data-row-next"))
        assertEquals(1, Regex("data-suggest-restart").findAll(end).count(), "one Start over, not two")
    }

    @Test
    fun `the basis line says when only some titles were used`() {
        assertEquals("Based on 10 of the 25 titles in this list", views.basis(10, 25, Locale.ENGLISH))
        assertEquals("Based on the title in this list", views.basis(1, 1, Locale.ENGLISH))
        assertEquals("Based on the 4 titles in this list", views.basis(4, 4, Locale.ENGLISH))
    }
}
