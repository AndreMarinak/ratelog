package org.ratelog.customlist

import org.ratelog.user.User
import org.springframework.stereotype.Repository

@Repository
class ListSummaryRepositoryImpl(
    private val listSummaryDAO: ListSummaryDAO,
) : ListSummaryRepository {

    override fun findByOwner(ownerId: User.Id): List<ListSummary> =
        withCovers(listSummaryDAO.findByOwner(ownerId.value))

    override fun findPublicByOwners(ownerIds: Collection<User.Id>, limit: Int): List<ListSummary> {
        if (ownerIds.isEmpty() || limit <= 0) return emptyList()
        return withCovers(listSummaryDAO.findPublicByOwners(ownerIds.map { it.value }.distinct(), limit))
    }

    override fun findPublicExcludingOwners(excludedOwnerIds: Collection<User.Id>, limit: Int): List<ListSummary> {
        if (limit <= 0) return emptyList()
        // NOT IN () is invalid SQL; no real user has id 0.
        val excluded = excludedOwnerIds.map { it.value }.distinct().ifEmpty { listOf(0L) }
        return withCovers(listSummaryDAO.findPublicExcludingOwners(excluded, limit))
    }

    private fun withCovers(rows: List<ListSummaryRow>): List<ListSummary> {
        if (rows.isEmpty()) return emptyList()
        val covers = listSummaryDAO.findCovers(rows.map { it.id }, ListSummary.COVER_SIZE)
            .groupBy({ it.listId }, { it.posterPath })
        return rows.map { row ->
            ListSummary(
                id = CustomList.Id(row.id),
                ownerId = User.Id(row.userId),
                ownerUsername = row.username,
                name = row.name,
                isPublic = row.isPublic,
                itemCount = row.itemCount.toInt(),
                coverPosterPaths = covers[row.id].orEmpty(),
                lastActivityEpochMs = row.lastActivity,
            )
        }
    }
}
