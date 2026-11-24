package org.ratelog.watchlist

import org.ratelog.MediaType
import org.ratelog.user.User

/**
 * Fork (2026-11-24): a title on the user's watchlist (table `watchlist_entries`), the one source of truth for "on my
 * watchlist". Independent of [MediaStatus] (progress: Watching / Watched / Dropped), so a seen title can be on the
 * watchlist too, to rewatch. Invariant kept by the handlers: a title on the watchlist has no status or Watched; setting
 * Watching or Dropped takes it off (as when there was one status), adding it clears Watching / Dropped.
 */
data class WatchlistEntry(
    val userId: User.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id). */
    val mediaId: Long,
    val addedAtEpochMs: Long,
    /**
     * Seen when it was added: becoming seen again (marking it Watched, rating it) leaves it on the watchlist, since that is
     * why it was put there. An entry added while unseen leaves the watchlist as soon as the title is seen. Not a "seen"
     * flag: seen is always computed from the status and the ratings (SeenState).
     */
    val rewatch: Boolean = false,
)
