package org.ratelog.watchlist.quickadd

import jakarta.servlet.http.HttpServletRequest
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.MediaImporter
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.entry.SetWatchlist
import org.ratelog.watchlist.entry.SetWatchlistHandler
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.net.URI

/** What a one-click watchlist toggle did. */
enum class QuickWatchlistOutcome(val messageKey: String) {
    ADDED("watchlist.quick.added"),
    REMOVED("watchlist.quick.removed"),
    /** Watching / Dropped are left alone: the poster bookmark doesn't move a show you're in the middle of (or dropped). */
    UNCHANGED("watchlist.quick.unchanged"),
}

/**
 * One-click "add to watchlist" from any poster tile (home rows, search, browse, lists), which only know the TMDB id.
 * Saves the title locally if needed, then toggles it on the watchlist. Plain form POST + redirect back, like the status control.
 * Fork (2026-11-24): a seen (Watched or rated) title can be added too and stays seen ([SetWatchlistHandler]).
 */
@Controller
class QuickWatchlistController(
    private val mediaImporter: MediaImporter,
    private val mediaStatusRepository: MediaStatusRepository,
    private val setWatchlistHandler: SetWatchlistHandler,
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
        val on = mediaStatusRepository.findWatchlistEntry(userId, mediaType, mediaId) != null
        val status = mediaStatusRepository.find(userId, mediaType, mediaId)?.status
        if (!on && (status == WatchStatus.WATCHING || status == WatchStatus.DROPPED)) return QuickWatchlistOutcome.UNCHANGED
        setWatchlistHandler.handle(SetWatchlist(userId, mediaType, mediaId, on = !on))
        return if (on) QuickWatchlistOutcome.REMOVED else QuickWatchlistOutcome.ADDED
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
