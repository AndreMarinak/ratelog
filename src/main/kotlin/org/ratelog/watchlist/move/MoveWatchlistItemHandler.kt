package org.ratelog.watchlist.move

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatusRepository
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.entry.SetWatchlist
import org.ratelog.watchlist.entry.SetWatchlistHandler
import org.ratelog.watchlist.setstatus.SetWatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatusError
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Where a watchlist tile's quick action moves the title. `remove` clears the status (a title with none, only on the
 * watchlist, leaves the watchlist). Fork (2026-11-24), with the watchlist in its own table:
 * - `unlist`: off the watchlist, the status stays (the To watch tab's Remove, so a rewatch stays seen);
 * - `relist`: back on the watchlist, the status stays (Undo of `unlist`);
 * - `want_to_watch`: on the watchlist with no status (Undo of Seen / Watching from To watch).
 */
enum class WatchlistMove(val param: String, val status: WatchStatus?) {
    SEEN("seen", WatchStatus.WATCHED),
    WATCHING("watching", WatchStatus.WATCHING),
    DROPPED("dropped", WatchStatus.DROPPED),
    TO_WATCH("want_to_watch", WatchStatus.WANT_TO_WATCH),
    REMOVE("remove", null),
    UNLIST("unlist", null),
    RELIST("relist", null);

    companion object {
        /** `seen`, `watching`, `dropped`, `want_to_watch`, `remove`, `unlist`, `relist`, or any status name WatchStatus.parse accepts. */
        fun parse(value: String?): WatchlistMove? {
            val key = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            entries.find { it.param == key }?.let { return it }
            if (key == "none" || key == "clear") return REMOVE
            val status = WatchStatus.parse(key) ?: return null
            return entries.find { it.status == status }
        }

        /** The move that puts a title back to a progress [status] (Undo). */
        fun to(status: WatchStatus?): WatchlistMove = entries.first { it.status == status }
    }
}

data class MoveWatchlistItem(
    val userId: User.Id,
    val mediaType: MediaType,
    /** Local id (movies.id / tv.id). */
    val mediaId: Long,
    val move: WatchlistMove,
)

/** A title's place on the watchlist page: its progress status (a tab) and whether it is on the watchlist (To watch). */
data class WatchlistPlace(val status: WatchStatus?, val onWatchlist: Boolean) {
    /** The tabs it shows in: To watch when on the watchlist, plus its status's tab. A rewatch is in To watch and Seen. */
    val tabs: Set<WatchStatus> get() = setOfNotNull(WatchStatus.WANT_TO_WATCH.takeIf { onWatchlist }, status)
}

data class MoveWatchlistItemResult(
    val before: WatchlistPlace,
    val after: WatchlistPlace,
    val move: WatchlistMove,
) {
    val changed: Boolean get() = before != after

    /** The tab the title left (for the counts and the notice), or null. */
    val previous: WatchStatus? get() = (before.tabs - after.tabs).firstOrNull()

    /** The tab it joined, or null (a rewatch taken off To watch is still in Seen). */
    val current: WatchStatus? get() = (after.tabs - before.tabs).firstOrNull()

    /** The move that restores [before], or null when nothing changed. */
    val undo: WatchlistMove? get() = when {
        !changed -> null
        before.status == after.status && before.onWatchlist -> WatchlistMove.RELIST
        before.status == after.status -> WatchlistMove.UNLIST
        before.onWatchlist && before.status == null -> WatchlistMove.TO_WATCH
        else -> WatchlistMove.to(before.status)
    }
}

/** The watchlist page's quick actions: remove, mark seen, Watching / Dropped (shows), and off / back on the watchlist. */
@Component
class MoveWatchlistItemHandler(
    private val setWatchStatusHandler: SetWatchStatusHandler,
    private val setWatchlistHandler: SetWatchlistHandler,
    private val mediaStatusRepository: MediaStatusRepository,
) {
    @Transactional
    fun handle(command: MoveWatchlistItem): Either<SetWatchStatusError, MoveWatchlistItemResult> = either {
        val before = place(command)
        when (command.move) {
            WatchlistMove.UNLIST, WatchlistMove.RELIST ->
                setWatchlistHandler.handle(SetWatchlist(command.userId, command.mediaType, command.mediaId, on = command.move == WatchlistMove.RELIST))
                    .mapLeft { SetWatchStatusError.MediaNotFound }.bind()

            WatchlistMove.TO_WATCH -> {
                // Exactly "To watch": no status, on the watchlist (what Seen / Watching from To watch undoes to).
                if (before.status != null) setWatchStatusHandler.handle(SetWatchStatus(command.userId, command.mediaType, command.mediaId, null)).bind()
                setWatchStatusHandler.handle(SetWatchStatus(command.userId, command.mediaType, command.mediaId, WatchStatus.WANT_TO_WATCH)).bind()
            }

            WatchlistMove.REMOVE -> {
                if (before.status != null) {
                    // Clears the status; a rewatch stays on the watchlist.
                    mediaStatusRepository.delete(command.userId, command.mediaType, command.mediaId)
                } else {
                    setWatchStatusHandler.handle(SetWatchStatus(command.userId, command.mediaType, command.mediaId, null)).bind()
                }
            }

            else -> setWatchStatusHandler.handle(SetWatchStatus(command.userId, command.mediaType, command.mediaId, command.move.status)).bind()
        }
        MoveWatchlistItemResult(before = before, after = place(command), move = command.move)
    }

    private fun place(command: MoveWatchlistItem) = WatchlistPlace(
        status = mediaStatusRepository.find(command.userId, command.mediaType, command.mediaId)?.status,
        onWatchlist = mediaStatusRepository.findWatchlistEntry(command.userId, command.mediaType, command.mediaId) != null,
    )
}
