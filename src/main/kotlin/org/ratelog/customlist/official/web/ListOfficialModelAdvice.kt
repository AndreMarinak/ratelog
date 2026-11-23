package org.ratelog.customlist.official.web

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.getlistdetail.GetListDetailController
import org.ratelog.customlist.official.OfficialListRepository
import org.ratelog.user.User
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable

/** What `fragments/official-lists :: listHeader` renders under the list title. */
data class ListOfficialView(
    val official: Boolean,
    /** Admins (ADMIN / SUPERADMIN) see the toggle on any list they can open; nobody else does. */
    val canToggle: Boolean,
    /** POST target of the toggle (SetListOfficialController). */
    val toggleHref: String,
    /** The shown list's visibility, set by [OfficialListVisibilityInterceptor] once the list has loaded. */
    val listPublic: Boolean = true,
) {
    /** The value the toggle sends: the opposite of the current state. */
    val nextValue: Boolean get() = !official

    /** Official but private: in no Official section, and only its owner can open it ("Official list · Hidden"). */
    val hidden: Boolean get() = official && !listPublic
}

/*
 * Adds `listOfficial` to /list/{id} without touching the upstream list controller and handler (same approach as
 * ListProgressModelAdvice). The template only renders it when the list itself is shown, so a private list's
 * state never reaches someone who can't open it.
 */
@ControllerAdvice(assignableTypes = [GetListDetailController::class])
class ListOfficialModelAdvice(
    private val officialLists: OfficialListRepository,
) {
    @ModelAttribute
    fun addListOfficial(
        @CurrentUser user: User?,
        @PathVariable("id") listId: Long,
        model: Model,
    ) {
        val id = CustomList.Id(listId)
        model.addAttribute(
            "listOfficial",
            ListOfficialView(
                official = officialLists.isOfficial(id),
                canToggle = user?.role?.isAdminLike == true,
                toggleHref = "/list/$listId/official",
            ),
        )
    }
}
