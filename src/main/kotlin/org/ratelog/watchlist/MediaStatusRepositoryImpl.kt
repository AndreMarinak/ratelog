package org.ratelog.watchlist

import org.ratelog.MediaType
import org.ratelog.user.User
import org.springframework.stereotype.Repository
import kotlin.jvm.optionals.getOrNull

@Repository
class MediaStatusRepositoryImpl(
    private val mediaStatusDAO: MediaStatusDAO,
) : MediaStatusRepository {

    override fun find(userId: User.Id, mediaType: MediaType, mediaId: Long): MediaStatus? =
        mediaStatusDAO.findOne(userId.value, mediaType.name, mediaId).getOrNull()?.toDomain()

    override fun findByUser(userId: User.Id, status: WatchStatus?, mediaType: MediaType?): List<MediaStatus> =
        mediaStatusDAO.findByUser(userId.value, status?.name, mediaType?.name).map { it.toDomain() }

    override fun countByStatus(userId: User.Id, mediaType: MediaType?): Map<WatchStatus, Int> =
        mediaStatusDAO.countByStatus(userId.value, mediaType?.name)
            .associate { WatchStatus.valueOf(it.status) to it.total.toInt() }

    override fun save(mediaStatus: MediaStatus) {
        mediaStatusDAO.upsert(
            userId = mediaStatus.userId.value,
            mediaType = mediaStatus.mediaType.name,
            mediaId = mediaStatus.mediaId,
            status = mediaStatus.status.name,
            updatedAtEpochMs = mediaStatus.updatedAtEpochMs,
        )
    }

    override fun delete(userId: User.Id, mediaType: MediaType, mediaId: Long): Boolean =
        mediaStatusDAO.deleteOne(userId.value, mediaType.name, mediaId) > 0

    private fun MediaStatusEntity.toDomain() = MediaStatus(
        userId = User.Id(userId),
        mediaType = MediaType.valueOf(mediaType),
        mediaId = mediaId,
        status = WatchStatus.valueOf(status),
        updatedAtEpochMs = updatedAtEpochMs,
    )
}
