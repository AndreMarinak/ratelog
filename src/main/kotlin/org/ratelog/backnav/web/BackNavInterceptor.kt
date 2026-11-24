package org.ratelog.backnav.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.backnav.BackNavLabel
import org.ratelog.backnav.BackNavLink
import org.ratelog.backnav.BackNavNames
import org.ratelog.backnav.BackNavPages
import org.ratelog.customlist.getlistdetail.ListDetail
import org.ratelog.movie.detail.MovieDetailResponse
import org.ratelog.person.detail.web.PersonView
import org.ratelog.tvshow.detail.TvShowDetailResponse
import org.ratelog.user.User
import org.ratelog.user.UserDetailsService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Adds the back-link model to every page after its controller ran, so no upstream controller changes:
 * - `backNavPage` ([BackNavLabel] or null): what this page is called when a title page links back to it. Rendered on
 *   the back-nav.js script tag (fragments/back-nav.html :: page, included from fragments/nav.html); null for title
 *   pages and pages nobody comes back to (settings, admin, errors).
 * - `backNav` ([BackNavLink], title pages only): the link rendered before JS, from the Referer (else Home).
 */
class BackNavInterceptor(
    private val names: BackNavNames,
    private val currentUser: () -> User?,
) : HandlerInterceptor {
    private val logger = LoggerFactory.getLogger(BackNavInterceptor::class.java)

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        if (modelAndView == null || !request.method.equals("GET", ignoreCase = true)) return
        val view = modelAndView.viewName ?: return
        if (view.startsWith("redirect:") || view.startsWith("forward:")) return
        val model = modelAndView.model
        val path = request.requestURI.removePrefix(request.contextPath).ifEmpty { "/" }

        when (view) {
            "movie-detail" -> (model["movie"] as? MovieDetailResponse)?.let { modelAndView.addObject("backNav", titleLink(request, it.name)) }
            "tvshow-detail" -> (model["show"] as? TvShowDetailResponse)?.let { modelAndView.addObject("backNav", titleLink(request, it.name)) }
            // Fork (2026-11-24): person pages get the same back link (they count as a title in the back-link chain).
            "person" -> (model["person"] as? PersonView)?.let { modelAndView.addObject("backNav", titleLink(request, it.name)) }
            else -> pageLabel(view, path, request.queryString, model)?.let { modelAndView.addObject("backNavPage", it) }
        }
    }

    private fun pageLabel(view: String, path: String, query: String?, model: Map<String, Any?>): BackNavLabel? = when (view) {
        "list-detail" -> (model["list"] as? ListDetail)?.name?.let(BackNavLabel::list)
        "profile" -> (model["username"] as? String)?.let { username ->
            if (model["isLoggedUser"] == true) BackNavLabel.OWN_PROFILE else BackNavLabel.profile(username)
        }
        "not-found", "tmdb-error", "login" -> null
        else -> BackNavPages.fixedLabel(path, query)
    }

    private fun titleLink(request: HttpServletRequest, titleName: String?): BackNavLink = try {
        BackNavPages.titleLink(request.getHeader("Referer"), request.serverName, currentUser()?.id, names, titleName)
    } catch (e: Exception) {
        // The page still renders; the link is the default and back-nav.js may still know better.
        logger.warn("Back link unavailable for {}: {}", request.requestURI, e.javaClass.simpleName)
        BackNavLink(BackNavPages.DEFAULT_HREF, BackNavPages.DEFAULT_LABEL, titleName)
    }
}

/** Registers [BackNavInterceptor] on every page (it only acts on rendered views). */
@Configuration
class BackNavWebConfig(
    private val names: BackNavNames,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(BackNavInterceptor(names) { UserDetailsService.getCurrentUser() })
            .addPathPatterns("/**")
            .excludePathPatterns("/api/**")
    }
}
