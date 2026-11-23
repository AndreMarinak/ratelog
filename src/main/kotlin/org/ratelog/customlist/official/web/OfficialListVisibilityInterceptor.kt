package org.ratelog.customlist.official.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.customlist.getlistdetail.ListDetail
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

/**
 * Tells the list header whether the shown list is public, so a private official list reads "Official list · Hidden"
 * and the admin toggle's confirmation says whether everyone will see it. Runs after the controller (ListOfficialModelAdvice
 * runs before it and can't see the list), so it costs no extra query and the upstream list slice stays untouched.
 * Registered in [OfficialListsWebConfig].
 */
class OfficialListVisibilityInterceptor : HandlerInterceptor {
    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        if (modelAndView == null || modelAndView.viewName != "list-detail") return
        val official = modelAndView.model["listOfficial"] as? ListOfficialView ?: return
        val list = modelAndView.model["list"] as? ListDetail ?: return
        if (official.listPublic != list.isPublic) {
            modelAndView.addObject("listOfficial", official.copy(listPublic = list.isPublic))
        }
    }
}
