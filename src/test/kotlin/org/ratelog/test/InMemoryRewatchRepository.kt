package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.rewatch.Rewatch
import org.ratelog.rewatch.RewatchRepository
import org.ratelog.rewatch.RewatchSummary
import org.ratelog.user.User
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The rewatch log (fork, 2026-11-24) in memory: ids count up from 1 like the BIGSERIAL. */
class InMemoryRewatchRepository : RewatchRepository {
    private val rows = ConcurrentHashMap<Long, Rewatch>()
    private val sequence = AtomicLong(1)

    override fun add(rewatch: Rewatch): Rewatch {
        val saved = rewatch.copy(id = sequence.getAndIncrement())
        rows[saved.id!!] = saved
        return saved
    }

    override fun find(userId: User.Id, id: Long): Rewatch? = rows[id]?.takeIf { it.userId == userId }

    override fun delete(userId: User.Id, id: Long): Boolean =
        find(userId, id)?.let { rows.remove(id) } != null

    override fun summaries(userId: User.Id, mediaType: MediaType, mediaIds: Collection<Long>): Map<Long, RewatchSummary> =
        rows.values
            .filter { it.userId == userId && it.mediaType == mediaType && it.mediaId in mediaIds }
            .groupBy { it.mediaId }
            .mapValues { (_, list) -> RewatchSummary(list.size, list.maxOf { it.watchedAtEpochMs }) }

    override fun findByUser(userId: User.Id): List<Rewatch> =
        rows.values.filter { it.userId == userId }
            .sortedWith(compareByDescending<Rewatch> { it.watchedAtEpochMs }.thenByDescending { it.id })

    fun all(): List<Rewatch> = rows.values.sortedBy { it.id }
}
