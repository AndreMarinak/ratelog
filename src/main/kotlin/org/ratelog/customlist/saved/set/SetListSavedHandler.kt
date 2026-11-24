package org.ratelog.customlist.saved.set

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListRepository
import org.ratelog.customlist.official.OfficialListRepository
import org.ratelog.customlist.saved.SavedListPolicy
import org.ratelog.customlist.saved.SavedListRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

sealed interface SetListSavedError {
    /** No such list, or someone else's private list (reported as missing, so list ids can't be probed). */
    data object ListNotFound : SetListSavedError

    /** Only official, public lists can be saved for now ([SavedListPolicy]). */
    data object NotSaveable : SetListSavedError
}

/** [saved] is the state asked for (not "toggle"), so a repeated tap or a stale second tab never does the opposite. */
data class SetListSaved(
    val listId: CustomList.Id,
    val userId: User.Id,
    val saved: Boolean,
)

data class SetListSavedResult(
    val listId: CustomList.Id,
    val listName: String,
    val saved: Boolean,
    /** False when the list already was (or wasn't) saved. */
    val changed: Boolean,
)

/**
 * Saves an official list for one user, or un-saves it. Saving needs a list [SavedListPolicy.canSave] allows (official and
 * public, owners and admins included). Un-saving always works on a list the user can still open or that has become
 * private, so a list that stopped being official can still be taken off.
 */
@Service
class SetListSavedHandler(
    private val customListRepository: CustomListRepository,
    private val officialLists: OfficialListRepository,
    private val savedLists: SavedListRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun handle(command: SetListSaved): Either<SetListSavedError, SetListSavedResult> = either {
        val list = customListRepository.findById(command.listId) ?: raise(SetListSavedError.ListNotFound)
        val own = list.userId == command.userId

        val changed = if (command.saved) {
            ensure(own || list.isPublic) { SetListSavedError.ListNotFound }
            ensure(SavedListPolicy.canSave(officialLists.isOfficial(command.listId), list.isPublic)) { SetListSavedError.NotSaveable }
            savedLists.save(command.userId, command.listId, clock.millis())
        } else {
            // A private list of someone else stays "missing", unless the user had saved it while it was shown.
            ensure(own || list.isPublic || savedLists.isSaved(command.userId, command.listId)) { SetListSavedError.ListNotFound }
            savedLists.unsave(command.userId, command.listId)
        }
        SetListSavedResult(command.listId, list.name.value, command.saved, changed)
    }
}
