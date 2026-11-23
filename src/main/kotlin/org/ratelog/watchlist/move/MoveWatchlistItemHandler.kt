package org.ratelog.watchlist.move

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.MediaType
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatus
import org.ratelog.watchlist.setstatus.SetWatchStatusError
import org.ratelog.watchlist.setstatus.SetWatchStatusHandler
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** Where a watchlist tile's quick action moves the title. `remove` clears the status. */
enum class WatchlistMove(val param: String, val status: WatchStatus?) {
    SEEN("seen", WatchStatus.WATCHED),
    WATCHING("watching", WatchStatus.WATCHING),
    DROPPED("dropped", WatchStatus.DROPPED),
    TO_WATCH("want_to_watch", WatchStatus.WANT_TO_WATCH),
    REMOVE("remove", null);

    companion object {
        /** `seen`, `watching`, `dropped`, `want_to_watch`, `remove`, or any status name WatchStatus.parse accepts. */
        fun parse(value: String?): WatchlistMove? {
            val key = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            entries.find { it.param == key }?.let { return it }
            if (key == "none" || key == "clear") return REMOVE
            val status = WatchStatus.parse(key) ?: return null
            return entries.find { it.status == status }
        }

        /** The move that puts a title back to [status] (Undo). */
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

data class MoveWatchlistItemResult(
    val previous: WatchStatus?,
    val current: WatchStatus?,
) {
    val changed: Boolean get() = previous != current
}

/** The watchlist page's quick actions: remove, mark seen, Watching / Dropped (shows). Plain status changes. */
@Component
class MoveWatchlistItemHandler(
    private val setWatchStatusHandler: SetWatchStatusHandler,
) {
    @Transactional
    fun handle(command: MoveWatchlistItem): Either<SetWatchStatusError, MoveWatchlistItemResult> = either {
        val result = setWatchStatusHandler.handle(
            SetWatchStatus(command.userId, command.mediaType, command.mediaId, command.move.status),
        ).bind()
        MoveWatchlistItemResult(previous = result.previous, current = result.current)
    }
}
