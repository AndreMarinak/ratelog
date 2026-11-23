package org.ratelog.watchlist.quickadd

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.MediaImporter
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.net.URI

/** What a one-click watchlist toggle did. */
enum class QuickWatchlistOutcome(val messageKey: String) {
    ADDED("watchlist.quick.added"),
    REMOVED("watchlist.quick.removed"),
    /** Watching / Watched / Dropped are left alone: the bookmark only toggles "Want to watch". */
    UNCHANGED("watchlist.quick.unchanged"),
}

/**
 * One-click "add to watchlist" from any poster tile (home rows, search, browse, lists), which only know the TMDB id.
 * Saves the title locally if needed, then toggles Want to watch. Plain form POST + redirect back, like the status control.
 */
@Controller
class QuickWatchlistController(
    private val mediaImporter: MediaImporter,
    private val mediaStatusRepository: MediaStatusRepository,
    private val setWatchStatusHandler: SetWatchStatusHandler,
) {
    @PostMapping("/watchlist/toggle")
    fun toggle(
        @CurrentUser user: User,
        @RequestParam("tmdbId") tmdbId: Int,
        @RequestParam("type") type: String,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        val back = "redirect:" + backPath(request)
        val mediaType = parseType(type)
        if (mediaType == null || tmdbId <= 0) {
            redirectAttributes.addFlashAttribute("error", "status.error.invalid")
            return back
        }
        val media = mediaImporter.ensure(tmdbId, mediaType, user.metadataLang).getOrNull()
        if (media == null) {
            redirectAttributes.addFlashAttribute("error", "status.error.media.not.found")
            return back
        }
        val outcome = toggle(user.id!!, mediaType, media.id)
        redirectAttributes.addFlashAttribute("success", outcome.messageKey)
        return back
    }

    fun toggle(userId: User.Id, mediaType: MediaType, mediaId: Long): QuickWatchlistOutcome {
        val current = mediaStatusRepository.find(userId, mediaType, mediaId)?.status
        val (next, outcome) = when (current) {
            null -> WatchStatus.WANT_TO_WATCH to QuickWatchlistOutcome.ADDED
            WatchStatus.WANT_TO_WATCH -> null to QuickWatchlistOutcome.REMOVED
            else -> return QuickWatchlistOutcome.UNCHANGED
        }
        setWatchStatusHandler.handle(SetWatchStatus(userId, mediaType, mediaId, next))
        return outcome
    }

    private fun parseType(type: String): MediaType? = when (type.trim().lowercase()) {
        "movie" -> MediaType.movie
        "tv", "tvshow" -> MediaType.tvshow
        else -> null
    }

    /** Back to the page the button was on; only the path is kept so the redirect never leaves the app. */
    private fun backPath(request: HttpServletRequest): String {
        val referer = request.getHeader("Referer") ?: return "/watchlist"
        val uri = runCatching { URI(referer) }.getOrNull() ?: return "/watchlist"
        val path = uri.rawPath?.takeIf { it.startsWith("/") && !it.startsWith("//") } ?: return "/watchlist"
        return path + (uri.rawQuery?.let { "?$it" } ?: "")
    }
}
