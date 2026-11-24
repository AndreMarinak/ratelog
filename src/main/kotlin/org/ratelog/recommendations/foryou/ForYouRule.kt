package org.ratelog.recommendations.foryou

import org.ratelog.MediaType
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.watchlist.seen.ReleaseRules
import java.time.LocalDate

/** A recommended title and the rated title it was recommended for (the first seed that brought it in). */
data class ForYouPick(
    val title: RelatedTitle,
    val because: ForYouSeed,
)

/**
 * The "Recommended for you" rule (user decision 2026-11-23: one row "based on your ratings"), kept pure so it can be
 * tested without TMDB or a database:
 *
 * 1. **Seeds**: the viewer's titles rated [HIGH_SCORE]+, newest rating first; with fewer than [MIN_HIGH_SEEDS] of
 *    those, their best-rated titles instead. At most [MAX_SEEDS]. When the row mixes movies and TV (Home), each type
 *    that has a candidate gets at least one seed.
 * 2. **Merge**: each seed's TMDB recommendations, taken round-robin (1st of every seed, then 2nd, ...), so no single
 *    title dominates; a title recommended by two seeds appears once, for the first.
 * 3. **Filter**: posterless titles, little-known released ones (under [MIN_VOTES] TMDB votes) and the seeds are left
 *    out here; the handler then drops whatever the viewer has rated or given any status (see [GetForYouHandler]).
 */
object ForYouRule {
    const val HIGH_SCORE = 8.0
    const val MIN_HIGH_SEEDS = 3
    const val MAX_SEEDS = 5

    /** Same floor as "More like this" on title pages. */
    const val MIN_VOTES = 10

    /** Merged candidates kept per viewer (before their own titles are dropped); rows show 20, the API up to 60. */
    const val MAX_CANDIDATES = 120

    private val NEWEST: Comparator<ForYouSeed> = compareByDescending<ForYouSeed> { it.ratedAtEpochMs }.thenByDescending { it.tmdbId }
    private val BEST: Comparator<ForYouSeed> = compareByDescending<ForYouSeed> { it.score }.then(NEWEST)

    /**
     * [high]: per type, up to [MAX_SEEDS] titles rated [HIGH_SCORE]+; [top]: per type, the best-rated titles (only
     * read when [high] has fewer than [MIN_HIGH_SEEDS] in all).
     */
    fun pickSeeds(
        high: Map<MediaType, List<ForYouSeed>>,
        top: () -> Map<MediaType, List<ForYouSeed>>,
    ): List<ForYouSeed> =
        if (high.values.sumOf { it.size } >= MIN_HIGH_SEEDS) mix(high, NEWEST) else mix(top(), BEST)

    /** The first [MAX_SEEDS] by [order], swapping the last one for another type's best when that type has none. */
    private fun mix(byType: Map<MediaType, List<ForYouSeed>>, order: Comparator<ForYouSeed>): List<ForYouSeed> {
        val picked = byType.values.flatten().distinctBy { it.type to it.tmdbId }.sortedWith(order).take(MAX_SEEDS).toMutableList()
        byType.forEach { (type, seeds) ->
            if (seeds.isEmpty() || picked.any { it.type == type }) return@forEach
            val best = seeds.sortedWith(order).first()
            // Replace the lowest pick of a type that has more than one, so no type loses its only seed.
            val index = picked.indices.lastOrNull { i -> picked.count { it.type == picked[i].type } > 1 } ?: return@forEach
            picked[index] = best
        }
        return picked.sortedWith(order)
    }

    /** Round-robin over each seed's recommendations (in seed order), de-duplicated, at most [MAX_CANDIDATES]. */
    fun merge(recommendations: List<Pair<ForYouSeed, List<RelatedTitle>>>, today: LocalDate): List<ForYouPick> {
        val seeds = recommendations.map { it.first.type to it.first.tmdbId }.toSet()
        val lists = recommendations.map { (seed, titles) -> seed to titles.filter { isCandidate(it, today) && (it.type to it.tmdbId) !in seeds } }
        val taken = HashSet<Pair<MediaType, Int>>()
        val merged = ArrayList<ForYouPick>()
        val longest = lists.maxOfOrNull { it.second.size } ?: 0
        for (i in 0 until longest) {
            for ((seed, titles) in lists) {
                val title = titles.getOrNull(i) ?: continue
                val key = title.type to title.tmdbId
                if (!taken.add(key)) continue
                merged += ForYouPick(title, seed)
                if (merged.size >= MAX_CANDIDATES) return merged
            }
        }
        return merged
    }

    /** A poster, and either enough TMDB votes or not out yet (upcoming titles have no votes). */
    fun isCandidate(title: RelatedTitle, today: LocalDate): Boolean =
        title.posterPath != null && (title.voteCount >= MIN_VOTES || ReleaseRules.isUpcoming(title.releaseDate, today))
}
