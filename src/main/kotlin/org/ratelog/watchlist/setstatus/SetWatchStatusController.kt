package org.ratelog.watchlist.setstatus

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.net.URI

@Controller
class SetWatchStatusController(
    private val handler: SetWatchStatusHandler,
) {

    /** Form post from the status control on detail pages. An empty `status` clears it. */
    @PostMapping("/status")
    fun setStatus(
        @CurrentUser user: User,
        @RequestParam("mediaType") mediaType: String,
        @RequestParam("mediaId") mediaId: Long,
        @RequestParam("status", required = false) status: String?,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        val back = "redirect:" + backPath(request)

        val type = MediaType.entries.find { it.name == mediaType }
        val newStatus = status?.takeIf { it.isNotBlank() }?.let { WatchStatus.parse(it) ?: return invalid(redirectAttributes, back) }
        if (type == null) return invalid(redirectAttributes, back)

        SetWatchStatus(
            userId = user.id!!,
            mediaType = type,
            mediaId = mediaId,
            status = newStatus,
        ).let(handler::handle)
            .onLeft { error ->
                val message = when (error) {
                    SetWatchStatusError.MediaNotFound -> "status.error.media.not.found"
                }
                redirectAttributes.addFlashAttribute("error", message)
            }

        return back
    }

    private fun invalid(redirectAttributes: RedirectAttributes, back: String): String {
        redirectAttributes.addFlashAttribute("error", "status.error.invalid")
        return back
    }

    /** Back to the page the form was on; only its path is kept so the redirect never leaves the app. */
    private fun backPath(request: HttpServletRequest): String {
        val referer = request.getHeader("Referer") ?: return "/watchlist"
        val uri = runCatching { URI(referer) }.getOrNull() ?: return "/watchlist"
        val path = uri.rawPath?.takeIf { it.startsWith("/") } ?: return "/watchlist"
        return path + (uri.rawQuery?.let { "?$it" } ?: "")
    }
}
