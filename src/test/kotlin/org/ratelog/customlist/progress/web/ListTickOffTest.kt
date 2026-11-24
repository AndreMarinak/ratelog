package org.ratelog.customlist.progress.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.customlist.progress.ListProgressFixture
import org.ratelog.test.InMemoryTitleReleaseRepository
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.TvShowFactory
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.SeenStateReader
import org.ratelog.watchlist.seen.ToggleSeen
import org.ratelog.watchlist.seen.ToggleSeenHandler
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.springframework.ui.ExtendedModelMap
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.LocalDate
import java.util.Locale

/** List tick-off mode (`?mark=1`): the links, the per-poster Seen toggles, the counts after toggling, the rendered bar. */
class ListTickOffTest {

    private val f = ListProgressFixture()
    private val users = InMemoryUserRepository()
    private val alice = users.save(UserFactory.aUser(username = "alice"))
    private val releases = InMemoryTitleReleaseRepository(f.movies, f.shows)
    private val clock = MutableClock() // today: 2026-11-22
    private val advice = ListProgressModelAdvice(f.handler, users, releases, clock)
    private val toggle = ToggleSeenHandler(f.statuses, f.movies, f.shows, SeenStateReader(f.statuses, f.ratings, f.tvRatings), clock)

    private val messages = ResourceBundleMessageSource().apply {
        setBasenames("messages")
        setDefaultEncoding("UTF-8")
        setFallbackToSystemLocale(false)
    }
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

    private val list = f.list(alice.id!!)
    private val coco = f.movies.save(MovieFactory.aMovie(tmdbId = 354912, originalTitle = "Coco", releaseDate = LocalDate.parse("2017-10-27")))
    private val doomsday = f.movies.save(MovieFactory.aMovie(tmdbId = 1003596, originalTitle = "Avengers: Doomsday", releaseDate = LocalDate.parse("2026-12-18")))
    private val toyStory = f.movies.save(MovieFactory.aMovie(tmdbId = 862, originalTitle = "Toy Story", releaseDate = LocalDate.parse("1995-11-22")))
    private val bear = f.shows.save(TvShowFactory.aTvShow(tmdbId = 136315, originalName = "The Bear", firstAirDate = LocalDate.parse("2022-06-23")))
    private val items = f.add(list, coco, doomsday, toyStory, bear)

    private fun view(show: String? = null, mark: String? = null): ListProgressView {
        val model = ExtendedModelMap()
        advice.addListProgress(alice, list.id!!.value, show, mark, model)
        return model["listProgress"] as ListProgressView
    }

