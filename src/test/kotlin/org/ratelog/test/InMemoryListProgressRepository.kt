package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.progress.ListProgressRepository
import org.ratelog.customlist.progress.ViewerMark
import org.ratelog.movie.Movie
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User
import org.ratelog.watchlist.WatchlistEntry
import java.util.concurrent.atomic.AtomicInteger

/**
 * Progress computed from the other in-memory fakes, with the same rules as ListProgressDAO:
 * movies are rated with a rating row, shows with at least one rated season; the bulk add never overwrites a status.
 */
class InMemoryListProgressRepository(
    private val lists: InMemoryCustomListRepository,
    private val statuses: InMemoryMediaStatusRepository,
    private val ratings: InMemoryRatingRepository = InMemoryRatingRepository(),
    private val tvRatings: InMemoryTvRatingRepository = InMemoryTvRatingRepository(),
) : ListProgressRepository {

    /** findMarks calls, to check marks are looked up once per media type (not per title). */
    val markQueries = AtomicInteger()
    val markQueryTypes = mutableListOf<MediaType>()

    override fun findMarks(userId: User.Id, mediaType: MediaType, mediaIds: Collection<Long>): Map<Long, ViewerMark> {
        markQueries.incrementAndGet()
        markQueryTypes += mediaType
        return mediaIds.distinct()
            .map { it to mark(userId, mediaType, it) }
            .filter { (_, mark) -> mark != ViewerMark.NONE }
            .toMap()
    }

    override fun countSeen(userId: User.Id, listIds: Collection<CustomList.Id>): Map<CustomList.Id, Int> =
        listIds.distinct().mapNotNull { id ->
            val seen = lists.findById(id)?.items?.count { mark(userId, it.mediaType, it.mediaId).seen(it.mediaType) } ?: 0
            if (seen > 0) id to seen else null
        }.toMap()

    override fun addToWatchlist(userId: User.Id, listId: CustomList.Id, itemIds: Collection<CustomListItem.Id>, nowEpochMs: Long): Int {
        val list = lists.findById(listId) ?: return 0
        val chosen = list.items
            .filter { it.id in itemIds && !mark(userId, it.mediaType, it.mediaId).rated }
            .sortedWith(compareBy<CustomListItem> { it.position }.thenBy { it.id!!.value })
        var added = 0
        chosen.forEachIndexed { index, item ->
            // Any status (Watching / Watched / Dropped) is skipped; ON CONFLICT DO NOTHING: already on the watchlist.
            if (statuses.find(userId, item.mediaType, item.mediaId) == null &&
                statuses.findWatchlistEntry(userId, item.mediaType, item.mediaId) == null
            ) {
                statuses.addToWatchlist(WatchlistEntry(userId, item.mediaType, item.mediaId, nowEpochMs - index))
                added++
            }
        }
        return added
    }

    private fun mark(userId: User.Id, mediaType: MediaType, mediaId: Long): ViewerMark {
        val status = statuses.find(userId, mediaType, mediaId)?.status
        val listed = statuses.findWatchlistEntry(userId, mediaType, mediaId) != null
        return when (mediaType) {
            MediaType.movie -> ratings.findByMovieIdAndUserId(Movie.Id(mediaId), userId)
                .let { ViewerMark(status, it?.score?.value, rated = it != null, onWatchlist = listed) }
            MediaType.tvshow -> tvRatings.findByTvShowIdAndUserId(TvShow.Id(mediaId), userId)
                .let { ViewerMark(status, it?.score?.value, rated = it?.isRated == true, onWatchlist = listed) }
        }
    }
}
