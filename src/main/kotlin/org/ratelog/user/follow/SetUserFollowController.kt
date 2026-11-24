package org.ratelog.user.follow

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.annotations.CurrentUser
import org.ratelog.user.User
import org.ratelog.watchlist.seen.BackPath
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Fork: `POST /user/follow/set` (`id`, `following=true|false`, optional `back`) from the Follow / Following buttons
 * (templates/fragments/follow-button.html) on Community and profiles. Saves in place with inplace.js; without JS it
 * is a plain form post that redirects back to the page the button was on (same-site paths only, `BackPath`).
 */
@Controller
class SetUserFollowController(
    private val handler: SetUserFollowHandler,
) {
    @PostMapping("/user/follow/set")
    fun setFollow(
        @CurrentUser user: User,
        @RequestParam("id") userId: Long,
        @RequestParam("following") following: Boolean,
        @RequestParam("back", required = false) back: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        handler.handle(SetUserFollow(user.id!!, User.Id(userId), following)).onLeft {
            redirectAttributes.addFlashAttribute(
                "error",
                when (it) {
                    SetUserFollowError.UserNotFound -> "error.user.not.found"
                    SetUserFollowError.CannotFollowYourself -> "error.cannot.follow.yourself"
                },
            )
        }
        return "redirect:" + BackPath.resolve(back, request.getHeader("Referer"), "/profile/$userId")
    }
}
