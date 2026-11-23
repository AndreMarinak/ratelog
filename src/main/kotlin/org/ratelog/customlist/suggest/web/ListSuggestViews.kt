package org.ratelog.customlist.suggest.web

import org.ratelog.MediaType
import org.ratelog.customlist.suggest.ListSuggestion
import org.ratelog.customlist.suggest.ListSuggestions
import org.ratelog.customlist.suggest.SuggestionReason
import org.ratelog.customlist.suggest.TitleKey
import org.ratelog.watchlist.seen.TileActionsView
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.util.Locale

/** What `templates/fragments/list-suggest.html :: row` renders: "Suggested for this list" and its batch of tiles. */
data class ListSuggestView(
    val listId: Long,
    /** "Based on the 10 titles in this list" / "Based on 10 of the 25 titles in this list". */
    val basis: String,
    val tiles: List<SuggestTileView>,
    /** "More suggestions": the pool has titles this batch didn't show. */
    val hasMore: Boolean,
    /** Nothing new is left, but the row skipped titles: "Start over" shows the first batch again. */
    val canRestart: Boolean,
    /** Every suggestion was already shown or hidden: "That's all for now" instead of tiles. */
    val exhausted: Boolean,
    /** Where "+ Add" posts (AddSuggestionController). */
    val addAction: String,
    /** Sent as `back`: a plain form post returns to the list. */
    val back: String,
)

data class SuggestTileView(
    /** `movie:862`, the key tile-actions.js keeps every copy of a title in sync with. */
    val key: String,
    /** `m862`, what the row sends back in `skip`. */
    val skipKey: String,
    val tmdbId: Int,
    /** `movie` / `tv`, what the add form posts. */
    val type: String,
    val href: String,
    val title: String,
    /** Year, or "TBA". */
    val meta: String,
    val posterUrl: String?,
    val posterUrl2x: String?,
    val initial: String,
    /** The viewer's score, one decimal. */
    val userScore: String?,
    /** An entry of a series the list has titles of. */
    val series: Boolean,
    /** Seen eye, watchlist bookmark and the Upcoming badge. */
    val actions: TileActionsView,
)

@Component
class ListSuggestViews(
    private val messages: MessageSource,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /** Null when there is nothing to show at all (an empty list, or no recommendations): the page drops the row. */
    fun row(suggestions: ListSuggestions, locale: Locale = LocaleContextHolder.getLocale()): ListSuggestView? {
        if (suggestions.exhausted && !suggestions.skipped) return null
        val today = LocalDate.now(clock)
        val listId = suggestions.listId.value
        return ListSuggestView(
            listId = listId,
            basis = basis(suggestions.basedOn, suggestions.listSize, locale),
            tiles = suggestions.suggestions.map { tile(it, today, locale) },
            hasMore = suggestions.remaining > 0,
            canRestart = suggestions.remaining == 0 && suggestions.skipped,
            exhausted = suggestions.exhausted,
            addAction = "/list/$listId/suggestions/add",
            back = "/list/$listId",
        )
    }

    fun basis(basedOn: Int, listSize: Int, locale: Locale): String =
        if (basedOn >= listSize) message("list.suggest.basis", locale, listSize)
        else message("list.suggest.basis.some", locale, basedOn, listSize)

    private fun tile(suggestion: ListSuggestion, today: LocalDate, locale: Locale): SuggestTileView {
        val t = suggestion.title
        val key = TitleKey(t.type, t.tmdbId)
        return SuggestTileView(
            key = key.tileKey,
            skipKey = key.compact,
            tmdbId = t.tmdbId,
            type = if (t.type == MediaType.movie) "movie" else "tv",
            href = if (t.type == MediaType.movie) "/movie/${t.tmdbId}" else "/tv/${t.tmdbId}",
            title = t.title,
            meta = t.year?.toString() ?: message("recs.year.tba", locale),
            posterUrl = t.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
            posterUrl2x = t.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
            initial = t.title.trim().take(1).uppercase().ifEmpty { "?" },
            userScore = suggestion.mark?.score?.let { String.format(Locale.ROOT, "%.1f", it) },
            series = suggestion.reason == SuggestionReason.SERIES,
            actions = TileActionsView.of(t.tmdbId, t.type, suggestion.mark?.status, suggestion.mark?.rated == true, t.releaseDate, today, locale, score = suggestion.mark?.score),
        )
    }

    private fun message(key: String, locale: Locale, vararg args: Any): String =
        messages.getMessage(key, if (args.isEmpty()) null else arrayOf(*args), key, locale) ?: key
}
