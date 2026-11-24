package org.ratelog.test

import org.ratelog.feed.community.people.CommunityPeopleRepository
import org.ratelog.feed.community.people.CommunityPerson
import org.ratelog.user.User

/** People directory over [InMemoryUserRepository]: follows come from it, rating counts from [ratingCounts]. */
class InMemoryCommunityPeopleRepository(
    private val users: InMemoryUserRepository,
    val ratingCounts: MutableMap<User.Id, Long> = mutableMapOf(),
) : CommunityPeopleRepository {
    override fun findAllExcept(viewerId: User.Id): List<CommunityPerson> =
        users.findAll()
            .filter { it.id != viewerId }
            .sortedWith(compareBy({ it.username.value.lowercase() }, { it.id!!.value }))
            .map { CommunityPerson(it.id!!, it.username, ratingCounts[it.id] ?: 0, users.isFollowing(viewerId, it.id!!)) }
}
