package org.ratelog.watchlist.getwatchlist

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

data class WatchlistTabResponse(
    val labelKey: String,
    val icon: String,
    val count: Int,
    val href: String,
    val active: Boolean,
)

data class WatchlistTypeFilterResponse(
    val labelKey: String,
    val href: String,
    val active: Boolean,
)

data class WatchlistItemResponse(
    val href: String,
    val isMovie: Boolean,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val score: Double?,
)

@Controller
class WatchlistController(
    private val handler: GetWatchlistHandler,
) {

    /** `status`: want_to_watch (default) | watching | watched | dropped. `type`: movie | tv, or omitted for both. */
    @GetMapping("/watchlist")
    fun watchlistPage(
        @CurrentUser user: User,
        @RequestParam("status", required = false) status: String?,
        @RequestParam("type", required = false) type: String?,
        model: Model,
    ): String {
        val selectedStatus = WatchStatus.parse(status) ?: WatchStatus.DEFAULT_TAB
        val selectedType = TYPE_FILTERS[type?.trim()?.lowercase()]

        val watchlist = GetWatchlist(
            userId = user.id!!,
            status = selectedStatus,
            mediaType = selectedType,
            lang = user.metadataLang,
        ).let(handler::handle)

        val typeParam = selectedType?.let(::typeParam)
        model.addAttribute("tabs", WatchStatus.entries.map { s ->
            WatchlistTabResponse(
                labelKey = s.messageKey,
                icon = s.icon,
                count = watchlist.counts[s] ?: 0,
                href = href(s, typeParam),
                active = s == selectedStatus,
            )
        })
        model.addAttribute("typeFilters", listOf(null, MediaType.movie, MediaType.tvshow).map { t ->
            WatchlistTypeFilterResponse(
                labelKey = when (t) {
                    null -> "watchlist.filter.all"
                    MediaType.movie -> "watchlist.filter.movies"
                    MediaType.tvshow -> "watchlist.filter.tv"
                },
                href = href(selectedStatus, t?.let(::typeParam)),
                active = t == selectedType,
            )
        })
        model.addAttribute("items", watchlist.items.map(::toResponse))
        model.addAttribute("emptyKey", "watchlist.empty.${selectedStatus.apiName}")

        return "watchlist"
    }

    private fun toResponse(item: WatchlistItem) = WatchlistItemResponse(
        href = when (item.mediaType) {
            MediaType.movie -> "/movie/${item.tmdbId}"
            MediaType.tvshow -> "/tv/${item.tmdbId}"
        },
        isMovie = item.mediaType == MediaType.movie,
        title = item.title,
        year = item.year,
        posterPath = item.posterPath,
        score = item.score,
    )

    private fun href(status: WatchStatus, typeParam: String?) =
        "/watchlist?status=${status.apiName}" + (typeParam?.let { "&type=$it" } ?: "")

    private fun typeParam(type: MediaType) = when (type) {
        MediaType.movie -> "movie"
        MediaType.tvshow -> "tv"
    }

    companion object {
        private val TYPE_FILTERS = mapOf(
            "movie" to MediaType.movie,
            "movies" to MediaType.movie,
            "tv" to MediaType.tvshow,
            "tvshow" to MediaType.tvshow,
        )
    }
}
