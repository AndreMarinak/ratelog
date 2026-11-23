package org.ratelog.watchlist.seen

import org.ratelog.watchlist.WatchStatus

/**
 * The Letterboxd-style view of a title's status, for the Seen and Watchlist toggles. The data model is unchanged:
 * one [WatchStatus] (or none) per user and title, plus the rating.
 *
 * - **Seen**, movies: rated, or status Watched. A rated movie stays seen: the Seen toggle can't clear it (that would
 *   mean deleting the rating), so it is "locked".
 * - **Seen**, shows: status Watched, or rated with no status at all (ratings from before statuses existed). Shows are
 *   rated per season and rating one only makes the show Watching (MarkWatchedOnRatingHandler), so an explicit
 *   Watching / Want to watch / Dropped wins over "has a rated season". Never locked: un-seeing a rated show moves it
 *   back to Watching. List progress uses this same rule.
 * - **Watchlist** = Want to watch.
 */
data class SeenState(
    val status: WatchStatus?,
    val rated: Boolean,
    val isTv: Boolean = false,
) {
    val seen: Boolean get() =
        if (isTv) status == WatchStatus.WATCHED || (rated && status == null)
        else rated || status == WatchStatus.WATCHED

    /** A rated movie: tapping Seen explains instead of clearing anything. */
    val seenLocked: Boolean get() = rated && !isTv

    val onWatchlist: Boolean get() = status == WatchStatus.WANT_TO_WATCH

    /** The one-click bookmark on posters never downgrades Watching / Watched / Dropped (see QuickWatchlistController). */
    val canToggleWatchlist: Boolean get() = status == null || status == WatchStatus.WANT_TO_WATCH
}
