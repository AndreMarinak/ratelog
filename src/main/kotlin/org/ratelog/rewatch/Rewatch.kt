package org.ratelog.rewatch

import org.ratelog.MediaType
import org.ratelog.user.User

/**
 * Fork (2026-11-24, rewatch log): one time a user watched a title again (table `rewatches`, V1144). The first watch is
 * the title's seen state (status Watched, or rated: SeenState), so the watch count is 1 + rewatches. Movies and shows
 * (a whole show, not an episode or a season).
 *
 * Rows of a title that isn't seen any more (un-seen with the eye, its last rating deleted) are kept but never counted:
 * every reader counts them only while the title is seen, and seeing it again brings them back.
 */
data class Rewatch(
    val id: Long?,
    val userId: User.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id). */
    val mediaId: Long,
    val watchedAtEpochMs: Long,
)

/** A title's rewatches: how many and the latest. */
data class RewatchSummary(
    val rewatches: Int,
    val lastAtEpochMs: Long?,
) {
    /** The first watch plus the rewatches (only meaningful while the title is seen). */
    val watchCount: Int get() = 1 + rewatches

    companion object {
        val NONE = RewatchSummary(0, null)
    }
}
