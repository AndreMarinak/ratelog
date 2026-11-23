package org.ratelog.extras.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.extras.gettitleinfo.GetTitleInfo
import org.ratelog.extras.gettitleinfo.GetTitleInfoHandler
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Adds `titleInfo` (a [TitleInfoView], or nothing) to the movie and TV detail pages after their controllers ran,
 * so the detail slices stay untouched. The templates render it with `fragments/title-info :: titleInfo`.
 */
class TitleInfoModelInterceptor(
    private val handler: GetTitleInfoHandler,
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(TitleInfoModelInterceptor::class.java)

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        if (modelAndView == null || !request.method.equals("GET", ignoreCase = true)) return
        val viewName = modelAndView.viewName ?: return
        val (type, detailAttribute) = when (viewName) {
            "movie-detail" -> MediaType.movie to "movie"
            "tvshow-detail" -> MediaType.tvshow to "show"
            else -> return
        }
        // The detail page failed to load (e.g. TMDB not configured): nothing to add.
        if (modelAndView.model[detailAttribute] == null) return
        val tmdbId = pathId(request)?.takeIf { it > 0 } ?: return

        try {
            this.handler.handle(GetTitleInfo(type, TmdbId(tmdbId)))
                .onRight { modelAndView.addObject("titleInfo", TitleInfoView(it)) }
        } catch (e: Exception) {
            logger.warn("Title extras unavailable for {} {}: {}", type, tmdbId, e.javaClass.simpleName)
        }
    }

    private fun pathId(request: HttpServletRequest): Int? =
        (request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *>)
            ?.get("id")?.toString()?.toIntOrNull()
}

@Configuration
class TitleInfoWebConfig(
    private val handler: GetTitleInfoHandler,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(TitleInfoModelInterceptor(handler)).addPathPatterns("/movie/*", "/tv/*")
    }
}
