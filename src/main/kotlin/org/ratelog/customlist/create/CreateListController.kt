package org.ratelog.customlist.create

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.order.numbered.ListNumberedForm
import org.ratelog.customlist.order.numbered.NumberedField
import org.ratelog.user.User
import jakarta.servlet.http.HttpServletRequest
import org.ratelog.watchlist.seen.BackPath
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class CreateListController(
    private val handler: CreateListHandler,
    private val numberedForm: ListNumberedForm, // fork: "Numbered list" checkbox
) {
    @PostMapping("/list")
    fun createList(
        @CurrentUser user: User,
        @RequestParam name: String,
        @RequestParam(name = "isPublic", required = false, defaultValue = "false") isPublic: Boolean,
        @RequestParam(name = NumberedField.PARAM, required = false) numbered: String?,
        @RequestParam(name = NumberedField.MARKER, required = false) numberedMarker: String?,
        redirectAttributes: RedirectAttributes,
        request: HttpServletRequest,
    ): String {
        val command = CreateListCommand(user.id!!, name, isPublic)
        return handler.handle(command).fold(
            { error ->
                val message = when (error) {
                    is CreateListError.InvalidListName -> "list.error.invalid.name"
                    is CreateListError.ListLimitExceeded -> "list.error.limit.exceeded"
                }
                redirectAttributes.addFlashAttribute("error", message)
                "redirect:" + BackPath.resolve(null, request.getHeader("Referer"), "/lists")
            },
            { list ->
                numberedForm.save(list.id!!, user.id, numbered, numberedMarker)
                "redirect:/list/${list.id.value}"
            }
        )
    }
}
