package org.ratelog.feed.community.people.web

import org.ratelog.annotations.CurrentUser
import org.ratelog.feed.community.CommunityController
import org.ratelog.feed.community.people.CommunityPeople
import org.ratelog.feed.community.people.ListCommunityPeople
import org.ratelog.feed.community.people.ListCommunityPeopleHandler
import org.ratelog.user.User
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

data class CommunityPersonView(
    val id: Long,
    val username: String,
    val initial: String,
    val ratingCount: Long,
    val following: Boolean,
)

data class CommunityPeopleView(
    val people: List<CommunityPersonView>,
    val followingCount: Int,
) {
    val total: Int get() = people.size

    companion object {
        fun of(result: CommunityPeople) = CommunityPeopleView(
            people = result.people.map {
                CommunityPersonView(
                    id = it.id.value,
                    username = it.username.value,
                    initial = it.username.value.take(1).uppercase(),
                    ratingCount = it.ratingCount,
                    following = it.following,
                )
            },
            followingCount = result.followingCount,
        )
    }
}

/*
 * Adds the People directory (`communityPeople`) to /community without touching the upstream controller.
 * Rendered by templates/fragments/community-people.html; each row's button is fragments/follow-button.html.
 */
@ControllerAdvice(assignableTypes = [CommunityController::class])
class CommunityPeopleAdvice(
    private val handler: ListCommunityPeopleHandler,
) {
    @ModelAttribute
    fun addPeople(@CurrentUser user: User?, model: Model) {
        val viewerId = user?.id ?: return
        handler.handle(ListCommunityPeople(viewerId))
            .onRight { model.addAttribute("communityPeople", CommunityPeopleView.of(it)) }
    }
}
