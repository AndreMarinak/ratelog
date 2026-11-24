package org.ratelog.watchlist.seen

import org.ratelog.watchlist.WatchStatus

/**
 * The Letterboxd-style view of a title's status, for the Seen and Watchlist toggles: the progress [status] (Watching /
 * Watched / Dropped, or none), the rating, and (fork, 2026-11-24) whether it is on the watchlist, which is independent.
 *
 * - **Seen**, movies: rated, or status Watched. A rated movie stays seen: the Seen toggle can't clear it (that would
 *   mean deleting the rating), so it is "locked".
 * - **Seen**, shows: status Watched, or rated with no status at all (ratings from before statuses existed). Shows are
 *   rated per season and rating one only makes the show Watching (MarkWatchedOnRatingHandler), so an explicit
 *   Watching / Want to watch / Dropped wins over "has a rated season". Never locked: un-seeing a rated show moves it
 *   back to Watching. List progress uses this same rule.
 * - **Watchlist** (fork, 2026-11-24): its own flag ([onWatchlist], table `watchlist_entries`), so a seen title can be on
 *   it too (a rewatch). Being on the watchlist never changes "seen". [status] is never Want to watch.
 */
data class SeenState(
    /** The progress status: Watching, Watched, Dropped or null. Never [WatchStatus.WANT_TO_WATCH]. */
    val status: WatchStatus?,
    val rated: Boolean,
    val isTv: Boolean = false,
    val onWatchlist: Boolean = false,
) {
    val seen: Boolean get() =
        if (isTv) status == WatchStatus.WATCHED || (rated && status == null)
        else rated || status == WatchStatus.WATCHED

    /** A rated movie: tapping Seen explains instead of clearing anything. */
    val seenLocked: Boolean get() = rated && !isTv

    /**
     * The one-click bookmark on posters: every title but a Watching / Dropped one (fork, 2026-11-24: seen and rated titles
     * too; adding them keeps them seen). A show you're watching or dropped keeps its badge there instead.
     */
    val canToggleWatchlist: Boolean get() = status != WatchStatus.WATCHING && status != WatchStatus.DROPPED

    /** On the watchlist and already seen: a rewatch (tagged on the watchlist page). */
    val rewatch: Boolean get() = onWatchlist && seen

    /** What the API and the JSON answers call `status`: Want to watch for a title only on the watchlist. */
    val effectiveStatus: WatchStatus? get() = WatchStatus.effective(status, onWatchlist)
}
