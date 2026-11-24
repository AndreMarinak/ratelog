package org.ratelog.recommendations.foryou.web

import org.ratelog.MediaType
import org.ratelog.browse.BrowseFilters
import org.ratelog.browse.BrowseTitle
import org.ratelog.browse.discover.BrowseTile
import org.ratelog.browse.web.BrowseBarViews
import org.ratelog.browse.web.DiscoverRowView
import org.ratelog.recommendations.foryou.ForYouPick
import org.springframework.context.MessageSource
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * The skeleton row `templates/fragments/for-you.html :: placeholder` renders before the titles arrive: the heading is
 * real so nothing moves when the row swaps in. [src] is where the page fetches the row from.
 */
data class ForYouPlaceholderView(
    val id: String,
    val src: String,
)

/** Builds "Recommended for you" with Discover's row and tile views (fragments/discover-rows.html :: row). */
@Component
class ForYouViews(
    private val messages: MessageSource,
    private val barViews: BrowseBarViews,
) {
    /** The loaded row; no "More" link (there is no grid of recommendations). */
    fun row(type: MediaType?, picks: List<ForYouPick>, locale: Locale = LocaleContextHolder.getLocale()) = DiscoverRowView(
        id = rowId(type),
        title = messages.getMessage(TITLE_KEY, null, TITLE_KEY, locale) ?: TITLE_KEY,
        moreHref = null,
        srcHref = null,
        tiles = barViews.tiles(picks.map { BrowseTile(it.toBrowseTitle(), mark = null) }, locale),
    )

    private fun ForYouPick.toBrowseTitle() = BrowseTitle(
        tmdbId = title.tmdbId,
        type = title.type,
        title = title.title,
        releaseDate = title.releaseDate,
        posterPath = title.posterPath,
        voteAverage = title.voteAverage,
        voteCount = title.voteCount,
        genreIds = emptyList(),
        originalLanguage = null,
    )

    companion object {
        const val ROW_PATH = "/for-you/row"
        const val TITLE_KEY = "home.row.recommended"

        /** Home's row (both types) is `row-recommended`, like the other home rows; Discover's is `discover-row-recommended`. */
        fun rowId(type: MediaType?): String = if (type == null) "row-recommended" else "discover-row-recommended"

        fun src(type: MediaType?): String =
            if (type == null) ROW_PATH else BrowseFilters.link(ROW_PATH, listOf("type" to BrowseFilters.typeParam(type)))

        fun placeholder(type: MediaType?) = ForYouPlaceholderView(rowId(type), src(type))
    }
}
