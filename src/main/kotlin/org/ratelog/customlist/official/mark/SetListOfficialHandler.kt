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
    /**
     * The list's visibility, which neither marking nor un-marking changes. A private official list is hidden: only its
     * owner sees it (with an "Official · Hidden" badge) until they make it public.
     */
    val isPublic: Boolean,
)

/**
 * Marks a list official or un-marks it. Neither touches its visibility (2026-11-23; before, marking made the list
 * public): only public official lists are shown to everyone, so a private one is a hidden draft, and an admin marking
 * someone else's private list doesn't publish it. The actor's role is read from the database, not the session, so a
 * demoted admin's open session can't keep doing it.
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

        val changed = if (command.official) officialLists.mark(command.listId, clock.millis()) else officialLists.unmark(command.listId)
        ListOfficialChange(command.listId, official = command.official, changed = changed, isPublic = list.isPublic)
    }
}
