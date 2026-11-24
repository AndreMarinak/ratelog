package org.ratelog.tvshow.rating.show

import org.ratelog.annotations.CurrentUser
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * The TV page's "Rate show" modal (fragments/rate-show-modal.html). Plain form posts that redirect back to the show
 * with a flash message; the page sends them with inplace.js (`data-inplace`), so the page itself never reloads.
 * - POST /tv/rate/show (`tvShowId`, `tmdbId`, `score`): set or change the whole-show score.
 * - POST /tv/rate/show/delete (`tvShowId`, `tmdbId`): delete it.
 */
@Controller
class RateShowController(
    private val rateHandler: RateShowHandler,
    private val deleteHandler: DeleteShowRatingHandler,
) {
    @PostMapping("/tv/rate/show")
    fun rate(
        @CurrentUser user: User,
        @RequestParam("tvShowId") tvShowId: Long,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("score", required = false) score: Double?,
        redirectAttributes: RedirectAttributes,
    ): String {
        rateHandler.handle(RateShow(TvShow.Id(tvShowId), user.id!!, score)).fold(
            { error ->
                redirectAttributes.addFlashAttribute(
                    "error",
                    when (error) {
                        RateShowHandlerError.InvalidScore -> "error.rating.invalid"
                        RateShowHandlerError.TvShowNotFound -> "error.load.tvshow"
                    },
                )
            },
            { result ->
                redirectAttributes.addFlashAttribute(
                    "success",
                    if (result.outcome == RateShowOutcome.RATED) "success.rating.saved" else "success.rating.updated",
                )
            },
        )
        return "redirect:/tv/$tmdbId"
    }

    @PostMapping("/tv/rate/show/delete")
    fun delete(
        @CurrentUser user: User,
        @RequestParam("tvShowId") tvShowId: Long,
        @RequestParam("tmdbId") tmdbId: Int,
        redirectAttributes: RedirectAttributes,
    ): String {
        deleteHandler.handle(DeleteShowRating(TvShow.Id(tvShowId), user.id!!)).fold(
            { redirectAttributes.addFlashAttribute("error", "error.rating.delete.failed") },
            { redirectAttributes.addFlashAttribute("success", "success.rating.deleted") },
        )
        return "redirect:/tv/$tmdbId"
    }
}
