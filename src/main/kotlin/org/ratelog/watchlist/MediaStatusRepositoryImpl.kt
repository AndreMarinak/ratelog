package org.ratelog.watchlist

import org.ratelog.MediaType
import org.ratelog.user.User
import org.springframework.stereotype.Repository
import kotlin.jvm.optionals.getOrNull

@Repository
class MediaStatusRepositoryImpl(
    private val mediaStatusDAO: MediaStatusDAO,
    private val watchlistEntryDAO: WatchlistEntryDAO,
) : MediaStatusRepository {

    override fun find(userId: User.Id, mediaType: MediaType, mediaId: Long): MediaStatus? =
        mediaStatusDAO.findOne(userId.value, mediaType.name, mediaId).getOrNull()?.toDomain()

    override fun findByUser(userId: User.Id, status: WatchStatus?, mediaType: MediaType?): List<MediaStatus> =
        mediaStatusDAO.findByUser(userId.value, status?.name, mediaType?.name).map { it.toDomain() }

    override fun countByStatus(userId: User.Id, mediaType: MediaType?): Map<WatchStatus, Int> =
        mediaStatusDAO.countByStatus(userId.value, mediaType?.name)
            .associate { WatchStatus.valueOf(it.status) to it.total.toInt() }

    override fun save(mediaStatus: MediaStatus) {
        require(mediaStatus.status != WatchStatus.WANT_TO_WATCH) { "Want to watch is a watchlist entry, not a status" }
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

    override fun findWatchlistEntry(userId: User.Id, mediaType: MediaType, mediaId: Long): WatchlistEntry? =
        watchlistEntryDAO.findOne(userId.value, mediaType.name, mediaId).getOrNull()?.toDomain()

    override fun findWatchlist(userId: User.Id, mediaType: MediaType?): List<WatchlistEntry> =
        watchlistEntryDAO.findByUser(userId.value, mediaType?.name).map { it.toDomain() }

    override fun countWatchlist(userId: User.Id, mediaType: MediaType?): Int =
        watchlistEntryDAO.countByUser(userId.value, mediaType?.name).toInt()

    override fun addToWatchlist(entry: WatchlistEntry) {
        watchlistEntryDAO.upsert(entry.userId.value, entry.mediaType.name, entry.mediaId, entry.addedAtEpochMs, entry.rewatch)
    }

    override fun removeFromWatchlist(userId: User.Id, mediaType: MediaType, mediaId: Long): Boolean =
        watchlistEntryDAO.deleteOne(userId.value, mediaType.name, mediaId) > 0

    private fun MediaStatusEntity.toDomain() = MediaStatus(
        userId = User.Id(userId),
        mediaType = MediaType.valueOf(mediaType),
        mediaId = mediaId,
        status = WatchStatus.valueOf(status),
        updatedAtEpochMs = updatedAtEpochMs,
    )

    private fun WatchlistEntryEntity.toDomain() = WatchlistEntry(
        userId = User.Id(userId),
        mediaType = MediaType.valueOf(mediaType),
        mediaId = mediaId,
        addedAtEpochMs = addedAtEpochMs,
        rewatch = rewatch,
    )
}
