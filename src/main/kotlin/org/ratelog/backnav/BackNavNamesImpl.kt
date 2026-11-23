package org.ratelog.backnav

import org.ratelog.customlist.CustomListDAO
import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.springframework.stereotype.Component
import kotlin.jvm.optionals.getOrNull

/** One primary-key read each, and only when a title page was opened from a list or a profile. */
@Component
class BackNavNamesImpl(
    private val customListDAO: CustomListDAO,
    private val userRepository: UserRepository,
) : BackNavNames {
    override fun list(listId: Long): BackNavList? =
        customListDAO.findById(listId).getOrNull()?.let { BackNavList(User.Id(it.userId), it.isPublic, it.name) }

    override fun username(userId: Long): String? = userRepository.findById(User.Id(userId))?.username?.value
}
