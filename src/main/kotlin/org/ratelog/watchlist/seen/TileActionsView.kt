package org.ratelog.watchlist.seen

import org.ratelog.MediaType
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate
import java.util.Locale

/**
 * The quick actions on a poster tile (home rows, Discover rows and grid, search results, list pages), rendered by
 * `templates/fragments/tile-actions.html :: actions` and kept in sync in place by `static/tile-actions.js`.
 * Top-right of the poster, stacked: the watchlist bookmark (or a Watching / Dropped badge), then the Seen eye.
 * Upcoming titles get no Seen toggle (you can't have seen them) and an "Upcoming · Dec 18" badge instead.
 */
data class TileActionsView(
    val tmdbId: Int,
    /** `movie` or `tv`: what POST /watchlist/toggle and POST /seen/toggle expect. */
    val type: String,
    /** Api name of the viewer's status, or null. */
    val status: String?,
    val onWatchlist: Boolean,
    /** The bookmark is rendered either way (hidden when false) so the page can show it again after un-seeing. */
    val canToggleWatchlist: Boolean,
    val seen: Boolean,
    /** Rated: the eye shows seen and tapping it only explains why it can't be cleared. */
    val seenLocked: Boolean,
    val upcoming: Boolean,
    /** Short date for the Upcoming badge ("Dec 18"), null when not upcoming. */
    val upcomingDate: String?,
    /** Watching / Dropped (or Watched on a title with no Seen toggle): shown where the bookmark would be. */
    val badgeIcon: String?,
    val badgeKey: String?,
) {
    val canToggleSeen: Boolean get() = !upcoming

    companion object {
        fun of(
            tmdbId: Int,
            mediaType: MediaType,
            status: WatchStatus?,
            rated: Boolean,
            releaseDate: LocalDate?,
            today: LocalDate,
            locale: Locale,
        ): TileActionsView {
            val state = SeenState(status, rated, isTv = mediaType == MediaType.tvshow)
            val upcoming = ReleaseRules.isUpcoming(releaseDate, today)
            val badge = status?.takeIf {
                it == WatchStatus.WATCHING || it == WatchStatus.DROPPED || (it == WatchStatus.WATCHED && upcoming)
            }
            return TileActionsView(
                tmdbId = tmdbId,
                type = if (mediaType == MediaType.movie) "movie" else "tv",
                status = status?.apiName,
                onWatchlist = state.onWatchlist,
                canToggleWatchlist = state.canToggleWatchlist,
                seen = state.seen,
                seenLocked = state.seenLocked,
                upcoming = upcoming,
                upcomingDate = if (upcoming && releaseDate != null) ReleaseRules.shortDate(releaseDate, today, locale) else null,
                badgeIcon = badge?.icon,
                badgeKey = badge?.messageKey,
            )
        }
    }
}

/** What POST /seen/toggle and POST /watchlist/toggle answer to `fetch` (header `X-Requested-With: fetch`). */
data class TileStateResponse(
    val ok: Boolean,
    val status: String?,
    val seen: Boolean,
    val seenLocked: Boolean,
    val onWatchlist: Boolean,
    val canToggleWatchlist: Boolean,
    /** +1 / -1 / 0: how "seen" changed, so list pages can update "You've seen 9 of 25" without a reload. */
    val seenDelta: Int,
    /** Localized, for the toast. */
    val message: String?,
) {
    companion object {
        fun of(state: SeenState, seenDelta: Int, message: String?, ok: Boolean = true) = TileStateResponse(
            ok = ok,
            status = state.status?.apiName,
            seen = state.seen,
            seenLocked = state.seenLocked,
            onWatchlist = state.onWatchlist,
            canToggleWatchlist = state.canToggleWatchlist,
            seenDelta = seenDelta,
            message = message,
        )
    }
}
