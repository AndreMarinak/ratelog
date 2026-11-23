package org.ratelog.ratings.unrated

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.user.User
import org.springframework.stereotype.Component

data class GetUnratedSeen(
    val userId: User.Id,
    /** Null for movies and shows together. */
    val type: MediaType?,
    val lang: Lang,
)

data class UnratedSeen(
    /** Newest Seen first. */
    val titles: List<UnratedSeenTitle>,
    val count: UnratedSeenCount,
)

/** The Ratings tab's "To rate" view: what you've marked seen but not rated yet, newest first. */
@Component
class GetUnratedSeenHandler(
    private val repository: UnratedSeenRepository,
) {
    fun handle(query: GetUnratedSeen): UnratedSeen {
        val types = query.type?.let(::listOf) ?: MediaType.entries
        val titles = types
            .flatMap { repository.find(query.userId, it, query.lang, LIMIT) }
            .sortedWith(compareByDescending<UnratedSeenTitle> { it.seenAtEpochMs }.thenByDescending { it.tmdbId })
            .take(LIMIT)
        return UnratedSeen(titles, repository.count(query.userId))
    }

    companion object {
        /** One page; rating them in place shrinks it. */
        const val LIMIT = 200
    }
}
