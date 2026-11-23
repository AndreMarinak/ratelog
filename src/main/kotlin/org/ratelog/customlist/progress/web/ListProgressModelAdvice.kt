package org.ratelog.customlist.progress.web

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.getlistdetail.GetListDetailController
import org.ratelog.customlist.progress.GetListProgress
import org.ratelog.customlist.progress.GetListProgressHandler
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

/*
 * Adds the viewer's progress (`listProgress`) and the back link (`listBack`) to /list/{id} without touching the
 * upstream list detail controller and handler. Rendered by templates/fragments/list-progress.html.
 * `?show=unseen` / `?show=seen` filter the grid; ranks stay those of the whole list.
 */
@ControllerAdvice(assignableTypes = [GetListDetailController::class])
class ListProgressModelAdvice(
    private val handler: GetListProgressHandler,
    private val userRepository: UserRepository,
) {
    @ModelAttribute
    fun addListProgress(
        @CurrentUser user: User?,
        @PathVariable("id") listId: Long,
        @RequestParam("show", required = false) show: String?,
        model: Model,
    ) {
        val viewerId = user?.id
        val progress = viewerId?.let { handler.handle(GetListProgress(CustomList.Id(listId), it)).getOrNull() }

        model.addAttribute("listProgress", progress?.toView(ListFilter.parse(show)))
        model.addAttribute("listBack", backLink(progress?.ownerId, viewerId))
    }

    private fun backLink(ownerId: User.Id?, viewerId: User.Id?): ListBackLink {
        if (ownerId == null || ownerId == viewerId) return ListBackLink.LISTS
        val owner = userRepository.findById(ownerId) ?: return ListBackLink.LISTS
        return ListBackLink.profile(ownerId.value, owner.username.value)
    }
}
