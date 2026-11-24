package org.ratelog.customlist.suggest

import org.ratelog.MediaType
import org.ratelog.recommendations.CollectionRef
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TitleCollection
import org.ratelog.watchlist.seen.ReleaseRules
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.log10

enum class SuggestionReason {
    /** An entry of a movie series (TMDB collection) that titles in the list belong to. */
    SERIES,

    /** In the TMDB recommendations of titles in the list. */
    RECOMMENDED,
}

/** One title of a list's suggestion pool. The pool is in the order the row shows it. */
data class PoolEntry(
    val title: RelatedTitle,
    val reason: SuggestionReason,
    /** How many of the sampled list titles have it in their TMDB recommendations. */
    val recommendedBy: Int,
    val score: Double,
) {
    val key: TitleKey get() = TitleKey(title.type, title.tmdbId)
}

/** What one sampled list title's TMDB recommendations call gave (page 1, TMDB's order). */
data class SeedRecommendations(
    val seed: ListTitle,
    val titles: List<RelatedTitle>,
    /** Movies only: the TMDB collection the seed belongs to (it comes with the same call). */
    val collection: CollectionRef?,
)

/**
 * How "Suggested for this list" picks and orders titles (pure functions; numbers measured on real lists, see
 * docs/decisions/lists.md "Suggested for this list"):
 *
 * 1. **Seeds**: up to [MAX_SEEDS] list titles, so one list costs at most that many recommendation calls. Smaller lists
 *    use every title. Bigger ones split the seeds between movies and shows by their share of the list, and within a
 *    type take [RECENT_PERCENT]% from the most recently added titles (where the list is going) and the rest spread
 *    evenly across the list order, starting at #1 (what it is about). Not half: a list imported in one go has its
 *    bottom ranks as its "newest" titles.
 * 2. **Score**: a candidate collects `1 / (1 + p / 8)` from every seed whose recommendations have it at place `p`
 *    (0-based), so titles several list titles recommend win and TMDB's first picks count about three times its last.
 *    That sum is multiplied by a quality factor (TMDB vote count and average), which keeps rarely seen and poorly rated
 *    titles from riding on one seed's long tail.
 * 3. **Series**: a TMDB collection with [PIN_MEMBERS]+ titles in the list (a franchise list: "Harry Potter in order")
 *    puts its missing entries first, in release order. With one title in the list, its missing entries get
 *    [SERIES_BONUS] (worth one more seed's top pick) instead: Finding Dory for a Pixar list with Finding Nemo.
 * 4. **Filter**: titles already in the list, posterless ones and released ones with fewer than [MIN_VOTES] TMDB votes
 *    are left out (series entries only need a poster).
 * 5. **Media mix**: movies and shows are ranked separately and interleaved by the list's own share, so a list of seven
 *    films and two shows gets about one show in every four or five tiles, and a movie-only list only movies.
 */
object SuggestionScoring {
    const val MAX_SEEDS = 10
    const val RECENT_PERCENT = 30
    const val MAX_COLLECTIONS = 4
    const val MIN_VOTES = 50
    const val SERIES_BONUS = 1.0
    const val PIN_MEMBERS = 2

    private val releaseOrder = compareBy<RelatedTitle> { it.releaseDate == null }
        .thenBy { it.releaseDate }
        .thenBy { it.title.lowercase() }

    /** The list titles whose recommendations are read, in list order. [titles] must be in list order. */
    fun pickSeeds(titles: List<ListTitle>, max: Int = MAX_SEEDS): List<ListTitle> {
        if (titles.size <= max) return titles
        val byType = titles.groupBy { it.type }
        val quotas = quotas(byType.mapValues { it.value.size }, max)
        val picked = byType.flatMap { (type, typed) -> pickWithin(typed, quotas[type] ?: 0) }.toSet()
        return titles.filter { it in picked }
    }

    /** [max] seeds split by each type's share (largest remainder), at least one for every type in the list. */
    internal fun quotas(counts: Map<MediaType, Int>, max: Int): Map<MediaType, Int> {
        val present = counts.filterValues { it > 0 }
        val total = present.values.sum()
        if (total == 0 || max <= 0) return emptyMap()
        val exact = present.mapValues { (_, n) -> n.toDouble() * max / total }
        val quota = present.mapValues { (type, n) -> floor(exact.getValue(type)).toInt().coerceIn(1, n) }.toMutableMap()
        // The minimum of one can overshoot: take back from the biggest share.
        while (quota.values.sum() > max) {
            val biggest = quota.filterValues { it > 1 }.maxByOrNull { it.value }?.key ?: break
            quota[biggest] = quota.getValue(biggest) - 1
        }
        val byRemainder = exact.entries.sortedByDescending { it.value - floor(it.value) }.map { it.key }
        while (quota.values.sum() < max) {
            val next = byRemainder.firstOrNull { quota.getValue(it) < present.getValue(it) } ?: break
            quota[next] = quota.getValue(next) + 1
        }
        return quota
    }

    /** [k] of [typed] (list order): 30% the newest additions, the rest evenly spread over the others. */
    private fun pickWithin(typed: List<ListTitle>, k: Int): List<ListTitle> {
        if (typed.size <= k) return typed
        if (k <= 0) return emptyList()
        val recent = typed.sortedByDescending { it.itemId }.take(k * RECENT_PERCENT / 100).toSet()
        val rest = typed.filter { it !in recent }
        val spread = k - recent.size
        return recent.toList() + (0 until spread).map { i -> rest[i * rest.size / spread] }
    }

