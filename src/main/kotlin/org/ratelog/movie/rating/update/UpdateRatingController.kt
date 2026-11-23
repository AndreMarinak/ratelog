package org.ratelog.movie.rating.update

import org.ratelog.annotations.CurrentUser
import org.ratelog.movie.Movie
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class UpdateRatingController(
    private val handler: UpdateRatingHandler,
) {

    @PostMapping("/movie/rate/update")
    fun updateRating(
        @CurrentUser user: User,
        @RequestParam("movieId") movieId: Long,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("directing", required = false) directing: Double?,
        @RequestParam("cinematography", required = false) cinematography: Double?,
        @RequestParam("acting", required = false) acting: Double?,
        @RequestParam("soundtrack", required = false) soundtrack: Double?,
        @RequestParam("screenplay", required = false) screenplay: Double?,
        @RequestParam("score", required = false) score: Double?,
        @RequestParam("reviewTitle", required = false) reviewTitle: String?,
        @RequestParam("reviewText", required = false) reviewText: String?,
        redirectAttributes: RedirectAttributes
    ): String =
        UpdateRating(
            movieId = Movie.Id(movieId),
            userId = user.id!!,
            directing = directing,
            cinematography = cinematography,
            acting = acting,
            soundtrack = soundtrack,
            screenplay = screenplay,
            reviewTitle = reviewTitle?.takeIf { it.isNotBlank() },
            reviewText = reviewText?.takeIf { it.isNotBlank() },
            score = score,
        ).let(handler::handle)
            .mapLeft(::mapError)
            .fold(
                {
                    redirectAttributes.addFlashAttribute("error", it)
                    "redirect:/movie/${tmdbId}"
                },
                {
                    redirectAttributes.addFlashAttribute("success", "success.rating.updated")
                    "redirect:/movie/${tmdbId}"
                }
            )

    private fun mapError(error: UpdateRatingHandlerError): String = when (error) {
        UpdateRatingHandlerError.InvalidRatingValue -> "error.rating.invalid"
        UpdateRatingHandlerError.InvalidRatingCombination -> "error.rating.combination"
        UpdateRatingHandlerError.RatingNotFound -> "error.rating.update.failed"
        UpdateRatingHandlerError.InvalidReviewTitle -> "error.review.title.invalid"
        UpdateRatingHandlerError.InvalidReviewText -> "error.review.text.invalid"
    }
}
