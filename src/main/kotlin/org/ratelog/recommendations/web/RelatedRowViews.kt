package org.ratelog.recommendations.web

import org.ratelog.MediaType
import org.ratelog.recommendations.related.RelatedTile
import org.ratelog.recommendations.related.RelatedTitles
import org.ratelog.recommendations.related.SeriesRow
import org.ratelog.watchlist.seen.ReleaseRules
import org.ratelog.watchlist.seen.TileActionsView
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.util.Locale

/** What `templates/fragments/title-related.html :: rows` renders. No rows: nothing is rendered. */
data class RelatedSectionView(
    val rows: List<RelatedRowView>,
)

/** One row (home.css look): the movie's series, or "More like this". */
data class RelatedRowView(
    /** HTML id: `related-series` or `related-more`. */
    val id: String,
    val title: String,
    /** Series only: "You've seen 2 of 5 · Next: Toy Story 3". */
    val progress: String?,
    val isSeries: Boolean,
    val tiles: List<RelatedTileView>,
)

data class RelatedTileView(
    /** `movie:863`, the same key tile-actions.js uses for every copy of a title. */
    val key: String,
    val href: String,
    val title: String,
    /** Year, or "TBA". */
    val meta: String,
    val posterUrl: String?,
    val posterUrl2x: String?,
    val initial: String,
    /** The title whose page this is: outlined, "You're here", no quick actions (the page's own panel has them). */
    val current: Boolean,
    val seen: Boolean,
    /** The viewer's score, one decimal. */
    val userScore: String?,
    /** Quick actions (not rendered on the current tile) and the Upcoming badge. */
    val actions: TileActionsView,
)

@Component
class RelatedRowViews(
    private val messages: MessageSource,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun section(related: RelatedTitles, locale: Locale = LocaleContextHolder.getLocale()): RelatedSectionView {
        val today = LocalDate.now(clock)
        val rows = buildList {
            related.series?.let { add(seriesRow(it, today, locale)) }
            if (related.moreLikeThis.isNotEmpty()) {
                add(
                    RelatedRowView(
                        id = "related-more",
                        title = message("recs.more.title", locale),
                        progress = null,
                        isSeries = false,
                        tiles = related.moreLikeThis.map { tile(it, today, locale) },
                    )
                )
            }
        }
        return RelatedSectionView(rows)
    }

    private fun seriesRow(series: SeriesRow, today: LocalDate, locale: Locale) = RelatedRowView(
        id = "related-series",
        title = seriesTitle(series.name, locale),
        progress = progress(series.entries, today, locale),
        isSeries = true,
        tiles = series.entries.map { tile(it, today, locale) },
    )

    /**
     * "Part of the Toy Story Collection". TMDB's English names often start with "The" ("The Avengers Collection"),
     * which would read "Part of the The Avengers Collection", so a leading "The " is dropped.
     */
    fun seriesTitle(name: String, locale: Locale): String {
        val clean = name.trim().removePrefix("The ").trim()
        if (clean.isEmpty()) return message("recs.series.title.unnamed", locale)
        return message("recs.series.title", locale, clean)
    }

    /**
     * How far the viewer is through the series, counting entries that are out (upcoming and undated ones can't be
     * seen yet):
     * - nothing seen: "5 films · Start with Toy Story";
     * - some seen: "You've seen 2 of 5 · Next: Toy Story 3" (next = the first unseen one in release order);
     * - all seen: "You've seen all 4", plus "Next: Avengers: Doomsday, Dec 16" when an entry is still to come.
     * Null when nothing in the series is out yet.
     */
    fun progress(entries: List<RelatedTile>, today: LocalDate, locale: Locale): String? {
        val (released, upcoming) = entries.partition { it.isOut(today) }
        if (released.isEmpty()) return null
        val seen = released.count { it.seen }
        val count = when {
            seen == 0 -> message("recs.series.count", locale, released.size)
            seen == released.size && released.size > 1 -> message("recs.series.all", locale, released.size)
            else -> message("recs.series.progress", locale, seen, released.size)
        }
        val firstUnseen = released.firstOrNull { !it.seen }
        val nextUp = upcoming.firstOrNull()
        val next = when {
            seen == 0 -> message("recs.series.start", locale, released.first().title.title)
            firstUnseen != null -> message("recs.series.next", locale, firstUnseen.title.title)
            nextUp?.title?.releaseDate != null ->
                message("recs.series.next.dated", locale, nextUp.title.title, ReleaseRules.shortDate(nextUp.title.releaseDate, today, locale))
            nextUp != null -> message("recs.series.next", locale, nextUp.title.title)
            else -> null
        }
        return listOfNotNull(count, next).joinToString(" · ")
    }

    private fun tile(tile: RelatedTile, today: LocalDate, locale: Locale): RelatedTileView {
        val t = tile.title
        val type = if (t.type == MediaType.movie) "movie" else "tv"
        return RelatedTileView(
            key = "$type:${t.tmdbId}",
            href = if (t.type == MediaType.movie) "/movie/${t.tmdbId}" else "/tv/${t.tmdbId}",
            title = t.title,
            meta = t.year?.toString() ?: message("recs.year.tba", locale),
            posterUrl = t.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
            posterUrl2x = t.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            initial = t.title.trim().take(1).uppercase().ifEmpty { "?" },
            current = tile.current,
            seen = tile.seen,
            userScore = tile.mark?.score?.let { String.format(Locale.ROOT, "%.1f", it) },
            actions = TileActionsView.of(t.tmdbId, t.type, tile.mark?.status, tile.mark?.rated == true, t.releaseDate, today, locale, score = tile.mark?.score, onWatchlist = tile.mark?.onWatchlist == true),
        )
    }

    private fun message(key: String, locale: Locale, vararg args: Any): String =
        messages.getMessage(key, if (args.isEmpty()) null else arrayOf(*args), key, locale) ?: key
}
