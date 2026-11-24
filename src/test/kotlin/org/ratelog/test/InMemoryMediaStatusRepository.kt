package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.WatchlistEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class InMemoryMediaStatusRepository : MediaStatusRepository {
    private data class Key(val userId: User.Id, val mediaType: MediaType, val mediaId: Long)

    private val store = ConcurrentHashMap<Key, MediaStatus>()
    /** Insertion order breaks ties between equal timestamps, like the `id DESC` in the real query. */
    private val order = ConcurrentHashMap<Key, Long>()
    private val sequence = AtomicLong(1)
    private val watchlist = ConcurrentHashMap<Key, WatchlistEntry>()
    private val watchlistOrder = ConcurrentHashMap<Key, Long>()

    override fun find(userId: User.Id, mediaType: MediaType, mediaId: Long): MediaStatus? =
        store[Key(userId, mediaType, mediaId)]

    override fun findByUser(userId: User.Id, status: WatchStatus?, mediaType: MediaType?): List<MediaStatus> =
        store.entries
            .filter { (key, value) ->
                key.userId == userId &&
                    (status == null || value.status == status) &&
                    (mediaType == null || key.mediaType == mediaType)
            }
            .sortedWith(compareByDescending<Map.Entry<Key, MediaStatus>> { it.value.updatedAtEpochMs }.thenByDescending { order[it.key] })
            .map { it.value }

    override fun countByStatus(userId: User.Id, mediaType: MediaType?): Map<WatchStatus, Int> =
        store.values
            .filter { it.userId == userId && (mediaType == null || it.mediaType == mediaType) }
            .groupingBy { it.status }
            .eachCount()

    /**
     * Test convenience: saving Want to watch puts the title on the watchlist instead (what tests meant by it before the
     * watchlist had its own table, fork 2026-11-24). The real repository refuses it (and the V1141 CHECK constraint), so
     * production code never relies on this: the handler tests check the watchlist through [findWatchlistEntry].
     */
    override fun save(mediaStatus: MediaStatus) {
        if (mediaStatus.status == WatchStatus.WANT_TO_WATCH) {
            addToWatchlist(WatchlistEntry(mediaStatus.userId, mediaStatus.mediaType, mediaStatus.mediaId, mediaStatus.updatedAtEpochMs))
            return
        }
        val key = Key(mediaStatus.userId, mediaStatus.mediaType, mediaStatus.mediaId)
        store[key] = mediaStatus
        order.putIfAbsent(key, sequence.getAndIncrement())
    }

    /** Test helper: the effective status (WatchStatus.effective): Want to watch for a title only on the watchlist. */
    fun effective(userId: User.Id, mediaType: MediaType, mediaId: Long): WatchStatus? =
        WatchStatus.effective(find(userId, mediaType, mediaId)?.status, findWatchlistEntry(userId, mediaType, mediaId) != null)

    override fun delete(userId: User.Id, mediaType: MediaType, mediaId: Long): Boolean {
        val key = Key(userId, mediaType, mediaId)
        order.remove(key)
        return store.remove(key) != null
    }

    override fun findWatchlistEntry(userId: User.Id, mediaType: MediaType, mediaId: Long): WatchlistEntry? =
        watchlist[Key(userId, mediaType, mediaId)]

    override fun findWatchlist(userId: User.Id, mediaType: MediaType?): List<WatchlistEntry> =
        watchlist.entries
            .filter { (key, _) -> key.userId == userId && (mediaType == null || key.mediaType == mediaType) }
            .sortedWith(compareByDescending<Map.Entry<Key, WatchlistEntry>> { it.value.addedAtEpochMs }.thenByDescending { watchlistOrder[it.key] })
            .map { it.value }

    override fun countWatchlist(userId: User.Id, mediaType: MediaType?): Int = findWatchlist(userId, mediaType).size

    override fun addToWatchlist(entry: WatchlistEntry) {
        val key = Key(entry.userId, entry.mediaType, entry.mediaId)
        val existing = watchlist[key]
        watchlist[key] = existing?.copy(rewatch = entry.rewatch) ?: entry
        watchlistOrder.putIfAbsent(key, sequence.getAndIncrement())
    }

    override fun removeFromWatchlist(userId: User.Id, mediaType: MediaType, mediaId: Long): Boolean {
        val key = Key(userId, mediaType, mediaId)
        watchlistOrder.remove(key)
        return watchlist.remove(key) != null
    }
}
