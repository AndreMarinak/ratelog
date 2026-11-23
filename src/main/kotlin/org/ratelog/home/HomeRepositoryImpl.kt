package org.ratelog.home

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.toLocalDate
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Repository

@Repository
class HomeRepositoryImpl(
    private val homeDAO: HomeDAO,
) : HomeRepository {

    override fun findByStatus(userId: User.Id, status: WatchStatus, lang: Lang, limit: Int): List<HomeTitle> =
        homeDAO.findByStatus(userId.value, status.name, lang.name, limit).map { it.toDomain() }

    override fun findRecentlyRated(userId: User.Id, lang: Lang, limit: Int): List<HomeTitle> =
        homeDAO.findRecentlyRated(userId.value, lang.name, limit).map { it.toDomain() }

    override fun findMarks(userId: User.Id, mediaType: MediaType, tmdbIds: Collection<Int>): Map<Int, TitleMark> {
        if (tmdbIds.isEmpty()) return emptyMap()
        val ids = tmdbIds.distinct()
        val rows = when (mediaType) {
            MediaType.movie -> homeDAO.findMovieMarks(userId.value, ids)
            MediaType.tvshow -> homeDAO.findTvMarks(userId.value, ids)
        }
        return rows
            .map { it.tmdbId to TitleMark(it.status?.let(WatchStatus::valueOf), it.score) }
            .filter { (_, mark) -> mark.status != null || mark.score != null }
            .toMap()
    }

    private fun HomeTitleRow.toDomain() = HomeTitle(
        tmdbId = tmdbId,
        mediaType = MediaType.valueOf(mediaType),
        title = title,
        year = releaseYear,
        posterPath = posterPath,
        status = status?.let(WatchStatus::valueOf),
        score = score,
        releaseDate = releaseDate?.take(10)?.let { runCatching { it.toLocalDate() }.getOrNull() },
    )
}
