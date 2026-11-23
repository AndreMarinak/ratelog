package org.ratelog.movie.togglefollow

import org.ratelog.annotations.CurrentUser
import org.ratelog.movie.Movie
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import jakarta.servlet.http.HttpServletRequest

@Controller
class ToggleMovieFollowController(
    private val handler: ToggleMovieFollowHandler,
) {

    @PostMapping("/movie/follow")
    fun toggleMovieFollow(
        @CurrentUser user: User,
        @RequestParam("movieId") movieId: Long,
        request: HttpServletRequest
    ): String {
        ToggleMovieFollow(
            movieId = Movie.Id(movieId),
            userId = user.id!!,
        ).let(handler::handle)

        // Fork: back to the page the button was on, only ever a same-site path.
        return "redirect:" + BackPath.resolve(null, request.getHeader("Referer"), "/")
    }
}
