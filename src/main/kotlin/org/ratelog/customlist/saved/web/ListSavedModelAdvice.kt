package org.ratelog.customlist.saved.web

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.getlistdetail.GetListDetailController
import org.ratelog.customlist.saved.SavedListRepository
import org.ratelog.user.User
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable

/** What `fragments/saved-lists :: listButton` renders beside the list title. */
data class ListSavedView(
    val listId: Long,
    val saved: Boolean,
) {
    /** POST target of the toggle (SetListSavedController). */
    val href: String get() = "/list/$listId/saved"
}

/*
 * Adds `listSaved` to /list/{id} without touching the upstream list slice (same approach as ListOfficialModelAdvice).
 * The template shows the button only on an official, public list (`listOfficial`), or on one the viewer saved earlier
 * so they can still take it off; the page itself only renders for people who can open the list.
 */
@ControllerAdvice(assignableTypes = [GetListDetailController::class])
class ListSavedModelAdvice(
    private val savedLists: SavedListRepository,
) {
    @ModelAttribute
    fun addListSaved(
        @CurrentUser user: User?,
        @PathVariable("id") listId: Long,
        model: Model,
    ) {
        val userId = user?.id ?: return
        model.addAttribute("listSaved", ListSavedView(listId, savedLists.isSaved(userId, CustomList.Id(listId))))
    }
}
