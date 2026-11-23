package org.ratelog.browse

import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus

/** What the user has done with a title Ratelog already knows: their score and watch status. */
data class LibraryMark(
    /** Movies: the rating's score. TV: the show's average over rated seasons. Null when unrated. */
    val score: Double?,
    val rated: Boolean,
    val status: WatchStatus?,
) {
    val onWatchlist: Boolean get() = status == WatchStatus.WANT_TO_WATCH
}

interface BrowseLibraryRepository {
    /**
     * Marks for the given TMDB ids in one query. Titles the user never rated or gave a status
     * (or that Ratelog has never stored) are left out.
     */
    fun findMarks(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>): Map<Int, LibraryMark>
}
