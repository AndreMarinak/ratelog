package org.ratelog.customlist.progress

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.seen.SeenState

/*
 * List progress (fork, roadmap item 12): how much of a list the *viewer* has seen, on their own lists and on
 * other people's public lists. Shown on /list/{id}, the /lists cards and GET /api/v1/lists/{id}.
 */

/** What the viewer has done with one title: their status, their score, and whether they rated it at all. */
data class ViewerMark(
    val status: WatchStatus?,
    /** Movies: the rating's score. TV: the show's score (average of rated seasons). Null when unrated. */
    val score: Double?,
    val rated: Boolean,
) {
    /**
     * "Seen" is the Seen toggle's rule (SeenState): a movie that is rated or Watched; a show that is Watched, or rated
     * with no status. Watching, Want to watch and Dropped are not seen (for a show, even with rated seasons).
     */
    fun seen(type: MediaType): Boolean = SeenState(status, rated, isTv = type == MediaType.tvshow).seen

    companion object {
        val NONE = ViewerMark(status = null, score = null, rated = false)
    }
}

/** The filter tabs on a list page: `?show=unseen` / `?show=seen`; anything else is All. */
enum class ListFilter(val param: String?, val labelKey: String) {
    ALL(null, "list.progress.filter.all"),
    UNSEEN("unseen", "list.progress.filter.unseen"),
    SEEN("seen", "list.progress.filter.seen");

    fun accepts(entry: ListProgressEntry): Boolean = when (this) {
        ALL -> true
        UNSEEN -> !entry.seen
        SEEN -> entry.seen
    }

    companion object {
        private val ALIASES = mapOf(
            "not_seen" to UNSEEN,
            "notseen" to UNSEEN,
            "unwatched" to UNSEEN,
            "watched" to SEEN,
        )

        /** Unknown or missing values mean [ALL], so a bad link still shows the list. */
        fun parse(value: String?): ListFilter {
            val key = value?.trim()?.lowercase()?.replace('-', '_')
            if (key.isNullOrEmpty()) return ALL
            return entries.find { it.param == key } ?: ALIASES[key] ?: ALL
        }
    }
}

/** One list item with the viewer's progress on it. */
data class ListProgressEntry(
    val itemId: CustomListItem.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id). */
    val mediaId: Long,
    /** 1-based place in the whole list. Filtering never renumbers it. */
    val rank: Int,
    val status: WatchStatus?,
    val score: Double?,
    val seen: Boolean,
    /** Rated (a show: at least one rated season). Such a title stays seen; the Seen toggle can't clear it. */
    val rated: Boolean = false,
) {
    /** "Add the unseen to my watchlist" would mark it Want to watch: not seen and no status at all. */
    val addable: Boolean get() = !seen && status == null
}

data class ListProgress(
    val listId: CustomList.Id,
    val ownerId: User.Id,
    /** Every item, in list order. */
    val entries: List<ListProgressEntry>,
) {
    val total: Int get() = entries.size
    val seenCount: Int get() = entries.count { it.seen }
    val unseenCount: Int get() = total - seenCount
    /** Unseen titles with no status: what the bulk watchlist button adds. */
    val addableCount: Int get() = entries.count { it.addable }

    fun count(filter: ListFilter): Int = when (filter) {
        ListFilter.ALL -> total
        ListFilter.UNSEEN -> unseenCount
        ListFilter.SEEN -> seenCount
    }

    fun visible(filter: ListFilter): List<ListProgressEntry> = entries.filter(filter::accepts)

    companion object {
        /**
         * Ranks follow list order (position, then id for ties), counted over the whole list,
         * so a removed title leaves no gap and a filtered view keeps the original numbers.
         */
        fun of(list: CustomList, marks: Map<Pair<MediaType, Long>, ViewerMark>): ListProgress = ListProgress(
            listId = list.id!!,
            ownerId = list.userId,
            entries = list.items
                .sortedWith(compareBy<CustomListItem> { it.position }.thenBy { it.id?.value ?: Long.MAX_VALUE })
                .mapIndexed { index, item ->
                    val mark = marks[item.mediaType to item.mediaId] ?: ViewerMark.NONE
                    ListProgressEntry(
                        itemId = item.id!!,
                        mediaType = item.mediaType,
                        mediaId = item.mediaId,
                        rank = index + 1,
                        status = mark.status,
                        score = mark.score,
                        seen = mark.seen(item.mediaType),
                        rated = mark.rated,
                    )
                },
        )
    }
}
