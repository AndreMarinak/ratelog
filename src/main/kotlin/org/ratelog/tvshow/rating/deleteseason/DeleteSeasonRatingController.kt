package org.ratelog.tvshow.rating.deleteseason

import org.ratelog.SeasonNumber
import org.ratelog.annotations.CurrentUser
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import jakarta.servlet.http.HttpServletRequest

@Controller
class DeleteSeasonRatingController(
    private val handler: DeleteSeasonRatingHandler,
) {

    @PostMapping("/tvshows/delete/{id}")
    @Transactional
    fun deleteRating(
        @CurrentUser user: User,
        @PathVariable("id") tvShowId: Long,
        @RequestParam("seasonNumber") seasonNumber: Int,
        redirectAttributes: RedirectAttributes,
        request: HttpServletRequest
    ): String {
        val result = DeleteSeasonRating(
            tvShowId = TvShow.Id(tvShowId),
            seasonNumber = seasonNumber.let(::SeasonNumber),
            userId = user.id!!,
        ).let(handler::handle)

        // Fork: back to the title page it was deleted from (same-site path only), else the ratings list.
        val back = BackPath.local(request.getHeader("Referer"), keepFragment = false)
        val redirectTarget = "redirect:" + (back?.takeIf { it.startsWith("/tv/") } ?: "/tvshows")

        return result
            .mapLeft(::mapError)
            .fold(
                {
                    redirectAttributes.addFlashAttribute("error", it)
                    redirectTarget
                },
                {
                    redirectAttributes.addFlashAttribute("success", "success.rating.deleted")
                    redirectTarget
                }
            )
    }

    private fun mapError(error: DeleteSeasonRatingHandlerError): String = when (error) {
        DeleteSeasonRatingHandlerError.TvShowNotFound,
        DeleteSeasonRatingHandlerError.RatingNotFound -> "error.rating.delete.failed"
    }
}
