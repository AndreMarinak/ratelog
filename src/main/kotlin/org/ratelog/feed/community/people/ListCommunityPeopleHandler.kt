package org.ratelog.feed.community.people

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ListCommunityPeople(val viewerId: User.Id)

data class CommunityPeople(
    val people: List<CommunityPerson>,
) {
    val followingCount: Int get() = people.count { it.following }
}

sealed interface ListCommunityPeopleError {
    data object ViewerNotFound : ListCommunityPeopleError
}

/**
 * Fork: the People directory on /community (user, 2026-11-23: "list every user with a follow button"). Everyone but
 * the viewer, admins included: nothing in the app hides admin accounts (user search and profiles never did).
 */
@Service
class ListCommunityPeopleHandler(
    private val userRepository: UserRepository,
    private val peopleRepository: CommunityPeopleRepository,
) {
    @Transactional(readOnly = true)
    fun handle(query: ListCommunityPeople): Either<ListCommunityPeopleError, CommunityPeople> = either {
        userRepository.findById(query.viewerId) ?: raise(ListCommunityPeopleError.ViewerNotFound)
        CommunityPeople(peopleRepository.findAllExcept(query.viewerId).filter { it.id != query.viewerId })
    }
}
