package org.ratelog.customlist.saved.set

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.CustomList
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** The answer to a `fetch` toggle (static/saved-lists.js): the stored state and the texts the buttons show. */
data class ListSavedResponse(
    val ok: Boolean,
    val listId: Long,
    val saved: Boolean,
    /** The button's label: "Save" / "Saved". */
    val label: String,
    /** Its tooltip and accessible name. */
    val title: String,
    val message: String,
)

/**
 * POST /list/{id}/saved (`saved=true|false`, optional `back`): the Save toggle of an official list's page and cards.
 * A plain form post goes back to `back` (else the Referer, else the list) with a flash message; `fetch` with
 * `X-Requested-With: fetch` gets [ListSavedResponse], so the page stays put.
 */
@Controller
class SetListSavedController(
    private val handler: SetListSavedHandler,
    private val messages: MessageSource,
) {
    @PostMapping("/list/{id}/saved")
    fun set(
        @PathVariable id: Long,
        @CurrentUser user: User,
        @RequestParam saved: Boolean,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        handler.handle(SetListSaved(CustomList.Id(id), user.id!!, saved)).fold(
            { redirectAttributes.addFlashAttribute("error", errorKey(it)) },
            { redirectAttributes.addFlashAttribute("success", if (it.saved) SAVED_PLAIN else UNSAVED_PLAIN) },
        )
        return "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/list/$id")
    }

    @PostMapping("/list/{id}/saved", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun setJson(
        @PathVariable id: Long,
        @CurrentUser user: User,
        @RequestParam saved: Boolean,
    ): ResponseEntity<ListSavedResponse> =
        handler.handle(SetListSaved(CustomList.Id(id), user.id!!, saved)).fold(
            { error ->
                val status = if (error == SetListSavedError.ListNotFound) HttpStatus.NOT_FOUND else HttpStatus.BAD_REQUEST
                ResponseEntity.status(status).body(
                    ListSavedResponse(false, id, !saved, label(!saved), "", text(errorKey(error))),
                )
            },
            { result ->
                ResponseEntity.ok(
                    ListSavedResponse(
                        ok = true,
                        listId = id,
                        saved = result.saved,
                        label = label(result.saved),
                        title = text(if (result.saved) "list.saved.button.title.on" else "list.saved.button.title.off", result.listName),
                        message = text(if (result.saved) "list.saved.done" else "list.saved.undone", result.listName),
                    ),
                )
            },
        )

    private fun label(saved: Boolean) = text(if (saved) "list.saved.button.on" else "list.saved.button.off")

    private fun errorKey(error: SetListSavedError) = when (error) {
        SetListSavedError.ListNotFound -> "list.not.found"
        SetListSavedError.NotSaveable -> "list.saved.error.not.official"
    }

    private fun text(key: String, vararg args: Any): String =
        messages.getMessage(key, args.takeIf { it.isNotEmpty() }, key, LocaleContextHolder.getLocale()) ?: key

    companion object {
        /** Flash keys for plain form posts: pages show `#{${success}}`, which takes no arguments. */
        const val SAVED_PLAIN = "list.saved.done.plain"
        const val UNSAVED_PLAIN = "list.saved.undone.plain"
    }
}
