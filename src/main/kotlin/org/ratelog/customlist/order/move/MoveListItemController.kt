package org.ratelog.customlist.order.move

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.CustomListRepository
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieRepository
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.user.User
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

/** What `list-order.js` gets back: the server's order wins over what the page shows. */
data class ListMoveResponse(
    val ok: Boolean,
    val message: String,
    val itemId: Long? = null,
    val position: Int? = null,
    val total: Int? = null,
    /** Every item id of the list, in the saved order. */
    val order: List<Long> = emptyList(),
)

/**
 * The reorder mode's forms on /list/{id}?reorder=1 (CSRF-protected POST): `position` (a 1-based place, "move to #")
 * or `to=top|up|down|bottom` (the arrow buttons; `to` wins). A plain post redirects back to reorder mode at the
 * title with a flash message; `fetch` with `X-Requested-With: fetch` (list-order.js) gets [ListMoveResponse] instead.
 */
@Controller
class MoveListItemController(
    private val handler: MoveListItemHandler,
    private val customListRepository: CustomListRepository,
    private val movieRepository: MovieRepository,
    private val tvShowRepository: TvShowRepository,
    private val messages: MessageSource,
) {
    @PostMapping("/list/{id}/item/{itemId}/move")
    fun move(
        @PathVariable id: Long,
        @PathVariable itemId: Long,
        @RequestParam("position", required = false) position: String?,
        @RequestParam("to", required = false) to: String?,
        @CurrentUser user: User,
        redirectAttributes: RedirectAttributes,
    ): String {
        when (val outcome = run(id, itemId, position, to, user)) {
            is Outcome.Moved -> redirectAttributes.addFlashAttribute(NOTICE, outcome.message)
            is Outcome.Failed -> redirectAttributes.addFlashAttribute("error", outcome.messageKey)
        }
        return "redirect:/list/$id?reorder=1#order-item-$itemId"
    }

    @PostMapping("/list/{id}/item/{itemId}/move", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun moveJson(
        @PathVariable id: Long,
        @PathVariable itemId: Long,
        @RequestParam("position", required = false) position: String?,
        @RequestParam("to", required = false) to: String?,
        @CurrentUser user: User,
    ): ResponseEntity<ListMoveResponse> = when (val outcome = run(id, itemId, position, to, user)) {
        is Outcome.Moved -> ResponseEntity.ok(
            ListMoveResponse(
                ok = true,
                message = outcome.message,
                itemId = itemId,
                position = outcome.moved.to,
                total = outcome.moved.total,
                order = outcome.moved.order.map { it.value },
            ),
        )
        is Outcome.Failed -> ResponseEntity.status(outcome.status).body(ListMoveResponse(ok = false, message = message(outcome.messageKey)))
    }

    private sealed interface Outcome {
        data class Moved(val moved: ListItemMoved, val message: String) : Outcome
        data class Failed(val status: HttpStatus, val messageKey: String) : Outcome
    }

    private fun run(id: Long, itemId: Long, position: String?, to: String?, user: User): Outcome {
        val target = MoveTarget.parse(to)
            ?: position?.trim()?.toIntOrNull()?.let { MoveTarget.Place(it) }
            ?: return Outcome.Failed(HttpStatus.BAD_REQUEST, "list.order.error.position")
        val command = MoveListItem(CustomList.Id(id), user.id!!, CustomListItem.Id(itemId), target)
        return handler.handle(command).fold(
            { error ->
                when (error) {
                    MoveListItemError.ListNotFound -> Outcome.Failed(HttpStatus.NOT_FOUND, "list.not.found")
                    MoveListItemError.NotOwner -> Outcome.Failed(HttpStatus.FORBIDDEN, "list.order.error.not.owner")
                    MoveListItemError.ItemNotFound -> Outcome.Failed(HttpStatus.NOT_FOUND, "list.order.error.item")
                }
            },
            { moved -> Outcome.Moved(moved, movedMessage(moved)) },
        )
    }

    private fun movedMessage(moved: ListItemMoved): String {
        val title = customListRepository.findItemById(moved.itemId)?.let { titleOf(it) }
        return when {
            title == null -> message("list.order.moved.untitled", moved.to)
            moved.from == moved.to -> message("list.order.unchanged", title, moved.to)
            else -> message("list.order.moved", title, moved.to)
        }
    }

    /** The title as the list page shows it (the original title, like GetListDetailHandler). */
    private fun titleOf(item: CustomListItem): String? = when (item.mediaType) {
        MediaType.movie -> movieRepository.findById(Movie.Id(item.mediaId))?.originalTitle?.value
        MediaType.tvshow -> tvShowRepository.findById(TvShow.Id(item.mediaId))?.originalName?.value
    }

    private fun message(key: String, vararg args: Any): String =
        messages.getMessage(key, args, key, LocaleContextHolder.getLocale()) ?: key

    companion object {
        /** Flash attribute: the "Moved X to #3" text, shown by fragments/list-order :: editor after a plain post. */
        const val NOTICE = "listOrderNotice"
    }
}
