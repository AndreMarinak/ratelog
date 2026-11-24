package org.ratelog.tile

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.customlist.membership.web.ListMembershipRow
import org.ratelog.customlist.membership.web.ListMembershipView
import org.ratelog.ratings.unrated.GetUnratedSeen
import org.ratelog.ratings.unrated.GetUnratedSeenHandler
import org.ratelog.ratings.unrated.UnratedSeenController
import org.ratelog.ratings.unrated.UnratedSeenTitle
import org.ratelog.ratings.unrated.FakeUnratedSeenRepository
import org.ratelog.Lang
import org.ratelog.test.UserFactory
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.getwatchlist.WatchlistController
import org.ratelog.watchlist.getwatchlist.WatchlistItemResponse
import org.ratelog.watchlist.seen.TileActionsView
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.springframework.security.web.csrf.DefaultCsrfToken
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.LocalDate
import java.util.Locale

/** Renders the real tile fragments with the real messages. */
class TileRateTemplatesTest {

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
    private val today = LocalDate.parse("2026-11-23")
    private val user = UserFactory.aUser(id = 1)

    init {
        LocaleContextHolder.setLocale(Locale.ENGLISH)
    }

    @AfterEach
    fun tearDown() = LocaleContextHolder.resetLocaleContext()

    private fun render(template: String, fragments: Set<String>, vars: Map<String, Any?>): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("_csrf", csrf)
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, fragments, context)
    }

    private fun tile(
        tmdbId: Int = 603,
        type: MediaType = MediaType.movie,
        score: Double? = null,
        status: WatchStatus? = null,
        date: LocalDate? = LocalDate.parse("1999-03-31"),
        detailed: Boolean = false,
    ) = TileActionsView.of(tmdbId, type, status, score != null, date, today, Locale.ENGLISH, score = score, detailed = detailed)

    private fun actions(a: TileActionsView, title: String = "The Matrix", marks: TileMarks? = null) =
        render("fragments/tile-actions", setOf("actions"), mapOf("a" to a, "title" to title, "tileMarks" to marks))

    /** The element with this class, from its opening tag to the end of its own tag. */
    private fun tag(html: String, cls: String): String {
        val start = html.indexOf("class=\"$cls")
        assertTrue(start >= 0, "no .$cls in $html")
        val open = html.lastIndexOf('<', start)
        return html.substring(open, html.indexOf('>', start) + 1)
    }

    @Test
    fun `an unrated movie gets Add to list and Rate links at the bottom, without JS they open the title page`() {
        val html = actions(tile())

        assertTrue(html.contains("class=\"tile-acts-bottom\""), html)
        val list = tag(html, "tile-act tile-list")
        assertTrue(list.contains("href=\"/movie/603?lists=1\""), list)
        assertTrue(list.contains("data-tile-lists"))
        assertTrue(list.contains("data-tmdb-id=\"603\"") && list.contains("data-type=\"movie\""))
        assertTrue(list.contains("aria-label=\"Add to a list: The Matrix\""), list)
        assertTrue(html.contains("data-lucide=\"list-plus\""))

        val star = tag(html, "tile-act tile-rate")
        assertTrue(star.contains("href=\"/movie/603?rate=1\""), star)
        assertTrue(star.contains("data-tile-rate=\"movie\""))
        assertTrue(star.contains("aria-haspopup=\"dialog\""))
        assertTrue(star.contains("aria-label=\"Rate: The Matrix\""), star)
        assertTrue(star.contains("data-title-href=\"/movie/603\""))
        assertFalse(star.contains("data-score"))
        assertFalse(star.contains("is-on"))
        assertFalse(html.contains("tile-rate-score"))
        assertFalse(html.contains("??"))
    }

    @Test
    fun `a rated movie shows its score on the poster, always`() {
        val html = actions(tile(score = 8.5, status = WatchStatus.WATCHED))

        val star = tag(html, "tile-act tile-rate")
        assertTrue(star.contains("tile-act tile-rate is-on"), star)
        assertTrue(star.contains("data-score=\"8.5\""))
        assertFalse(star.contains("data-detailed"))
        assertTrue(star.contains("title=\"Your rating: 8.5. Tap to change it\""), star)
        assertTrue(html.contains("<span class=\"tile-rate-score\">8.5</span>"), html)
    }

    @Test
    fun `tileMarks marks detailed ratings and titles already in a list`() {
        val marks = TileMarks(FakeTileMarksRepository(listed = setOf(TileKey(MediaType.movie, 603)), detailed = setOf(603))) { user }
        val html = actions(tile(score = 8.2, status = WatchStatus.WATCHED), marks = marks)

        val star = tag(html, "tile-act tile-rate")
        assertTrue(star.contains("is-on is-detailed"), star)
        assertTrue(star.contains("data-detailed=\"true\""))
        assertTrue(star.contains("title=\"Your rating: 8.2 (detailed)\""), star)
        val list = tag(html, "tile-act tile-list")
        assertTrue(list.contains("tile-list is-on"), list)
        assertTrue(list.contains("title=\"In your lists\""))
        assertTrue(html.contains("data-lucide=\"list-checks\""))
    }

    @Test
    fun `shows open the picker for a whole-show score, a show rated by season links to its seasons`() {
        // Fork (2026-11-23): like a movie, ★ opens the picker; without JS the show's page opens "Rate show".
        val unrated = actions(tile(tmdbId = 1396, type = MediaType.tvshow), title = "Breaking Bad")
        val star = tag(unrated, "tile-act tile-rate")
        assertTrue(star.contains("href=\"/tv/1396?rate=1\""), star)
        assertTrue(star.contains("data-tile-rate=\"tv\""), star)
        assertTrue(star.contains("aria-haspopup=\"dialog\""))
        assertTrue(star.contains("aria-label=\"Rate: Breaking Bad\""), star)
        assertFalse(star.contains("data-seasons"))
        assertTrue(tag(unrated, "tile-act tile-list").contains("href=\"/tv/1396?lists=1\""))

        val whole = actions(tile(tmdbId = 1396, type = MediaType.tvshow, score = 8.0, status = WatchStatus.WATCHED), title = "Breaking Bad")
        val wholeStar = tag(whole, "tile-act tile-rate")
        assertTrue(wholeStar.contains("Your rating: 8.0. Tap to change it"), wholeStar)
        assertFalse(wholeStar.contains("is-detailed"), wholeStar)
        assertTrue(whole.contains(">8.0</span>"))

        val marks = TileMarks(FakeTileMarksRepository(seasonRated = setOf(1396))) { user }
        val bySeason = actions(tile(tmdbId = 1396, type = MediaType.tvshow, score = 7.5, status = WatchStatus.WATCHING), title = "Breaking Bad", marks = marks)
        val seasonStar = tag(bySeason, "tile-act tile-rate")
        assertTrue(seasonStar.contains("href=\"/tv/1396#seasons\""), seasonStar)
        assertTrue(seasonStar.contains("data-seasons=\"true\""), seasonStar)
        assertTrue(seasonStar.contains("data-detailed=\"true\""), "the picker shows it, never overwrites it")
        assertTrue(seasonStar.contains("Your score: 7.5, the average of your rated seasons. Rate seasons"), seasonStar)
    }

    @Test
    fun `upcoming titles get no star, and their list button takes the eye's slot`() {
        val html = actions(tile(date = LocalDate.parse("2026-12-18"), status = WatchStatus.WANT_TO_WATCH), title = "Avengers: Doomsday")

        assertFalse(html.contains("tile-rate"), html)
        assertFalse(html.contains("tile-acts-bottom"))
        assertFalse(html.contains("tile-seen"))
        assertTrue(html.contains("tile-actions is-upcoming"))
        assertTrue(html.contains("class=\"tile-act tile-list\""))
    }

    @Test
    fun `every poster gets one chooser button for touch screens, a real button labelled with the title`() {
        listOf(tile(), tile(score = 8.5, status = WatchStatus.WATCHED), tile(date = LocalDate.parse("2026-12-18"))).forEach { a ->
            val html = actions(a)
            val button = tag(html, "tile-menu-btn")
            assertTrue(button.startsWith("<button type=\"button\""), button)
            assertTrue(button.contains("data-tile-menu"))
            assertTrue(button.contains("aria-haspopup=\"dialog\""))
            assertTrue(button.contains("aria-expanded=\"false\""))
            assertTrue(button.contains("aria-label=\"Watchlist, seen, rate or add to a list: The Matrix\""), button)
            assertTrue(html.contains("data-lucide=\"ellipsis\""))
            assertEquals(1, Regex("""data-tile-menu""").findAll(html).count())
        }
    }

    @Test
    fun `the Seen eye sits in the bottom corner with the score, between Add to list and the star`() {
        val html = actions(tile(score = 8.5, status = WatchStatus.WATCHED))
        val bottom = html.substringAfter("class=\"tile-acts-bottom\"")
        val list = bottom.indexOf("tile-act tile-list")
        val eye = bottom.indexOf("tile-act tile-seen is-on is-locked")
        val star = bottom.indexOf("tile-act tile-rate is-on")
        assertTrue(list in 0 until eye && eye < star, bottom)
        assertTrue(html.indexOf("tile-menu-btn") < html.indexOf("tile-act tile-watchlist"), "the chooser button comes first, top right")
    }

    @Test
    fun `the chooser template - four rows and every label the page script fills in`() {
        val html = render("fragments/tile-rate", setOf("assets"), emptyMap())

        assertTrue(html.contains("<template id=\"tileMenu\""), html)
        listOf("watchlist", "seen", "rate", "lists").forEach { act ->
            assertEquals(1, Regex("""data-menu-act="$act"""").findAll(html).count(), act)
        }
        assertEquals(4, Regex("""<button type="button" class="tile-menu-item"""").findAll(html).count())
        assertTrue(html.contains("role=\"dialog\" data-menu-pop hidden"))
        assertTrue(html.contains("data-label-aria=\"Actions for {0}\""), "filled in by tile-actions.js")
        assertTrue(html.contains("data-label-watch-add=\"Add to watchlist\""))
        assertTrue(html.contains("data-label-watch-on=\"On your watchlist\""))
        assertTrue(html.contains("data-label-seen-mark=\"Mark as seen\""))
        assertTrue(html.contains("data-label-seen-locked-hint=\"You rated it, so it stays seen\""))
        assertTrue(html.contains("data-label-rate-yours=\"Your rating\""))
        assertTrue(html.contains("data-label-rate-seasons=\"Rate seasons\""))
        assertTrue(html.contains("data-label-lists-in=\"In your lists\""))
        assertTrue(html.contains("aria-label=\"Close\""))
        assertFalse(html.contains("??"))
    }

    @Test
    fun `the picker template - ten scores, both forms with CSRF, labels for the page script`() {
        val html = render("fragments/tile-rate", setOf("assets"), emptyMap())

        assertTrue(html.contains("href=\"/tile-rate.css\""))
        assertTrue(html.contains("src=\"/tile-rate.js\""))
        assertTrue(html.contains("<template id=\"tileRatePicker\""))
        assertEquals(10, Regex("""class="tile-rate-score-btn"""").findAll(html).count())
        assertEquals(10, Regex("""name="score" value="\d+"""").findAll(html).count())
        assertTrue(html.contains("action=\"/movie/rate/tile\""))
        assertTrue(html.contains("action=\"/movie/rate/tile/delete\""))
        assertEquals(2, Regex("""name="_csrf" value="tok123"""").findAll(html).count())
        assertTrue(html.contains("data-label=\"{0} out of 10\""), "filled in by tile-rate.js")
        assertTrue(html.contains("data-label-on=\"Your rating: {0}. Tap to change it\""))
        assertTrue(html.contains("data-label-prompt=\"Marked seen. Rate it?\""))
        assertTrue(html.contains("Detailed rating…"))
        assertTrue(html.contains("Delete your rating?"))
        assertTrue(html.contains("Ask to rate after Seen"))
        assertTrue(html.contains("data-pop-skip"))
        assertFalse(html.contains("??"))
    }

    @Test
    fun `the tile's list sheet is the title page's sheet, keyed to the tile`() {
        val membership = ListMembershipView(
            mediaType = "movie", mediaId = 42, back = "/discover",
            rows = listOf(ListMembershipRow(5, "Sci-fi", inList = true, rank = 3, order = 0), ListMembershipRow(6, "Heist", false, null, 1)),
        )
        val html = render("fragments/tile-rate", setOf("listSheet"), mapOf(
            "listMembership" to membership, "sheetMediaId" to 42L, "sheetMediaType" to "movie", "sheetKey" to "movie:603", "sheetTitle" to "The Matrix",
        ))

        assertTrue(html.contains("data-tile-sheet"))
        assertTrue(html.contains("data-key=\"movie:603\""))
        assertTrue(html.contains("data-title=\"The Matrix\""))
        assertTrue(html.contains("id=\"addToListModal\""))
        assertTrue(html.contains("action=\"/list/5/membership\""))
        assertTrue(html.contains("name=\"back\" value=\"/discover\""))
        assertTrue(html.contains("name=\"mediaId\" value=\"42\""))
        assertFalse(html.contains("??"))
    }

    @Test
    fun `the Ratings switch links To rate with the count, hidden at zero`() {
        val some = render("fragments/ratings-switch", setOf("ratingsSwitch"), mapOf("current" to "movies", "unratedSeenCount" to 12))
        assertTrue(some.contains("href=\"/ratings/unrated\""))
        assertTrue(some.contains(">To rate<"))
        assertTrue(some.contains(">12<"))
        assertFalse(Regex("""data-ratings-unrated-count[^>]*hidden""").containsMatchIn(some), some)

        val none = render("fragments/ratings-switch", setOf("ratingsSwitch"), mapOf("current" to "unrated", "unratedSeenCount" to 0))
        assertTrue(Regex("""data-ratings-unrated-count[^>]*hidden""").containsMatchIn(none), none)
        assertTrue(none.contains("ratings-switch-link ratings-switch-unrated active"), none)
    }

    @Test
    fun `the To rate page shows tiles with the quick actions and per type tabs`() {
        val handler = GetUnratedSeenHandler(FakeUnratedSeenRepository(listOf(
            UnratedSeenTitle(603, MediaType.movie, "The Matrix", 1999, "/m.jpg", LocalDate.parse("1999-03-31"), 2),
            UnratedSeenTitle(1396, MediaType.tvshow, "Breaking Bad", 2008, null, LocalDate.parse("2008-01-20"), 1),
        )))
        val view = UnratedSeenController.view(handler.handle(GetUnratedSeen(user.id!!, null, Lang.en)), null, today, Locale.ENGLISH)
        val html = render("ratings-unrated", setOf("view"), mapOf("unrated" to view))

        assertEquals(2, Regex("""class="home-tile-wrap search-tile-wrap"""").findAll(html).count(), html)
        assertTrue(html.contains("data-key=\"movie:603\""))
        assertTrue(html.contains("href=\"/movie/603?rate=1\""))
        assertTrue(html.contains("href=\"/tv/1396?rate=1\""))
        assertTrue(html.contains("data-unrated-tab=\"movie\""))
        assertTrue(Regex("""data-unrated-empty[^>]*hidden""").containsMatchIn(html), "the empty note waits hidden")
        assertFalse(html.contains("??"))
    }

    @Test
    fun `watchlist tiles - the same slots for movies and shows, Watching for shows only, a note where no button fits`() {
        fun item(title: String, isMovie: Boolean, status: WatchStatus, upcomingDate: String? = null, score: Double? = null) = WatchlistItemResponse(
            href = if (isMovie) "/movie/1" else "/tv/2", isMovie = isMovie, title = title, year = 2020, posterPath = null, score = score,
            mediaType = if (isMovie) "movie" else "tvshow", mediaId = 1, upcomingDate = upcomingDate,
            actions = WatchlistController.actionsFor(status, !isMovie, upcomingDate != null, score != null),
            slots = WatchlistController.slotsFor(status),
            noteKey = WatchlistController.noteFor(status, upcomingDate != null, score != null, hasDate = upcomingDate != null),
            tileActions = TileActionsView.of(if (isMovie) 1 else 2, if (isMovie) MediaType.movie else MediaType.tvshow, status, score != null,
                if (upcomingDate != null) LocalDate.parse("2026-12-18") else LocalDate.parse("2001-01-01"), today, Locale.ENGLISH, score = score),
        )
        fun grid(vararg items: WatchlistItemResponse) = render("watchlist", setOf("grid"), mapOf("items" to items.toList(), "watchlistSelf" to "/watchlist"))

        val toWatch = grid(item("Heat", true, WatchStatus.WANT_TO_WATCH), item("The Bear", false, WatchStatus.WANT_TO_WATCH))
        assertEquals(2, Regex("""class="watchlist-actions slots-3"""").findAll(toWatch).count(), toWatch)
        listOf("seen", "remove").forEach { to ->
            assertEquals(2, Regex("""name="to" value="$to"""").findAll(toWatch).count(), "$to on both the movie and the show")
        }
        assertEquals(1, Regex("""name="to" value="watching"""").findAll(toWatch).count(), "Watching is for shows")
        assertEquals(1, Regex("""style="grid-column: span 2"""").findAll(toWatch).count(), "the movie's Seen takes two slots")
        assertTrue(toWatch.contains("tile-act tile-rate"), "★ on the poster")
        assertTrue(toWatch.contains("tile-act tile-list"))

        val upcoming = grid(item("Avengers: Doomsday", true, WatchStatus.WANT_TO_WATCH, upcomingDate = "Dec 18"))
        assertTrue(upcoming.contains("grid-column: span 2"), upcoming)
        assertTrue(upcoming.contains(">Out Dec 18<"))
        assertFalse(upcoming.contains("tile-rate"))

        val seen = grid(item("Heat", true, WatchStatus.WATCHED, score = 9.0), item("Up", true, WatchStatus.WATCHED))
        assertEquals(2, Regex("""class="watchlist-actions slots-1"""").findAll(seen).count(), seen)
        assertTrue(seen.contains(">Rated<"))
        assertEquals(1, Regex("""name="to" value="remove"""").findAll(seen).count())
        assertFalse(seen.contains("??"))
    }
}
