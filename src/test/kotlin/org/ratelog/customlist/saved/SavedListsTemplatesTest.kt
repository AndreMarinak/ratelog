package org.ratelog.customlist.saved

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.customlist.browse.ListCardView
import org.ratelog.customlist.official.web.ListOfficialView
import org.ratelog.customlist.saved.intitle.OfficialListChip
import org.ratelog.customlist.saved.intitle.TitleOfficialListsView
import org.ratelog.customlist.saved.web.ListSavedView
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

/** Renders the real saved-lists fragments with the real messages. */
class SavedListsTemplatesTest {

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
            setVariable("_csrf", csrf)
            vars.forEach { (k, v) -> setVariable(k, v) }
        }
        return engine.process(template, setOf(fragment), context).also { assertFalse(it.contains("??"), "every message key resolves: $it") }
    }

    private fun card(saved: Boolean?, name: String = "IMDb Top 250") =
        ListCardView("/list/7", name, 250, true, null, listOf(null, null, null, null), 12, isOfficial = true, saved = saved, baseOrder = 4)

    // --- Cards ---

    @Test
    fun `an official card gets the Save toggle beside the link, never inside it`() {
        val html = render("fragments/saved-lists", "card", mapOf("card" to card(saved = false)))

        assertTrue(html.contains("""class="list-tile-wrap""""))
        assertTrue(html.contains("""data-list-key="/list/7/saved""""))
        assertTrue(html.contains("""data-base-order="4""""))
        assertTrue(html.contains("""action="/list/7/saved""""))
        assertTrue(html.contains("""name="saved" value="true""""), "it sends the opposite state")
        assertTrue(html.contains("""aria-pressed="false""""))
        assertTrue(html.contains(">Save<"))
        assertTrue(html.contains("""name="_csrf" value="tok123""""))
        assertTrue(html.indexOf("</a>") < html.indexOf("<form"), "the form is a sibling of the card link")
        assertTrue(html.contains("Save &quot;IMDb Top 250&quot;"))
    }

    @Test
    fun `a saved card reads Saved and sends false`() {
        val html = render("fragments/saved-lists", "card", mapOf("card" to card(saved = true)))

        assertTrue(html.contains("list-tile-wrap is-saved"))
        assertTrue(html.contains("list-save is-card is-saved"))
        assertTrue(html.contains("""name="saved" value="false""""))
        assertTrue(html.contains("""aria-pressed="true""""))
        assertTrue(html.contains(">Saved<"))
    }

    @Test
    fun `cards without a toggle render the plain card`() {
        val html = render("fragments/saved-lists", "card", mapOf("card" to card(saved = null)))

        assertTrue(html.trim().startsWith("<a class=\"list-tile\""))
        assertFalse(html.contains("<form"))
    }

    // --- List page ---

    private fun official(official: Boolean = true, public: Boolean = true) = ListOfficialView(official, canToggle = false, toggleHref = "/list/7/official", listPublic = public)

    private fun listButton(saved: ListSavedView?, official: ListOfficialView?) =
        render("fragments/saved-lists", "listButton", mapOf("saved" to saved, "official" to official, "list" to mapOf("name" to "AFI 100")))

    @Test
    fun `the list page shows Save on official public lists, and Saved on one you saved`() {
        val save = listButton(ListSavedView(7, saved = false), official())
        val saved = listButton(ListSavedView(7, saved = true), official(official = false))

        assertTrue(save.contains("list-save is-page"))
        assertTrue(save.contains(">Save<"))
        assertTrue(saved.contains(">Saved<"), "a saved list that is no longer official can still be taken off")
    }

    @Test
    fun `no button on ordinary or hidden lists you never saved`() {
        assertFalse(listButton(ListSavedView(7, saved = false), official(official = false)).contains("<form"))
        assertFalse(listButton(ListSavedView(7, saved = false), official(public = false)).contains("<form"))
        assertFalse(listButton(null, official()).contains("<form"))
    }

    // --- Title page and sheet ---

    private fun view(vararg chips: OfficialListChip) = TitleOfficialListsView(chips.toList(), chips.filter { it.saved && !it.owned })

    @Test
    fun `title chips name the rank on numbered lists, highlight saved ones and fold the rest`() {
        val html = render(
            "fragments/saved-lists", "titleLine",
            mapOf(
                "view" to view(
                    OfficialListChip(1, "IMDb Top 250", 12, saved = true),
                    OfficialListChip(2, "AFI 100", null, saved = false),
                    OfficialListChip(3, "Sight and Sound", 4, saved = false),
                    OfficialListChip(4, "Hallmark", 30, saved = false),
                    OfficialListChip(5, "Criterion", 99, saved = false),
                ),
            ),
        )

        assertTrue(html.contains(">Official lists<"))
        assertTrue(html.contains("#12 in IMDb Top 250"))
        assertTrue(html.contains("title-official-chip is-saved"))
        assertTrue(html.contains(""">AFI 100<"""), "a plain list shows just its name")
        assertTrue(html.contains("+2 more"))
        assertTrue(html.indexOf("Sight and Sound") < html.indexOf("<details"), "three chips before more")
        assertTrue(html.indexOf("Hallmark") > html.indexOf("<details"))
        assertTrue(html.contains("""href="/list/1""""))
    }

    @Test
    fun `no line without official lists, no more with three`() {
        assertFalse(render("fragments/saved-lists", "titleLine", mapOf("view" to view())).contains("title-official-lists"))
        assertFalse(render("fragments/saved-lists", "titleLine", mapOf("view" to null)).contains("title-official-lists"))
        val three = render("fragments/saved-lists", "titleLine", mapOf("view" to view(OfficialListChip(1, "A", 1, false), OfficialListChip(2, "B", 1, false), OfficialListChip(3, "C", 1, false))))
        assertFalse(three.contains("<details"))
    }

    @Test
    fun `the sheet line lists saved official lists read-only`() {
        val html = render(
            "fragments/saved-lists", "sheetLine",
            mapOf("view" to view(OfficialListChip(1, "IMDb Top 250", 12, saved = true), OfficialListChip(2, "AFI 100", null, saved = true), OfficialListChip(3, "Other", 3, saved = false))),
        )

        assertTrue(html.contains("Also in your saved lists:"))
        assertTrue(html.contains(">IMDb Top 250 (#12)</a>, <a"))
        assertTrue(html.contains(">AFI 100</a>"))
        assertFalse(html.contains("Other"), "only saved lists")
        assertFalse(html.contains("<input") || html.contains("<button"), "no checkboxes")
    }

    @Test
    fun `no sheet line when none of your saved lists has the title`() {
        val html = render("fragments/saved-lists", "sheetLine", mapOf("view" to view(OfficialListChip(3, "Other", 3, saved = false))))
        assertEquals("", html.trim())
    }
}
