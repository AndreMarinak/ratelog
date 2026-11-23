package org.ratelog.recommendations.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.browse.LibraryMark
import org.ratelog.browse.web.BrowseBarViewsTest
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.related.RelatedTile
import org.ratelog.recommendations.related.RelatedTitles
import org.ratelog.recommendations.related.SeriesRow
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

/** Renders fragments/title-related.html with the real messages. */
class RelatedTemplatesTest {

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
    private val views = RelatedRowViews(messages, Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC))

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
        return engine.process("fragments/title-related", setOf(fragment), context)
    }

    private fun tile(id: Int, name: String, date: String, mark: LibraryMark? = null, current: Boolean = false) =
        RelatedTile(RelatedTitle(id, MediaType.movie, name, LocalDate.parse(date), "/p$id.jpg", 7.0, 900), mark, current)

    private val watched = LibraryMark(score = null, rated = false, status = WatchStatus.WATCHED)

    private fun rows(currentSeen: Boolean = false) = views.section(
        RelatedTitles(
            SeriesRow(
                10194, "Toy Story Collection",
                listOf(
                    tile(862, "Toy Story", "1995-11-22", mark = watched),
                    tile(863, "Toy Story 2", "1999-10-30", mark = if (currentSeen) watched else null, current = true),
                    tile(10193, "Toy Story 3", "2010-06-16"),
                    tile(1300000, "Toy Story 6", "2027-06-18"),
                ),
            ),
            listOf(tile(585, "Monsters, Inc.", "2001-11-01")),
        )
    )

    @Test
    fun `the placeholder points at the rows endpoint, with a skeleton and its own styles and script`() {
        val movie = render("placeholder", mapOf("type" to "movie", "tmdbId" to 863))
        assertTrue(movie.contains("""data-src="/movie/863/related""""), movie)
        assertTrue(movie.contains("title-related is-pending"))
        assertTrue(movie.contains("""aria-busy="true""""))
        assertEquals(8, Regex("""class="discover-skeleton"""").findAll(movie).count())
        assertTrue(movie.contains("""href="/home.css""""))
        assertTrue(movie.contains("""href="/title-related.css""""))
        assertTrue(movie.contains("""src="/title-related.js""""))
        // title-related.js replaces the section: styles inside it would be dropped with it.
        assertTrue(movie.indexOf("""href="/title-related.css"""") < movie.indexOf("<section"), "styles sit outside the swapped section")

        val tv = render("placeholder", mapOf("type" to "tv", "tmdbId" to 1396))
        assertTrue(tv.contains("""data-src="/tv/1396/related""""), tv)

        assertFalse(render("placeholder", mapOf("type" to "movie", "tmdbId" to null)).contains("title-related"), "no title, no rows")
    }

    @Test
    fun `nothing to show renders nothing`() {
        assertFalse(render("rows", mapOf("related" to null)).contains("<section"))
    }

    @Test
    fun `the series row marks this title and shows progress, the other tiles get quick actions`() {
        val html = render("rows", mapOf("related" to rows()))

        assertTrue(html.contains(">Part of the Toy Story Collection<"), html)
        assertTrue(html.contains("""data-related-progress>You&#39;ve seen 1 of 3 · Next: Toy Story 2<"""), html)
        assertTrue(html.contains("""id="related-series""""))
        assertTrue(html.contains("""id="related-more""""))
        assertTrue(html.contains(">More like this<"))

        // This title: no link to itself, no forms, "You're here", its Seen mark hidden while unseen.
        val current = html.substringAfter("""home-tile-wrap is-current""").substringBefore("""data-key="movie:10193"""")
        assertTrue(current.contains("""aria-current="page""""), current)
        assertTrue(current.contains("You&#39;re here"), current)
        assertFalse(current.contains("<form"), current)
        assertFalse(current.contains("""href="/movie/863""""))
        assertTrue(Regex("""data-related-current-seen[^>]*\shidden""").containsMatchIn(current), current)

        // Other entries: a link and the bookmark + Seen forms with CSRF.
        assertTrue(html.contains("""href="/movie/10193""""))
        assertTrue(html.contains("""data-key="movie:10193""""))
        assertTrue(html.contains("""action="/seen/toggle""""))
        assertTrue(html.contains("""name="_csrf" value="tok123""""))
        assertTrue(html.contains("""srcset="https://image.tmdb.org/t/p/w342/p585.jpg 342w, https://image.tmdb.org/t/p/w500/p585.jpg 500w""""))

        // The unreleased entry: Upcoming badge, no Seen eye.
        val upcoming = html.substringAfter("""data-key="movie:1300000"""").substringBefore("</section>")
        assertTrue(upcoming.contains(">Upcoming<"), upcoming)
        assertFalse(upcoming.contains("tile-seen"), upcoming)
        assertFalse(html.contains("??"), "every message key resolves")
    }

    @Test
    fun `a seen current title shows its Seen mark`() {
        val html = render("rows", mapOf("related" to rows(currentSeen = true)))
        val current = html.substringAfter("""home-tile-wrap is-current""").substringBefore("""data-key="movie:10193"""")
        assertFalse(Regex("""data-related-current-seen[^>]*\shidden""").containsMatchIn(current), current)
        assertTrue(html.contains("You&#39;ve seen 2 of 3 · Next: Toy Story 3"))
    }
}
