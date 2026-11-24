package org.ratelog.recommendations.foryou.web

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.recommendations.foryou.GetForYou
import org.ratelog.recommendations.foryou.GetForYouHandler
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/**
 * `/for-you/row`: the "Recommended for you" row as an HTML fragment (Discover's `lazyRow`), fetched by the page once
 * its placeholder nears the viewport, so Home and Discover never wait on these TMDB calls. `type=movie|tv` keeps to
 * one type (Discover); without it the row mixes both (Home). Never fails: no ratings, a TMDB error or a bug answer an
 * empty fragment, and the page drops the placeholder.
 */
@Controller
class ForYouRowController(
    private val handler: GetForYouHandler,
    private val views: ForYouViews,
) {
    private val logger = LoggerFactory.getLogger(ForYouRowController::class.java)

    @GetMapping(ForYouViews.ROW_PATH)
    fun row(@CurrentUser user: User, @RequestParam("type", required = false) type: String?, model: Model): String {
        val mediaType = parseType(type)
        val row = try {
            handler.handle(GetForYou(user.id!!, user.metadataLang, mediaType))
                .getOrNull()
                ?.picks
                ?.takeIf { it.isNotEmpty() }
                ?.let { views.row(mediaType, it) }
        } catch (e: Exception) {
            // Never log e.message: TMDB errors can carry the request URL (and its api_key).
            logger.warn("Recommended for you failed: {}", e.javaClass.simpleName)
            null
        }
        model.addAttribute("row", row)
        return FRAGMENT
    }

    companion object {
        const val FRAGMENT = "fragments/discover-rows :: lazyRow"

        /** `movie` or `tv` (also `tvshow`); anything else means both. */
        fun parseType(value: String?): MediaType? = when (value?.trim()?.lowercase()) {
            "movie", "movies" -> MediaType.movie
            "tv", "tvshow", "show", "shows" -> MediaType.tvshow
            else -> null
        }
    }
}
