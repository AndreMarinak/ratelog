package org.ratelog.feed.community.people

import org.ratelog.Username
import org.ratelog.user.User
import org.springframework.stereotype.Repository

data class CommunityPerson(
    val id: User.Id,
    val username: Username,
    val ratingCount: Long,
    val following: Boolean,
)

interface CommunityPeopleRepository {
    /** Every account except [viewerId], A-Z, with its rating count and whether [viewerId] follows it. */
    fun findAllExcept(viewerId: User.Id): List<CommunityPerson>
}

@Repository
class CommunityPeopleRepositoryImpl(
    private val dao: CommunityPeopleDAO,
) : CommunityPeopleRepository {
    override fun findAllExcept(viewerId: User.Id): List<CommunityPerson> =
        dao.findAllExcept(viewerId.value).map {
            CommunityPerson(User.Id(it.id), Username(it.username), it.ratingCount, it.following)
        }
}
