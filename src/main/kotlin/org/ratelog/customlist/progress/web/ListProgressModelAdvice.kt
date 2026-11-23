package org.ratelog.customlist.progress.web

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.getlistdetail.GetListDetailController
import org.ratelog.customlist.progress.GetListProgress
import org.ratelog.customlist.progress.GetListProgressHandler
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.customlist.progress.ListProgress
import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.ratelog.watchlist.seen.TitleRelease
import org.ratelog.watchlist.seen.TitleReleaseRepository
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.LocalDate

/*
 * Adds the viewer's progress (`listProgress`) and the back link (`listBack`) to /list/{id} without touching the
 * upstream list detail controller and handler. Rendered by templates/fragments/list-progress.html.
 * `?show=unseen` / `?show=seen` filter the grid; ranks stay those of the whole list.
 * `?mark=1` is tick-off mode ("Mark what you've seen"): tapping a poster toggles Seen (static/list-tick.js).
 * Each poster also gets its Seen / Watchlist quick actions and Upcoming badge (one release-date query per media type).
 */
@ControllerAdvice(assignableTypes = [GetListDetailController::class])
class ListProgressModelAdvice(
    private val handler: GetListProgressHandler,
    private val userRepository: UserRepository,
    private val titleReleaseRepository: TitleReleaseRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @ModelAttribute
    fun addListProgress(
        @CurrentUser user: User?,
        @PathVariable("id") listId: Long,
        @RequestParam("show", required = false) show: String?,
        @RequestParam("mark", required = false) mark: String?,
        model: Model,
    ) {
        val viewerId = user?.id
        val progress = viewerId?.let { handler.handle(GetListProgress(CustomList.Id(listId), it)).getOrNull() }
        val tick = mark?.trim()?.lowercase() in TICK_VALUES

        model.addAttribute(
            "listProgress",
            progress?.toView(ListFilter.parse(show), tick, releases(progress), LocalDate.now(clock), LocaleContextHolder.getLocale()),
        )
        model.addAttribute("listBack", backLink(progress?.ownerId, viewerId))
    }

    private fun releases(progress: ListProgress): Map<Pair<MediaType, Long>, TitleRelease> =
        progress.entries.groupBy({ it.mediaType }, { it.mediaId }).flatMap { (type, ids) ->
            titleReleaseRepository.find(type, ids).map { (mediaId, release) -> (type to mediaId) to release }
        }.toMap()

    private fun backLink(ownerId: User.Id?, viewerId: User.Id?): ListBackLink {
        if (ownerId == null || ownerId == viewerId) return ListBackLink.LISTS
        val owner = userRepository.findById(ownerId) ?: return ListBackLink.LISTS
        return ListBackLink.profile(ownerId.value, owner.username.value)
    }

    companion object {
        private val TICK_VALUES = setOf("1", "true", "on", "yes")
    }
}
