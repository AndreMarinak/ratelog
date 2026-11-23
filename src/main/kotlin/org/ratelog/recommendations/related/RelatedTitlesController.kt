package org.ratelog.recommendations.related

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.recommendations.web.RelatedRowViews
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

/**
 * The rows under a title page, as an HTML fragment that `static/title-related.js` swaps in for the page's skeleton
 * once it nears the viewport, so the title page itself never waits on these TMDB calls.
 * `/movie/{id}/related`: the series (TMDB collection) and "More like this"; `/tv/{id}/related`: "More like this".
 * Never fails: TMDB errors, unknown ids and bugs answer an empty fragment, and the page drops the skeleton.
 */
@Controller
class RelatedTitlesController(
    private val handler: GetRelatedTitlesHandler,
    private val views: RelatedRowViews,
) {
    private val logger = LoggerFactory.getLogger(RelatedTitlesController::class.java)

    @GetMapping("/movie/{id}/related")
    fun movie(@CurrentUser user: User, @PathVariable("id") tmdbId: Int, model: Model): String =
        related(user, MediaType.movie, tmdbId, model)

    @GetMapping("/tv/{id}/related")
    fun tv(@CurrentUser user: User, @PathVariable("id") tmdbId: Int, model: Model): String =
        related(user, MediaType.tvshow, tmdbId, model)

    private fun related(user: User, type: MediaType, tmdbId: Int, model: Model): String {
        val section = if (tmdbId <= 0) null else try {
            handler.handle(GetRelatedTitles(user.id!!, type, tmdbId, user.metadataLang))
                .getOrNull()
                ?.let { views.section(it) }
                ?.takeIf { it.rows.isNotEmpty() }
        } catch (e: Exception) {
            // Never log e.message: TMDB errors can carry the request URL (and its api_key).
            logger.warn("Related titles failed for {} {}: {}", type, tmdbId, e.javaClass.simpleName)
            null
        }
        model.addAttribute("related", section)
        return FRAGMENT
    }

    companion object {
        const val FRAGMENT = "fragments/title-related :: rows"
    }
}
