package org.ratelog.customlist.suggest.add

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
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

/** The answer to a `fetch` "+ Add": what list-suggest.js needs to mark the tile and point at the new list item. */
data class AddSuggestionResponse(
    val ok: Boolean,
    /** False when the title was in the list already. */
    val added: Boolean,
    val itemId: Long?,
    /** Its place in the list, null on a plain list. */
    val rank: Int?,
    /** For the toast: "Added "Up" to Pixar as #11." */
    val message: String?,
)

/**
 * POST /list/{id}/suggestions/add (`tmdbId`, `type` = `movie` | `tv`, optional `back`): "+ Add" on a tile of
 * "Suggested for this list". Only the list's owner can add. A plain form post goes back to the list with a flash
 * message; `fetch` with `X-Requested-With: fetch` gets [AddSuggestionResponse], and the page updates in place.
 */
@Controller
class AddSuggestionController(
    private val handler: AddSuggestionHandler,
    private val messages: MessageSource,
) {
    @PostMapping("/list/{id}/suggestions/add")
    fun add(
        @PathVariable id: Long,
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type") type: String,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        val target = "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/list/$id")
        when (val outcome = run(id, user, tmdbId, type)) {
            is Outcome.Done -> redirectAttributes.addFlashAttribute(
                if (outcome.result.added) "success" else "error",
                if (outcome.result.added) ADDED_PLAIN else "list.error.item.already.in.list",
            )
            is Outcome.Failed -> redirectAttributes.addFlashAttribute("error", outcome.messageKey)
        }
        return target
    }

    @PostMapping("/list/{id}/suggestions/add", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun addJson(
        @PathVariable id: Long,
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type") type: String,
    ): ResponseEntity<AddSuggestionResponse> = when (val outcome = run(id, user, tmdbId, type)) {
        is Outcome.Done -> {
            val r = outcome.result
            val message = when {
                !r.added -> text("list.suggest.already", r.title, r.listName)
                r.rank != null -> text("list.suggest.added", r.title, r.listName, r.rank)
                else -> text("list.suggest.added.norank", r.title, r.listName)
            }
            ResponseEntity.ok(AddSuggestionResponse(ok = true, added = r.added, itemId = r.itemId, rank = r.rank, message = message))
        }
        is Outcome.Failed -> ResponseEntity.status(outcome.httpStatus)
            .body(AddSuggestionResponse(ok = false, added = false, itemId = null, rank = null, message = text(outcome.messageKey)))
    }

    private sealed interface Outcome {
        data class Done(val result: AddSuggestionResult) : Outcome
        data class Failed(val messageKey: String, val httpStatus: HttpStatus) : Outcome
    }

    private fun run(id: Long, user: User, tmdbId: Int, type: String): Outcome {
        val mediaType = when (type.trim().lowercase()) {
            "movie" -> MediaType.movie
            "tv", "tvshow" -> MediaType.tvshow
            else -> null
        }
        if (mediaType == null || tmdbId <= 0 || id <= 0) return Outcome.Failed("list.suggest.error.invalid", HttpStatus.BAD_REQUEST)
        return handler.handle(AddSuggestion(CustomList.Id(id), user.id!!, mediaType, tmdbId, user.metadataLang)).fold(
            { error ->
                when (error) {
                    AddSuggestionError.ListNotFound -> Outcome.Failed("list.error.not.found", HttpStatus.NOT_FOUND)
                    AddSuggestionError.NotOwner -> Outcome.Failed("list.error.not.owner", HttpStatus.FORBIDDEN)
                    AddSuggestionError.TitleNotFound -> Outcome.Failed("list.suggest.error.title", HttpStatus.NOT_FOUND)
                    AddSuggestionError.TmdbNotConfigured, AddSuggestionError.TmdbUnavailable ->
                        Outcome.Failed("list.suggest.error.tmdb", HttpStatus.BAD_GATEWAY)
                }
            },
            { Outcome.Done(it) },
        )
    }

    private fun text(key: String, vararg args: Any): String =
        messages.getMessage(key, args.takeIf { it.isNotEmpty() }, key, LocaleContextHolder.getLocale()) ?: key

    companion object {
        /** Flash key for plain form posts: the list page shows `#{${success}}`, which takes no arguments. */
        const val ADDED_PLAIN = "list.suggest.added.plain"
    }
}
