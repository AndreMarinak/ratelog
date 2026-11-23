package org.ratelog.customlist.order.web

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.MediaType
import org.ratelog.customlist.getlistdetail.ListDetail
import org.ratelog.customlist.order.ListOptionsRepository
import org.ratelog.customlist.order.sort.ListSort
import org.ratelog.customlist.progress.ListProgressRepository
import org.ratelog.customlist.progress.web.ListProgressView
import org.ratelog.user.User
import org.ratelog.user.UserDetailsService
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/*
 * List order on /list/{id} (fork, roadmap item 16), without editing upstream's list controller and handler:
 * - `listOrder` (ListOrderView): numbered or plain, the Sort menu, "Edit order" and reorder mode (`?reorder=1`, owner only);
 * - `list.items` put in the order the page shows: list order (position, then id, the order ranks are counted in),
 *   or the viewer's `?sort=`. Reorder mode always shows list order;
 * - the tabs and tick-off links of `listProgress` keep `sort=`, so switching tabs doesn't drop the viewer's sort.
 * Runs after the controller and the model advices (it needs `list`, `isOwner` and `listProgress`).
 */
class ListOrderInterceptor(
    private val listOptions: ListOptionsRepository,
    private val listProgressRepository: ListProgressRepository,
    private val currentUser: () -> User?,
) : HandlerInterceptor {

    override fun postHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any, modelAndView: ModelAndView?) {
        if (modelAndView == null || modelAndView.viewName != "list-detail") return
        val model = modelAndView.model
        val list = model["list"] as? ListDetail ?: return
        val progress = model["listProgress"] as? ListProgressView

        val view = ListOrderView.of(
            listId = list.id.value,
            numbered = listOptions.isNumbered(list.id),
            isOwner = model["isOwner"] == true,
            reorderRequested = request.getParameter("reorder")?.trim()?.lowercase() in ON_VALUES,
            sort = ListSort.parse(request.getParameter("sort")),
            total = list.items.size,
            show = progress?.filterParam,
            tick = progress?.tick == true,
        )
        val scores = if (view.sort == ListSort.RATING) yourScores(list) else emptyMap()
        modelAndView.addObject("list", list.copy(items = view.sort.arrange(list.items, scores)))
        modelAndView.addObject("listOrder", view)
        if (progress != null && view.sorted) modelAndView.addObject("listProgress", progress.keepingSort(view.sort))
    }

    /** The viewer's score per item id: one marks query per media type, only for "Your rating". */
    private fun yourScores(list: ListDetail): Map<Long, Double> {
        val viewerId = currentUser()?.id ?: return emptyMap()
        val items = list.items.mapNotNull { item -> MediaType.entries.find { it.name == item.mediaType }?.let { it to item } }
        return items.groupBy({ it.first }, { it.second }).flatMap { (type, typed) ->
            val marks = listProgressRepository.findMarks(viewerId, type, typed.map { it.mediaId })
            typed.mapNotNull { item -> marks[item.mediaId]?.score?.let { item.id.value to it } }
        }.toMap()
    }

    private fun ListProgressView.keepingSort(sort: ListSort): ListProgressView = copy(
        tabs = tabs.map { it.copy(href = ListOrderView.withSort(it.href, sort)) },
        tickHref = ListOrderView.withSort(tickHref, sort),
        doneHref = ListOrderView.withSort(doneHref, sort),
        selfHref = ListOrderView.withSort(selfHref, sort),
    )

    companion object {
        private val ON_VALUES = setOf("1", "true", "on", "yes")
    }
}

@Configuration
class ListOrderWebConfig(
    private val listOptions: ListOptionsRepository,
    private val listProgressRepository: ListProgressRepository,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(ListOrderInterceptor(listOptions, listProgressRepository) { UserDetailsService.getCurrentUser() })
            .addPathPatterns("/list/*")
    }
}