    /** The collections worth reading: the ones most seeds belong to, at most [max] (one TMDB call each). */
    fun collectionsToFetch(seeds: List<SeedRecommendations>, max: Int = MAX_COLLECTIONS): List<CollectionRef> =
        seeds.mapNotNull { it.collection }
            .groupBy { it.id }
            .values
            .sortedByDescending { it.size }
            .take(max)
            .map { it.first() }

    /**
     * The whole pool, best first: pinned series entries, then the rest interleaved by media type.
     * [list] is every title of the list (exclusions and the media mix), in list order.
     */
    fun rank(
        seeds: List<SeedRecommendations>,
        collections: List<TitleCollection>,
        list: List<ListTitle>,
        today: LocalDate,
    ): List<PoolEntry> {
        val inList = list.map { it.key }.toSet()
        val found = LinkedHashMap<TitleKey, Candidate>()

        seeds.forEach { seed ->
            seed.titles.distinctBy { it.type to it.tmdbId }.forEachIndexed { place, title ->
                val key = TitleKey(title.type, title.tmdbId)
                if (key in inList) return@forEachIndexed
                val candidate = found.getOrPut(key) { Candidate(title) }
                candidate.consensus += positionWeight(place)
                candidate.seeds += seed.seed.itemId
            }
        }

        val pinned = mutableListOf<RelatedTitle>()
        collections.forEach { collection ->
            val parts = collection.parts.filter { it.type == MediaType.movie }.distinctBy { it.tmdbId }
            val members = parts.count { TitleKey(it.type, it.tmdbId) in inList }
            val missing = parts.filter { TitleKey(it.type, it.tmdbId) !in inList && it.posterPath != null }
            if (members >= PIN_MEMBERS) {
                pinned += missing
            } else {
                missing.forEach { part ->
                    val candidate = found.getOrPut(TitleKey(part.type, part.tmdbId)) { Candidate(part) }
                    if (!candidate.series) candidate.consensus += SERIES_BONUS
                    candidate.series = true
                }
            }
        }

        val first = pinned.distinctBy { it.tmdbId }.sortedWith(releaseOrder).map { title ->
            val candidate = found[TitleKey(title.type, title.tmdbId)]
            PoolEntry(title, SuggestionReason.SERIES, candidate?.seeds?.size ?: 0, candidate?.let { it.consensus * quality(title) } ?: 0.0)
        }
        val firstKeys = first.map { it.key }.toSet()

        val scored = found.filterKeys { it !in firstKeys }.values
            .filter { it.title.posterPath != null }
            .filter { it.title.voteCount >= MIN_VOTES || ReleaseRules.isUpcoming(it.title.releaseDate, today) }
            .map { c ->
                PoolEntry(
                    title = c.title,
                    reason = if (c.series) SuggestionReason.SERIES else SuggestionReason.RECOMMENDED,
                    recommendedBy = c.seeds.size,
                    score = c.consensus * quality(c.title),
                )
            }
        val byType = scored.groupBy { it.title.type }.mapValues { (_, entries) ->
            entries.sortedWith(
                compareByDescending<PoolEntry> { it.score }
                    .thenByDescending { it.title.voteCount }
                    .thenBy { it.title.title.lowercase() }
                    .thenBy { it.title.tmdbId }
            )
        }
        return first + interleave(byType, list.groupingBy { it.type }.eachCount())
    }

    /** What a recommendation at [place] (0-based) in one seed's list is worth: 1 for TMDB's first pick, 0.3 for its 20th. */
    fun positionWeight(place: Int): Double = 1.0 / (1.0 + place / 8.0)

    /**
     * 0.33 to 1: popularity (log of the vote count, full at 10,000 votes) times the TMDB average (full from 7, never
     * below 0.6). Upcoming titles have neither yet and land in the middle.
     */
    fun quality(title: RelatedTitle): Double {
        val popularity = (log10(title.voteCount.coerceAtLeast(1).toDouble()) / 4.0).coerceIn(0.0, 1.0)
        val rating = (((title.voteAverage ?: 6.5) - 4.0) / 3.0).coerceIn(0.6, 1.0)
        return (0.55 + 0.45 * popularity) * rating
    }

    /** Each type's ranking, merged so every prefix keeps the list's movie / show share (a type with titles gets a turn). */
    fun interleave(byType: Map<MediaType, List<PoolEntry>>, shares: Map<MediaType, Int>): List<PoolEntry> {
        val types = byType.filterValues { it.isNotEmpty() }.keys.sortedBy { it.ordinal }
        if (types.size <= 1) return types.flatMap { byType.getValue(it) }
        val weight = types.associateWith { (shares[it] ?: 0).coerceAtLeast(1).toDouble() }
        val totalWeight = weight.values.sum()
        val taken = types.associateWith { 0 }.toMutableMap()
        val total = types.sumOf { byType.getValue(it).size }
        val merged = ArrayList<PoolEntry>(total)
        while (merged.size < total) {
            val next = types
                .filter { taken.getValue(it) < byType.getValue(it).size }
                .maxBy { (merged.size + 1) * weight.getValue(it) / totalWeight - taken.getValue(it) }
            merged += byType.getValue(next)[taken.getValue(next)]
            taken[next] = taken.getValue(next) + 1
        }
        return merged
    }

    private class Candidate(val title: RelatedTitle) {
        var consensus: Double = 0.0
        val seeds: MutableSet<Long> = mutableSetOf()
        var series: Boolean = false
    }
}
