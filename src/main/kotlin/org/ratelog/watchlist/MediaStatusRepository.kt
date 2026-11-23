package org.ratelog.watchlist

import org.ratelog.MediaType
import org.ratelog.user.User

interface MediaStatusRepository {
    fun find(userId: User.Id, mediaType: MediaType, mediaId: Long): MediaStatus?

    /** Most recently updated first. A null filter means "any". */
    fun findByUser(userId: User.Id, status: WatchStatus?, mediaType: MediaType?): List<MediaStatus>

    /** Statuses with no titles are left out. */
    fun countByStatus(userId: User.Id, mediaType: MediaType?): Map<WatchStatus, Int>

    /** Insert, or replace the user's existing status for that title. */
    fun save(mediaStatus: MediaStatus)

    /** @return true if there was a status to remove. */
    fun delete(userId: User.Id, mediaType: MediaType, mediaId: Long): Boolean
}
