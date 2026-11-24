package org.ratelog.customlist.create

import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.springframework.stereotype.Component

/**
 * How many lists an account may own (fork, 2026-11-23). Every account had upstream's 50; admins curate the site's researched
 * and official lists, so they get more. The role is read from the database, like every other role check since the security
 * hardening.
 */
fun interface ListLimitPolicy {
    fun maxLists(userId: User.Id): Int

    companion object {
        const val USER_MAX = 50
        const val ADMIN_MAX = 200

        /** Tests and callers that don't care about roles. */
        fun fixed(max: Int) = ListLimitPolicy { max }
    }
}

@Component
class RoleListLimitPolicy(
    private val userRepository: UserRepository,
) : ListLimitPolicy {
    override fun maxLists(userId: User.Id): Int =
        if (userRepository.findById(userId)?.role?.isAdminLike == true) ListLimitPolicy.ADMIN_MAX else ListLimitPolicy.USER_MAX
}
