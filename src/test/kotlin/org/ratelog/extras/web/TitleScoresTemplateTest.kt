package org.ratelog.extras.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.extras.ExternalScores
import org.ratelog.extras.gettitleinfo.TitleInfo
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.context.support.StaticApplicationContext
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.util.Locale

/**
 * The score badges on title pages (fragments/title-info :: titleScores) with the real messages. A Rotten Tomatoes
 * search link sits next to IMDb's whenever the title is known (user request 2026-11-23); with MDBList's RT scores the
 * score badge is that link, so there is never a second RT badge.
 */
class TitleScoresTemplateTest {

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

    private fun info(
        type: MediaType = MediaType.movie,
        title: String? = "Crouching Tiger, Hidden Dragon",
        scores: ExternalScores? = null,
        imdbId: String? = "tt0190332",
        tmdbVoteAverage: Double? = 7.43,
    ) = TitleInfoView(
        TitleInfo(
            type = type, tmdbId = 146, title = title, tmdbVoteAverage = tmdbVoteAverage, tmdbVoteCount = 3_456,
            runtimeMinutes = 120, numberOfSeasons = null, trailerYoutubeKey = null, imdbId = imdbId,
            scores = scores, scoresFetchedAt = null,
        )
    )

    private fun scores(view: TitleInfoView): String {
        val context = Context(Locale.ENGLISH).apply {
            setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME, ThymeleafEvaluationContext(appContext, null))
            setVariable("info", view)
        }
        return engine.process("fragments/title-info", setOf("titleScores"), context)
            .also { assertFalse(it.contains("??"), "every message key resolves: $it") }
    }

    private fun rtBadges(html: String) = Regex("""<a [^>]*class="score-badge rt[^"]*"[^>]*>""").findAll(html).map { it.value }.toList()

    private val searchUrl = "https://www.rottentomatoes.com/search?search=Crouching+Tiger%2C+Hidden+Dragon"

    @Test
    fun `without MDBList scores there is a Rotten Tomatoes search link, styled like IMDb's`() {
        val html = scores(info(scores = null))

        val badge = rtBadges(html).single()
        assertTrue(badge.contains("""href="$searchUrl""""), badge)
        assertTrue(badge.contains("""target="_blank""""))
        assertTrue(badge.contains("""rel="noopener noreferrer""""))
        assertTrue(badge.contains("""title="Search Rotten Tomatoes® for this title (opens in a new tab)""""))
        assertTrue(html.contains(""">Rotten Tomatoes®</span>"""))
        assertEquals(2, Regex("""data-lucide="external-link" class="score-link-icon"""").findAll(html).count(), "IMDb and RT, same link style")
        assertTrue(html.indexOf("score-badge imdb") < html.indexOf("score-badge rt"), "right after IMDb")
    }

    @Test
    fun `with MDBList RT scores the score badge is the link, and there is no second RT badge`() {
        val html = scores(info(scores = ExternalScores(imdbRating = 7.9, imdbVotes = 290_123, rtCritics = 97, rtAudience = 86)))

        val badge = rtBadges(html).single()
        assertTrue(badge.contains("""href="$searchUrl""""))
        assertFalse(badge.contains("rt-search"))
        assertTrue(html.contains(">97%<"))
        assertTrue(html.contains(">86%<"))
        assertEquals(1, Regex(""">Rotten Tomatoes®<""").findAll(html).count())
    }

    @Test
    fun `MDBList scores without RT still get the search link`() {
        val html = scores(info(scores = ExternalScores(imdbRating = 7.9, imdbVotes = 290_123, metacritic = 45)))

        assertTrue(rtBadges(html).single().contains("rt-search"))
    }

    @Test
    fun `TV shows get it too, and there is no RT link without a title`() {
        assertTrue(rtBadges(scores(info(type = MediaType.tvshow, title = "Severance"))).single()
            .contains("""href="https://www.rottentomatoes.com/search?search=Severance""""))

        assertTrue(rtBadges(scores(info(title = null))).isEmpty())
    }

    @Test
    fun `the RT link alone is enough to show the badge row`() {
        val html = scores(info(imdbId = null, tmdbVoteAverage = null))

        assertTrue(html.contains("""class="title-info""""), html)
        assertEquals(1, rtBadges(html).size)
        assertFalse(html.contains("score-badge imdb"))
    }
}
