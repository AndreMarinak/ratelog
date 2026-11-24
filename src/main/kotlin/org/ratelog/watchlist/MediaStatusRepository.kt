package org.ratelog.watchlist

import org.ratelog.MediaType
import org.ratelog.user.User

/**
 * A user's own relation to titles: the progress status (`media_statuses`) and, fork 2026-11-24, the watchlist
 * (`watchlist_entries`, see [WatchlistEntry]). Two tables, one source of truth each; one repository so every handler
 * that changes one can keep the other consistent.
 */
interface MediaStatusRepository {
    fun find(userId: User.Id, mediaType: MediaType, mediaId: Long): MediaStatus?

    /** Most recently updated first. A null filter means "any". */
    fun findByUser(userId: User.Id, status: WatchStatus?, mediaType: MediaType?): List<MediaStatus>

    /** Statuses with no titles are left out. Never has [WatchStatus.WANT_TO_WATCH] (see [countWatchlist]). */
    fun countByStatus(userId: User.Id, mediaType: MediaType?): Map<WatchStatus, Int>

    /** Insert, or replace the user's existing status for that title. Never [WatchStatus.WANT_TO_WATCH]: use [addToWatchlist]. */
    fun save(mediaStatus: MediaStatus)

    /** @return true if there was a status to remove. The watchlist entry (if any) stays. */
    fun delete(userId: User.Id, mediaType: MediaType, mediaId: Long): Boolean

    // Fork (2026-11-24): the watchlist, independent of the status.

    fun findWatchlistEntry(userId: User.Id, mediaType: MediaType, mediaId: Long): WatchlistEntry?

    /** Most recently added first. A null type means movies and TV. */
    fun findWatchlist(userId: User.Id, mediaType: MediaType?): List<WatchlistEntry>

    fun countWatchlist(userId: User.Id, mediaType: MediaType?): Int

    /** Adds it, or (already there) keeps its date and updates [WatchlistEntry.rewatch]. */
    fun addToWatchlist(entry: WatchlistEntry)

    /** @return true if it was on the watchlist. The status (if any) stays. */
    fun removeFromWatchlist(userId: User.Id, mediaType: MediaType, mediaId: Long): Boolean
}
