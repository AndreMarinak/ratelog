package org.ratelog.rewatch

import org.ratelog.MediaType
import org.ratelog.user.User
import org.springframework.stereotype.Repository
import kotlin.jvm.optionals.getOrNull

/**
 * The rewatch log (fork, 2026-11-24). Plain storage: whether a row counts (the title must be seen) is decided by the
 * readers, see [Rewatch].
 */
interface RewatchRepository {
    /** @return the row with its id. */
    fun add(rewatch: Rewatch): Rewatch

    /** Only the user's own row. */
    fun find(userId: User.Id, id: Long): Rewatch?

    /** @return true if the user had that row. */
    fun delete(userId: User.Id, id: Long): Boolean

    fun summary(userId: User.Id, mediaType: MediaType, mediaId: Long): RewatchSummary =
        summaries(userId, mediaType, listOf(mediaId))[mediaId] ?: RewatchSummary.NONE

    /** Titles without a rewatch are left out. */
    fun summaries(userId: User.Id, mediaType: MediaType, mediaIds: Collection<Long>): Map<Long, RewatchSummary>

    /** Every row of the user, newest first. */
    fun findByUser(userId: User.Id): List<Rewatch>
}

@Repository
class RewatchRepositoryImpl(
    private val dao: RewatchDAO,
) : RewatchRepository {

    override fun add(rewatch: Rewatch): Rewatch =
        dao.save(
            RewatchEntity(
                userId = rewatch.userId.value,
                mediaType = rewatch.mediaType.name,
                mediaId = rewatch.mediaId,
                watchedAtEpochMs = rewatch.watchedAtEpochMs,
            ),
        ).toDomain()

    override fun find(userId: User.Id, id: Long): Rewatch? = dao.findOne(userId.value, id).getOrNull()?.toDomain()

    override fun delete(userId: User.Id, id: Long): Boolean = dao.deleteOne(userId.value, id) > 0

    override fun summaries(userId: User.Id, mediaType: MediaType, mediaIds: Collection<Long>): Map<Long, RewatchSummary> {
        if (mediaIds.isEmpty()) return emptyMap()
        return dao.summaries(userId.value, mediaType.name, mediaIds.distinct())
            .associate { it.mediaId to RewatchSummary(it.total.toInt(), it.lastAtMs) }
    }

    override fun findByUser(userId: User.Id): List<Rewatch> = dao.findByUser(userId.value).map { it.toDomain() }

    private fun RewatchEntity.toDomain() = Rewatch(
        id = id,
        userId = User.Id(userId),
        mediaType = MediaType.valueOf(mediaType),
        mediaId = mediaId,
        watchedAtEpochMs = watchedAtEpochMs,
    )
}
