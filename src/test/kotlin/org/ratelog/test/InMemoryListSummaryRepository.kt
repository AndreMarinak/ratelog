package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.ListSummary
import org.ratelog.customlist.ListSummaryRepository
import org.ratelog.movie.Movie
import org.ratelog.tvshow.TvShow
import org.ratelog.user.User

/**
 * Summaries computed from the other in-memory fakes, with the same filters as ListSummaryDAO. Owners must be saved users.
 * Without [official], no list is official.
 */
class InMemoryListSummaryRepository(
    private val lists: InMemoryCustomListRepository,
    private val users: InMemoryUserRepository,
    private val movies: InMemoryMovieRepository = InMemoryMovieRepository(),
    private val shows: InMemoryTvShowRepository = InMemoryTvShowRepository(),
    private val official: InMemoryOfficialListRepository? = null,
) : ListSummaryRepository {

    override fun findByOwner(ownerId: User.Id): List<ListSummary> =
        all().filter { it.ownerId == ownerId }

    override fun findPublicByOwners(ownerIds: Collection<User.Id>, limit: Int): List<ListSummary> =
        all().filter { it.isPublic && it.itemCount > 0 && !it.isOfficial && it.ownerId in ownerIds }.take(limit)

    override fun findPublicExcludingOwners(excludedOwnerIds: Collection<User.Id>, limit: Int): List<ListSummary> =
        all().filter { it.isPublic && it.itemCount > 0 && !it.isOfficial && it.ownerId !in excludedOwnerIds }.take(limit)

    override fun findOfficial(): List<ListSummary> =
        all().filter { it.isOfficial && it.isPublic }

    private fun all(): List<ListSummary> =
        users.findAll()
            .flatMap { owner -> lists.findByUserId(owner.id!!).map { it.toSummary(owner) } }
            .sortedWith(compareByDescending<ListSummary> { it.lastActivityEpochMs }.thenByDescending { it.id.value })

    private fun CustomList.toSummary(owner: User) = ListSummary(
        id = id!!,
        ownerId = userId,
        ownerUsername = owner.username.value,
        name = name.value,
        isPublic = isPublic,
        itemCount = items.size,
        coverPosterPaths = items.sortedBy { it.position }.mapNotNull { posterOf(it) }.take(ListSummary.COVER_SIZE),
        lastActivityEpochMs = maxOf(createdAtEpochMs, items.maxOfOrNull { it.addedAtEpochMs } ?: 0L),
        isOfficial = official?.isOfficial(id!!) == true,
    )

    private fun posterOf(item: CustomListItem): String? = when (item.mediaType) {
        MediaType.movie -> movies.findById(Movie.Id(item.mediaId))?.posterPath?.value
        MediaType.tvshow -> shows.findById(TvShow.Id(item.mediaId))?.posterPath?.value
    }
}
