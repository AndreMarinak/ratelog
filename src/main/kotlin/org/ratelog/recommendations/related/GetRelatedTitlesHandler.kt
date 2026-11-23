package org.ratelog.recommendations.related

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.browse.BrowseLibraryRepository
import org.ratelog.browse.LibraryMark
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TitleCollection
import org.ratelog.recommendations.TmdbRecommendationsClient
import org.ratelog.recommendations.TmdbRecommendationsError
import org.ratelog.user.User
import org.ratelog.watchlist.seen.ReleaseRules
import org.ratelog.watchlist.seen.SeenState
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

data class GetRelatedTitles(
    val userId: User.Id,
    val type: MediaType,
    val tmdbId: Int,
    /** Metadata language: titles and the collection name. */
    val lang: Lang,
    /** Leave titles the viewer has seen out of "More like this" (the title pages do; the API only when asked). */
    val hideSeen: Boolean = true,
)

data class RelatedTile(
    val title: RelatedTitle,
    /** The viewer's status and score, null when they have neither. */
    val mark: LibraryMark?,
    /** The title whose page this is (only in the series row). */
    val current: Boolean = false,
) {
    /** The tiles' Seen rule (the eye): movies rated or Watched; shows Watched, or rated with no status. */
    val seen: Boolean get() = mark != null && SeenState(mark.status, mark.rated, isTv = title.type == MediaType.tvshow).seen

    /** Out by [today]. Undated series entries are announced sequels, so they don't count as out (nor as upcoming on tiles). */
    fun isOut(today: LocalDate): Boolean = title.releaseDate?.isAfter(today) == false
}

/** A movie's TMDB collection, entries in release order (undated entries last). */
data class SeriesRow(
    val collectionId: Int,
    val name: String,
    val entries: List<RelatedTile>,
)

data class RelatedTitles(
    /** Movies in a TMDB collection with at least two entries; always null for shows. */
    val series: SeriesRow?,
    /** TMDB recommendations without the title itself, its series entries, posterless titles and (by default) seen ones. */
    val moreLikeThis: List<RelatedTile>,
) {
    val isEmpty: Boolean get() = series == null && moreLikeThis.isEmpty()
}

sealed interface GetRelatedTitlesError {
    data object TmdbNotConfigured : GetRelatedTitlesError
    data object NotFound : GetRelatedTitlesError
    data object TmdbUnavailable : GetRelatedTitlesError
}

/**
 * The rows under a title page: the movie's series (TMDB collection) and "More like this" (TMDB recommendations).
 *
 * TMDB calls: a movie's recommendations page 1 also says which collection it belongs to, so a cold cache costs one call
 * (+1 for the collection, +1 more recommendations page when hiding left fewer than [MIN_VISIBLE]); a warm cache none.
 * The viewer's marks are read with one query per recommendations page. A failing collection call only drops the
 * series row; the caller drops everything on an error.
 */
@Service
class GetRelatedTitlesHandler(
    private val client: TmdbRecommendationsClient,
    private val library: BrowseLibraryRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun handle(query: GetRelatedTitles): Either<GetRelatedTitlesError, RelatedTitles> {
        val first = client.recommendations(query.type, query.tmdbId, query.lang).fold({ return it.toError().left() }, { it })
        val today = LocalDate.now(clock)

        val ref = first.collection?.takeIf { query.type == MediaType.movie }
        val collection = ref?.let { r ->
            client.collection(r.id, query.lang).getOrNull()?.let { it.copy(name = it.name.ifBlank { r.name }) }
        }
        val parts = collection?.let(::inReleaseOrder).orEmpty()
        val seriesIds = parts.map { it.tmdbId }.toSet()
        val excluded = seriesIds + query.tmdbId

        val firstPicks = candidates(first.titles, excluded, today)
        val marks = library.findMarks(query.userId, query.type, (parts.map { it.tmdbId } + firstPicks.map { it.tmdbId } + query.tmdbId).distinct())
            .toMutableMap()
        val more = visible(firstPicks, marks, query.hideSeen).toMutableList()

        // Top up from page 2 when the series and seen titles took too many (one extra, cached, call at most).
        if (more.size < MIN_VISIBLE && first.totalPages > 1) {
            client.recommendations(query.type, query.tmdbId, query.lang, page = 2).onRight { second ->
                val taken = excluded + more.map { it.title.tmdbId }
                val extra = candidates(second.titles, taken, today)
                marks += library.findMarks(query.userId, query.type, extra.map { it.tmdbId })
                more += visible(extra, marks, query.hideSeen)
            }
        }

        val series = collection
            ?.takeIf { parts.size >= MIN_SERIES_ENTRIES }
            ?.let { c ->
                SeriesRow(
                    collectionId = c.id,
                    name = c.name,
                    entries = parts.map { RelatedTile(it, marks[it.tmdbId], current = it.tmdbId == query.tmdbId) },
                )
            }
        return RelatedTitles(series, more.take(ROW_LIMIT)).right()
    }

    /** Release order; entries TMDB has no date for (usually announced sequels) go last. */
    private fun inReleaseOrder(collection: TitleCollection): List<RelatedTitle> =
        collection.parts
            .distinctBy { it.tmdbId }
            .sortedWith(compareBy<RelatedTitle> { it.releaseDate == null }.thenBy { it.releaseDate }.thenBy { it.title.lowercase() })
            .take(MAX_SERIES_ENTRIES)

    /**
     * Recommendations worth a tile: not [excluded], with a poster, and not junk: released titles need [MIN_VOTES]
     * votes (upcoming ones have none yet and are kept).
     */
    private fun candidates(titles: List<RelatedTitle>, excluded: Set<Int>, today: LocalDate): List<RelatedTitle> =
        titles
            .filter { it.tmdbId !in excluded && it.posterPath != null }
            .filter { it.voteCount >= MIN_VOTES || ReleaseRules.isUpcoming(it.releaseDate, today) }
            .distinctBy { it.tmdbId }

    private fun visible(titles: List<RelatedTitle>, marks: Map<Int, LibraryMark>, hideSeen: Boolean): List<RelatedTile> =
        titles.map { RelatedTile(it, marks[it.tmdbId]) }.filter { !hideSeen || !it.seen }

    private fun TmdbRecommendationsError.toError(): GetRelatedTitlesError = when (this) {
        TmdbRecommendationsError.ApiKeyMissing -> GetRelatedTitlesError.TmdbNotConfigured
        TmdbRecommendationsError.NotFound -> GetRelatedTitlesError.NotFound
        TmdbRecommendationsError.Unavailable -> GetRelatedTitlesError.TmdbUnavailable
    }

    companion object {
        const val ROW_LIMIT = 20

        /** Below this many tiles in "More like this", the next recommendations page tops the row up. */
        const val MIN_VISIBLE = 12

        /** Released recommendations with fewer TMDB votes are left out (almost nobody has seen them). */
        const val MIN_VOTES = 10

        /** A "collection" of one isn't a series. */
        const val MIN_SERIES_ENTRIES = 2

        /** Long franchises (Bond, Godzilla) stay complete; this only guards against a runaway payload. */
        const val MAX_SERIES_ENTRIES = 60
    }
}
