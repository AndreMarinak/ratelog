package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class InMemoryMediaStatusRepository : MediaStatusRepository {
    private data class Key(val userId: User.Id, val mediaType: MediaType, val mediaId: Long)

    private val store = ConcurrentHashMap<Key, MediaStatus>()
    /** Insertion order breaks ties between equal timestamps, like the `id DESC` in the real query. */
    private val order = ConcurrentHashMap<Key, Long>()
    private val sequence = AtomicLong(1)

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

    override fun save(mediaStatus: MediaStatus) {
        val key = Key(mediaStatus.userId, mediaStatus.mediaType, mediaStatus.mediaId)
        store[key] = mediaStatus
        order.putIfAbsent(key, sequence.getAndIncrement())
    }

    override fun delete(userId: User.Id, mediaType: MediaType, mediaId: Long): Boolean {
        val key = Key(userId, mediaType, mediaId)
        order.remove(key)
        return store.remove(key) != null
    }
}