    private fun render(vars: Map<String, Any?>): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(StaticApplicationContext().apply { refresh() }, null))
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process("fragments/list-progress", setOf("summary"), context)
    }

    @Test
    fun `mark=1 turns tick-off on, and every tab keeps it`() {
        val v = view(show = "unseen", mark = "1")

        assertTrue(v.tick)
        assertEquals(listOf("/list/${list.id!!.value}?mark=1", "/list/${list.id!!.value}?show=unseen&mark=1", "/list/${list.id!!.value}?show=seen&mark=1"), v.tabs.map { it.href })
        assertEquals("/list/${list.id!!.value}?show=unseen", v.doneHref, "Done keeps the tab, drops the mode")
        assertEquals("/list/${list.id!!.value}?show=unseen&mark=1", v.selfHref)
    }

    @Test
    fun `without mark the tabs are plain and the start link adds it`() {
        val v = view(show = "seen")

        assertFalse(v.tick)
        assertEquals("/list/${list.id!!.value}?show=seen&mark=1", v.tickHref)
        assertFalse(v.tabs.any { it.href.contains("mark") })
        assertFalse(view(mark = "nope").tick, "unknown values mean off")
        assertTrue(view(mark = "true").tick)
    }

    @Test
    fun `each poster gets its Seen toggle, upcoming titles don't, and rated ones are locked`() {
        f.rate(alice.id!!, toyStory, 9.0)
        val v = view(mark = "1")

        val cocoActions = v.entry(items[0].id!!.value)!!.actions!!
        assertEquals(354912, cocoActions.tmdbId)
        assertTrue(cocoActions.canToggleSeen)
        assertFalse(cocoActions.seen)

        val doom = v.entry(items[1].id!!.value)!!.actions!!
        assertFalse(doom.canToggleSeen)
        assertEquals("Dec 18", doom.upcomingDate)

        val toy = v.entry(items[2].id!!.value)!!.actions!!
        assertTrue(toy.seen && toy.seenLocked)

        assertEquals("tv", v.entry(items[3].id!!.value)!!.actions!!.type)
        assertEquals(2, releases.lookups.get(), "one release lookup per media type")
    }

    @Test
    fun `ticking a title moves the counts, like the in-place update does`() {
        assertEquals(0, view().seen)

        toggle.handle(ToggleSeen(alice.id!!, MediaType.movie, coco.id!!.value))
        val after = view(mark = "1")
        assertEquals(1, after.seen)
        assertTrue(after.entry(items[0].id!!.value)!!.actions!!.seen)
        assertEquals(listOf(4, 3, 1), after.tabs.map { it.count })

        toggle.handle(ToggleSeen(alice.id!!, MediaType.movie, coco.id!!.value))
        assertEquals(0, view().seen)
    }

    @Test
    fun `a show with one rated season but status Watching is not seen in list progress`() {
        f.rate(alice.id!!, bear, 8.0)
        f.status(alice.id!!, bear, WatchStatus.WATCHING)
        assertEquals(0, view().seen)
        assertEquals(0, f.handler.seenCounts(alice.id!!, listOf(list.id!!)).getValue(list.id!!), "the /lists card count agrees")

        f.status(alice.id!!, bear, WatchStatus.WATCHED)
        assertEquals(1, view().seen)
    }

    @Test
    fun `the summary renders the start link, or the tick bar with the live count, and the data for in-place updates`() {
        val csrf = DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "tok")

        val normal = render(mapOf("progress" to view(), "listName" to "Disney", "_csrf" to csrf))
        assertTrue(normal.contains("""class="btn secondary list-tick-start" href="/list/${list.id!!.value}?mark=1""""), normal)
        assertTrue(normal.contains("Mark what you"))
        assertFalse(normal.contains("list-tick-bar"))
        assertTrue(normal.contains("""data-seen="0""""))
        assertTrue(normal.contains("""data-total="4""""))
        assertTrue(normal.contains("{seen}") && normal.contains("{total}"), "the text template for list-tick.js")
        assertTrue(normal.contains("""data-tab-count="list.progress.filter.unseen""""))

        val ticking = render(mapOf("progress" to view(mark = "1"), "listName" to "Disney", "_csrf" to csrf))
        assertFalse(ticking.contains("list-tick-start"))
        assertTrue(ticking.contains("list-tick-bar"))
        assertTrue(ticking.contains("Tap the posters you"))
        assertTrue(ticking.contains("""class="btn primary list-tick-done" href="/list/${list.id!!.value}""""))
        // The progress line sits on the header's meta line now (fragments/list-progress :: line); the tick bar's count is here.
        assertEquals(1, Regex("data-progress-text").findAll(ticking).count(), "the tick bar's count updates")
        assertFalse(ticking.contains("??"))
    }

    @Test
    fun `badges show the Upcoming badge and leave the status to the quick actions`() {
        f.status(alice.id!!, coco, WatchStatus.WANT_TO_WATCH)
        val v = view()
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(StaticApplicationContext().apply { refresh() }, null))
        }
        context.setVariable("entry", v.entry(items[1].id!!.value))
        val doom = engine.process("fragments/list-progress", setOf("badges"), context)
        assertTrue(doom.contains("tile-upcoming"))
        assertTrue(doom.contains("Dec 18"))

        context.setVariable("entry", v.entry(items[0].id!!.value))
        val cocoBadges = engine.process("fragments/list-progress", setOf("badges"), context)
        assertFalse(cocoBadges.contains("list-status"), "Want to watch shows as the bookmark instead")
        assertNotNull(v.entry(items[0].id!!.value)!!.actions)
        assertNull(v.entry(999))
    }
}
