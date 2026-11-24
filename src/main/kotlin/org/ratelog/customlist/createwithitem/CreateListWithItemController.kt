package org.ratelog.customlist.createwithitem

import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.additem.AddToListCommand
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.customlist.create.CreateListCommand
import org.ratelog.customlist.create.CreateListError
import org.ratelog.customlist.create.CreateListHandler
import org.ratelog.customlist.order.numbered.ListNumberedForm
import org.ratelog.customlist.order.numbered.NumberedField
import org.ratelog.user.User
import jakarta.servlet.http.HttpServletRequest
import org.ratelog.watchlist.seen.BackPath
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * "New list" from the add-to-list modal on a title page: creates the list and adds the title in one step,
 * then opens the new list (same place adding to an existing list goes).
 */
@Controller
class CreateListWithItemController(
    private val createListHandler: CreateListHandler,
    private val addToListHandler: AddToListHandler,
    private val numberedForm: ListNumberedForm, // fork: quick lists start plain (no ranks)
) {
    @PostMapping("/list/new-with-item")
    @Transactional
    fun createWithItem(
        @CurrentUser user: User,
        @RequestParam name: String,
        @RequestParam(defaultValue = "false") isPublic: Boolean,
        @RequestParam mediaId: Long,
        @RequestParam mediaType: String,
        redirectAttributes: RedirectAttributes,
        request: HttpServletRequest,
    ): String {
        val list = createListHandler.handle(CreateListCommand(user.id!!, name, isPublic)).fold(
            { error ->
                redirectAttributes.addFlashAttribute(
                    "error",
                    when (error) {
                        CreateListError.InvalidListName -> "list.error.invalid.name"
                        CreateListError.ListLimitExceeded -> "list.error.limit.exceeded"
                    }
                )
                return "redirect:" + BackPath.resolve(null, request.getHeader("Referer"), "/lists")
            },
            { it }
        )
        addToListHandler.handle(AddToListCommand(list.id!!, user.id, mediaId, mediaType))
        // A list started from a title page is a plain collection (no ranks) until the owner ticks "Numbered list".
        numberedForm.save(list.id, user.id, numbered = null, marker = NumberedField.MARKER)
        redirectAttributes.addFlashAttribute("success", "list.add.new.created")
        return "redirect:/list/${list.id.value}"
    }
}
