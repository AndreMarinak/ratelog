package org.ratelog.movie.rating.delete

import org.ratelog.annotations.CurrentUser
import org.ratelog.movie.Movie
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import jakarta.servlet.http.HttpServletRequest

@Controller
class DeleteRatingController(
    private val handler: DeleteRatingHandler,
) {

    @PostMapping("/movies/delete/{id}")
    @Transactional
    fun deleteRating(
        @CurrentUser user: User,
        @PathVariable("id") movieId: Long,
        redirectAttributes: RedirectAttributes,
        request: HttpServletRequest
    ): String {
        val result = DeleteRating(
            movieId = Movie.Id(movieId),
            userId = user.id!!,
        ).let(handler::handle)

        // Fork: back to the title page it was deleted from (same-site path only), else the ratings list.
        val back = BackPath.local(request.getHeader("Referer"), keepFragment = false)
        val redirectTarget = "redirect:" + (back?.takeIf { it.startsWith("/movie/") } ?: "/movies")

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

    private fun mapError(error: DeleteRatingHandlerError): String = when (error) {
        DeleteRatingHandlerError.MovieNotFound,
        DeleteRatingHandlerError.RatingNotFound -> "error.rating.delete.failed"
    }
}
