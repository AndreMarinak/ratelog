package org.ratelog.browse

import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus

/** What the user has done with a title Ratelog already knows: their score and watch status. */
data class LibraryMark(
    /** Movies: the rating's score. TV: the show's average over rated seasons. Null when unrated. */
    val score: Double?,
    val rated: Boolean,
    /** The progress status (Watching / Watched / Dropped), never Want to watch: see [onWatchlist]. */
    val status: WatchStatus?,
    /** Fork (2026-11-24): on the watchlist, independent of [status] (a seen title can be on it too). */
    val onWatchlist: Boolean = status == WatchStatus.WANT_TO_WATCH,
) {
    /** What the API calls `status`: Want to watch for a title only on the watchlist. */
    val effectiveStatus: WatchStatus? get() = WatchStatus.effective(status, onWatchlist)

    /** "Seen" for Discover's "Hide titles I've seen": rated (any season, for TV) or marked Watched. */
    val seen: Boolean get() = rated || status == WatchStatus.WATCHED
}

interface BrowseLibraryRepository {
    /**
     * Marks for the given TMDB ids in one query. Titles the user never rated, gave a status or put on the watchlist
     * (or that Ratelog has never stored) are left out.
     */
    fun findMarks(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>): Map<Int, LibraryMark>
}
