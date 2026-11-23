package org.ratelog.customlist.official.mark

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListRepository
import org.ratelog.customlist.official.OfficialListRepository
import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

sealed interface SetListOfficialError {
    data object ListNotFound : SetListOfficialError
    /** Only ADMIN / SUPERADMIN accounts can mark or un-mark lists, on any list. */
    data object NotAdmin : SetListOfficialError
}

data class SetListOfficial(
    val listId: CustomList.Id,
    val actorId: User.Id,
    val official: Boolean,
)

data class ListOfficialChange(
    val listId: CustomList.Id,
    val official: Boolean,
    /** False when the list already was (or wasn't) official. */
    val changed: Boolean,
    /** Marking made a private list public. */
    val madePublic: Boolean,
)

/**
 * Marks a list official (which also makes it public, so every visibility check just works) or un-marks it
 * (which leaves it public; the owner can make it private again). The actor's role is read from the database,
 * not the session, so a demoted admin's open session can't keep doing it.
 */
@Service
class SetListOfficialHandler(
    private val customListRepository: CustomListRepository,
    private val officialLists: OfficialListRepository,
    private val userRepository: UserRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun handle(command: SetListOfficial): Either<SetListOfficialError, ListOfficialChange> = either {
        val actor = userRepository.findById(command.actorId)
        ensure(actor != null && actor.role.isAdminLike) { SetListOfficialError.NotAdmin }
        val list = customListRepository.findById(command.listId) ?: raise(SetListOfficialError.ListNotFound)

        if (command.official) {
            val madePublic = !list.isPublic
            if (madePublic) customListRepository.save(list.copy(isPublic = true))
            val changed = officialLists.mark(command.listId, clock.millis())
            ListOfficialChange(command.listId, official = true, changed = changed, madePublic = madePublic)
        } else {
            val changed = officialLists.unmark(command.listId)
            ListOfficialChange(command.listId, official = false, changed = changed, madePublic = false)
        }
    }
}
