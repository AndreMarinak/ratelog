package org.ratelog.customlist.official.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.customlist.progress.web.ListBackLink
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * On an official list the back link reads "← Lists" for everyone, not "← {owner}'s profile": official lists are
 * presented as the site's (no owner on their cards), and people open them from the Lists tab or Discover.
 * Runs after the controller and every model advice (ListProgressModelAdvice sets `listBack`), so the order is fixed
 * without editing that advice.
 */
class OfficialListBackLinkInterceptor : HandlerInterceptor {
    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        if (modelAndView == null || modelAndView.viewName != "list-detail") return
        val official = modelAndView.model["listOfficial"] as? ListOfficialView ?: return
        if (official.official && modelAndView.model["listBack"] != null) {
            modelAndView.addObject("listBack", ListBackLink.LISTS)
        }
    }
}

@Configuration
class OfficialListsWebConfig : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(OfficialListBackLinkInterceptor()).addPathPatterns("/list/*")
    }
}
