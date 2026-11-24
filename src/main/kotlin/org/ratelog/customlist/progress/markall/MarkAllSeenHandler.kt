package org.ratelog.customlist.progress.markall

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.progress.GetListProgress
import org.ratelog.customlist.progress.GetListProgressError
import org.ratelog.customlist.progress.GetListProgressHandler
import org.ratelog.user.User
import org.ratelog.watchlist.seen.ToggleSeen
import org.ratelog.watchlist.seen.ToggleSeenError
import org.ratelog.watchlist.seen.ToggleSeenHandler
import org.ratelog.watchlist.seen.ToggleSeenOutcome
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class MarkAllSeen(
    val listId: CustomList.Id,
    val userId: User.Id,
)

data class MarkAllSeenResult(
    /** Titles that became seen (status Watched). */
    val marked: Int,
    /** Unseen titles that aren't out yet, left alone. */
    val notOut: Int,
)

/**
 * "Mark all seen" in a list's tick-off mode (user request, 2026-11-23): every title in the list the viewer hasn't seen becomes
 * seen, through the same [ToggleSeenHandler] as the eye on each poster, so the rules are identical: titles not out yet are
 * refused (counted in [MarkAllSeenResult.notOut]), and nothing already seen (rated or Watched) is touched. Works on any list the
 * viewer can open (progress is always the viewer's own), like the tick-off mode itself.
 */
@Service
class MarkAllSeenHandler(
    private val getListProgressHandler: GetListProgressHandler,
    private val toggleSeenHandler: ToggleSeenHandler,
) {
    @Transactional
    fun handle(command: MarkAllSeen): Either<GetListProgressError, MarkAllSeenResult> = either {
        val progress = getListProgressHandler.handle(GetListProgress(command.listId, command.userId)).bind()
        var marked = 0
        var notOut = 0
        progress.entries.filter { !it.seen }.forEach { entry ->
            toggleSeenHandler.handle(ToggleSeen(command.userId, entry.mediaType, entry.mediaId)).fold(
                { error -> if (error == ToggleSeenError.NotReleased) notOut++ },
                { result -> if (result.outcome == ToggleSeenOutcome.MARKED_SEEN) marked++ },
            )
        }
        MarkAllSeenResult(marked, notOut)
    }
}
