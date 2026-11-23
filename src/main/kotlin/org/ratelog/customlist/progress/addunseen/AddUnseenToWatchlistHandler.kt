package org.ratelog.customlist.progress.addunseen

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.progress.GetListProgress
import org.ratelog.customlist.progress.GetListProgressError
import org.ratelog.customlist.progress.GetListProgressHandler
import org.ratelog.customlist.progress.ListProgressRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

data class AddUnseenToWatchlist(
    val listId: CustomList.Id,
    val userId: User.Id,
)

/**
 * "Add the N unseen to my watchlist" on a list page (the viewer's own list or someone's public one).
 * Only titles with **no status** that the user hasn't rated become Want to watch;
 * Watching, Watched, Dropped and existing Want to watch are never touched.
 */
@Service
class AddUnseenToWatchlistHandler(
    private val getListProgressHandler: GetListProgressHandler,
    private val listProgressRepository: ListProgressRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** @return how many titles were added. */
    @Transactional
    fun handle(command: AddUnseenToWatchlist): Either<GetListProgressError, Int> = either {
        val progress = getListProgressHandler.handle(GetListProgress(command.listId, command.userId)).bind()
        val addable = progress.entries.filter { it.addable }.map { it.itemId }
        listProgressRepository.addToWatchlist(command.userId, command.listId, addable, clock.millis())
    }
}
