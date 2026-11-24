package org.ratelog.recommendations.foryou

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.user.User
import org.springframework.stereotype.Repository

/** One of the viewer's rated titles that "Recommended for you" can start from. */
data class ForYouSeed(
    val tmdbId: Int,
    val type: MediaType,
    /** In the metadata language, falling back to English, then the original title. */
    val title: String,
    val score: Double,
    /** When it was rated (a show: its latest season rating). */
    val ratedAtEpochMs: Long,
)

/** Read-only queries over the viewer's ratings for [GetForYouHandler]. */
interface ForYouSeedRepository {
    /** Up to [limit] of the user's titles of [type] scored [minScore] or more, newest rating first. */
    fun findRecentRatedAtLeast(userId: User.Id, type: MediaType, minScore: Double, lang: Lang, limit: Int): List<ForYouSeed>

    /** Up to [limit] of the user's best-scored titles of [type], newest first among equal scores. */
    fun findTopRated(userId: User.Id, type: MediaType, lang: Lang, limit: Int): List<ForYouSeed>

    /** Whether the user has rated any title of [type]. */
    fun hasRatings(userId: User.Id, type: MediaType): Boolean
}

@Repository
class ForYouSeedRepositoryImpl(
    private val dao: ForYouSeedDAO,
) : ForYouSeedRepository {
    override fun findRecentRatedAtLeast(userId: User.Id, type: MediaType, minScore: Double, lang: Lang, limit: Int): List<ForYouSeed> =
        when (type) {
            MediaType.movie -> dao.findRecentMovies(userId.value, minScore, lang.name, limit)
            MediaType.tvshow -> dao.findRecentShows(userId.value, minScore, lang.name, limit)
        }.map { it.toSeed(type) }

    override fun findTopRated(userId: User.Id, type: MediaType, lang: Lang, limit: Int): List<ForYouSeed> =
        when (type) {
            MediaType.movie -> dao.findTopMovies(userId.value, lang.name, limit)
            MediaType.tvshow -> dao.findTopShows(userId.value, lang.name, limit)
        }.map { it.toSeed(type) }

    override fun hasRatings(userId: User.Id, type: MediaType): Boolean = when (type) {
        MediaType.movie -> dao.hasMovieRatings(userId.value)
        MediaType.tvshow -> dao.hasShowRatings(userId.value)
    }

    private fun ForYouSeedRow.toSeed(type: MediaType) = ForYouSeed(tmdbId, type, title, score, ratedAt ?: 0)
}
