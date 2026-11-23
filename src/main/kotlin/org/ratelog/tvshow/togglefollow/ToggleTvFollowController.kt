package org.ratelog.tvshow.togglefollow

import org.ratelog.annotations.CurrentUser
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import jakarta.servlet.http.HttpServletRequest

@Controller
class ToggleTvFollowController(
    private val handler: ToggleTvFollowHandler,
) {

    @PostMapping("/tvshow/follow")
    fun toggleTvFollow(
        @CurrentUser user: User,
        @RequestParam("tvShowId") tvShowId: Long,
        request: HttpServletRequest
    ): String {
        ToggleTvFollow(
            tvShowId = TvShow.Id(tvShowId),
            userId = user.id!!,
        ).let(handler::handle)

        // Fork: back to the page the button was on, only ever a same-site path.
        return "redirect:" + BackPath.resolve(null, request.getHeader("Referer"), "/")
    }
}
