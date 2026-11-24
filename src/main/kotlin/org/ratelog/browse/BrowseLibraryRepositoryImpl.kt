package org.ratelog.browse

import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Repository

@Repository
class BrowseLibraryRepositoryImpl(
    private val dao: BrowseLibraryDAO,
) : BrowseLibraryRepository {

    override fun findMarks(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>): Map<Int, LibraryMark> {
        val ids = tmdbIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        val rows = when (type) {
            MediaType.movie -> dao.findMovieMarks(userId.value, ids)
            MediaType.tvshow -> dao.findTvMarks(userId.value, ids)
        }
        return rows
            .mapNotNull { row ->
                val status = row.status?.let { runCatching { WatchStatus.valueOf(it) }.getOrNull() }
                if (!row.rated && status == null && !row.onWatchlist) return@mapNotNull null
                row.tmdbId to LibraryMark(score = row.score?.takeIf { row.rated }, rated = row.rated, status = status, onWatchlist = row.onWatchlist)
            }
            .toMap()
    }
}
