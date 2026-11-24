package org.ratelog.customlist.progress.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.browse.ListCardView
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.customlist.progress.ListProgress
import org.ratelog.customlist.progress.ListProgressEntry
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale

/** Renders the real list-progress fragments and list card with the real messages (MessageFormat quoting included). */
class ListProgressTemplatesTest {

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
        return engine.process(template, setOf(fragment), context)
    }

    // #1 Watched + 9.0, #2 nothing, #3 Watching, #4 nothing, #5 Dropped.
    private val progress = ListProgress(
        listId = CustomList.Id(7),
        ownerId = User.Id(1),
        entries = listOf(
            entry(1, WatchStatus.WATCHED, 9.0, seen = true),
            entry(2, null, null, seen = false),
            entry(3, WatchStatus.WATCHING, null, seen = false),
            entry(4, null, null, seen = false),
            entry(5, WatchStatus.DROPPED, null, seen = false),
        ),
    )

    private fun summary(view: ListProgressView?, extra: Map<String, Any?> = emptyMap()) = render(
        "fragments/list-progress", "summary",
        mapOf("progress" to view, "listName" to "Best Disney Movies", "_csrf" to DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok123")) + extra,
    )

    @Test
    fun `the line shows the progress and the bar, the summary the tabs (the bulk form is in the header menu)`() {
        val view = progress.toView(ListFilter.UNSEEN)
        val line = render("fragments/list-progress", "line", mapOf("progress" to view))

        assertTrue(line.contains("You&#39;ve seen 1 of 5") || line.contains("You've seen 1 of 5"), line)
        assertTrue(line.contains("""aria-valuenow="1""""))
        assertTrue(line.contains("""aria-valuemax="5""""))
        assertTrue(line.contains("width: 20%"))
        assertTrue(line.contains("data-list-progress-bar"), "list-tick.js finds the bar outside the section")

        val html = summary(view)
        assertTrue(html.contains("""href="/list/7""""))
        assertTrue(html.contains("""href="/list/7?show=unseen""""))
        assertTrue(html.contains("""href="/list/7?show=seen""""))
        assertEquals(1, Regex("""aria-current="page"""").findAll(html).count())
        assertTrue(Regex("""<a href="/list/7\?show=unseen" class="status-segment active"""").containsMatchIn(html), "Not seen is active")
        listOf(">All<", ">Not seen<", ">Seen<").forEach { assertTrue(html.contains(it), it) }
        // The bulk "add unseen" form moved to the "⋯" menu (ListHeaderTemplatesTest).
        assertFalse(html.contains("watchlist-unseen"))
        assertFalse(html.contains("??"), "every message key resolves")
        assertFalse(render("fragments/list-progress", "line", mapOf("progress" to null)).contains("list-progress-line"))
    }

    @Test
    fun `no button when nothing is addable, and an empty tab explains itself`() {
        val allSeen = ListProgress(CustomList.Id(7), User.Id(1), listOf(entry(1, WatchStatus.WATCHED, null, seen = true)))

        val html = summary(allSeen.toView(ListFilter.UNSEEN))

        assertFalse(html.contains("watchlist-unseen"))
        assertTrue(html.contains("seen everything on this list"))
    }

    @Test
    fun `nothing renders without progress or for an empty list`() {
        assertFalse(summary(null).contains("list-progress"))
        assertFalse(summary(ListProgress(CustomList.Id(7), User.Id(1), emptyList()).toView(ListFilter.ALL)).contains("list-progress"))
    }

    @Test
    fun `badges show rank, status and score`() {
        val view = progress.toView(ListFilter.ALL)

        val watched = render("fragments/list-progress", "badges", mapOf("entry" to view.entry(101)))
        assertTrue(watched.contains(""">1</span>"""))
        assertTrue(watched.contains("""title="No. 1 on this list""""))
        assertTrue(watched.contains("""class="home-status list-status is-watched""""))
        assertTrue(watched.contains("""aria-label="Watched""""))
        assertTrue(watched.contains("""data-lucide="eye""""))
        assertTrue(watched.contains("""class="home-score" title="Your score">9.0<"""))

        val none = render("fragments/list-progress", "badges", mapOf("entry" to view.entry(102)))
        assertTrue(none.contains("list-rank"))
        assertFalse(none.contains("home-status"))
        assertFalse(none.contains("home-score"))

        assertFalse(render("fragments/list-progress", "badges", mapOf("entry" to null)).contains("list-rank"))
    }

    @Test
    fun `back link and the bulk-add flash`() {
        val own = render("fragments/list-progress", "top", mapOf("back" to ListBackLink.LISTS))
        assertTrue(own.contains("""href="/lists""""))
        assertTrue(own.contains(">Lists<"))
        assertFalse(own.contains("feedback"))

        val other = render("fragments/list-progress", "top", mapOf("back" to ListBackLink.profile(3, "marisol"), "listWatchlistAdded" to 12))
        assertTrue(other.contains("""href="/profile/3""""))
        assertTrue(other.contains("marisol&#39;s profile") || other.contains("marisol's profile"), other)
        assertTrue(other.contains("Added 12 titles to your watchlist."))

        val one = render("fragments/list-progress", "top", mapOf("back" to ListBackLink.LISTS, "listWatchlistAdded" to 1))
        assertTrue(one.contains("Added 1 title to your watchlist."))
    }

    @Test
    fun `list card shows seen of total when known, the title count otherwise`() {
        fun card(seen: Int?, count: Int) = ListCardView("/list/1", "Top", count, true, null, listOf(null, null, null, null), seen)

        assertTrue(render("fragments/list-card", "listCard", mapOf("card" to card(9, 25))).contains(">9/25 seen<"))
        assertTrue(render("fragments/list-card", "listCard", mapOf("card" to card(9, 25))).contains("list-tile-seen has-seen"))
        assertTrue(render("fragments/list-card", "listCard", mapOf("card" to card(0, 25))).contains(">0/25 seen<"))
        assertTrue(render("fragments/list-card", "listCard", mapOf("card" to card(null, 25))).contains(">25 titles<"))
        assertTrue(render("fragments/list-card", "listCard", mapOf("card" to card(0, 0))).contains(">No titles<"))
    }

    private fun entry(rank: Int, status: WatchStatus?, score: Double?, seen: Boolean) = ListProgressEntry(
        itemId = CustomListItem.Id(100L + rank),
        mediaType = MediaType.movie,
        mediaId = rank.toLong(),
        rank = rank,
        status = status,
        score = score,
        seen = seen,
    )
}
