package org.ratelog.customlist.membership

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.membership.web.ListMembershipTexts
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/** The answer to a `fetch` toggle: the list's new state plus everything the sheet and the title page button show. */
data class ListMembershipResponse(
    val ok: Boolean,
    val listId: Long,
    val inList: Boolean,
    /** The title's place in the list, null when it isn't in it. */
    val rank: Int?,
    /** "#3", or null. */
    val rankLabel: String?,
    /** The question to ask before removing it again, null when it isn't in the list. */
    val confirm: String?,
    /** How many of the viewer's lists have the title now. */
    val count: Int,
    val summary: String,
    val buttonLabel: String,
    val buttonTitle: String,
    val message: String?,
)

/**
 * POST /list/{id}/membership (`mediaId`, `mediaType`, `inList`, optional `back`): a tap on a list in the title page's
 * add-to-list sheet. `inList=true` adds the title (appended, like Add to list), `false` takes it out. Only the list's
 * owner can do either. A plain form post goes back to the title page with a flash message; `fetch` with
 * `X-Requested-With: fetch` gets [ListMembershipResponse] (list-membership.js), so the sheet stays open.
 */
@Controller
class SetListMembershipController(
    private val handler: SetListMembershipHandler,
    private val membershipRepository: ListMembershipRepository,
    messages: MessageSource,
) {
    private val texts = ListMembershipTexts(messages)

    @PostMapping("/list/{id}/membership")
    fun set(
        @PathVariable id: Long,
        @CurrentUser user: User,
        @RequestParam mediaId: Long,
        @RequestParam mediaType: String,
        @RequestParam inList: Boolean,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        val target = "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/list/$id")
        when (val outcome = run(id, user, mediaId, mediaType, inList)) {
            is Outcome.Done -> redirectAttributes.addFlashAttribute(
                "success",
                if (outcome.result.inList) ListMembershipTexts.ADDED_PLAIN else ListMembershipTexts.REMOVED_PLAIN,
            )
            is Outcome.Failed -> redirectAttributes.addFlashAttribute("error", outcome.messageKey)
        }
        return target
    }

    @PostMapping("/list/{id}/membership", headers = ["X-Requested-With=fetch"])
    @ResponseBody
    fun setJson(
        @PathVariable id: Long,
        @CurrentUser user: User,
        @RequestParam mediaId: Long,
        @RequestParam mediaType: String,
        @RequestParam inList: Boolean,
    ): ResponseEntity<ListMembershipResponse> = when (val outcome = run(id, user, mediaId, mediaType, inList)) {
        is Outcome.Done -> {
            // Read back what is stored: the sheet and the button show the database, not what was asked.
            val type = outcome.type
            val lists = membershipRepository.findListsContaining(user.id!!, type, mediaId)
            val here = lists.firstOrNull { it.listId.value == id }
            val name = outcome.result.listName.value
            ResponseEntity.ok(
                ListMembershipResponse(
                    ok = true,
                    listId = id,
                    inList = here != null,
                    rank = here?.rank,
                    rankLabel = here?.rank?.let(texts::rank),
                    confirm = here?.let { texts.confirmRemove(name, it.rank) },
                    count = lists.size,
                    summary = texts.summary(lists.size),
                    buttonLabel = texts.buttonLabel(lists.size),
                    buttonTitle = texts.buttonTitle(lists.size),
                    message = if (here != null) texts.added(name) else texts.removed(name),
                ),
            )
        }
        is Outcome.Failed -> ResponseEntity.status(outcome.httpStatus).body(
            ListMembershipResponse(
                ok = false, listId = id, inList = !inList, rank = null, rankLabel = null, confirm = null, count = 0,
                summary = "", buttonLabel = "", buttonTitle = "", message = texts.error(outcome.messageKey),
            ),
        )
    }

    private sealed interface Outcome {
        data class Done(val result: SetListMembershipResult, val type: MediaType) : Outcome
        data class Failed(val messageKey: String, val httpStatus: HttpStatus) : Outcome
    }

    private fun run(id: Long, user: User, mediaId: Long, mediaType: String, inList: Boolean): Outcome {
        val type = MediaType.entries.find { it.name == mediaType }
        if (type == null || mediaId <= 0) return Outcome.Failed("list.member.error.invalid", HttpStatus.BAD_REQUEST)
        return handler.handle(SetListMembership(CustomList.Id(id), user.id!!, type, mediaId, inList)).fold(
            { error ->
                when (error) {
                    SetListMembershipError.ListNotFound -> Outcome.Failed("list.error.not.found", HttpStatus.NOT_FOUND)
                    SetListMembershipError.NotOwner -> Outcome.Failed("list.error.not.owner", HttpStatus.FORBIDDEN)
                }
            },
            { Outcome.Done(it, type) },
        )
    }
}
