package org.ratelog.home.dashboard

import org.ratelog.MediaType
import org.ratelog.customlist.browse.ListCardView
import org.ratelog.customlist.browse.toCard
import org.ratelog.home.HomeTitle
import org.ratelog.watchlist.WatchStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/* What templates/fragments/home-dashboard.html renders. Built in the web layer, from a [HomeDashboard]. */

data class HomeDashboardView(
    val rows: List<HomeRowView>,
    /** Nothing personal yet: show a short "how this page fills up" note. */
    val showWelcome: Boolean,
    val tmdbNotConfigured: Boolean,
    val tmdbUnavailable: Boolean,
)

data class HomeRowView(
    /** HTML id, e.g. `row-trending-movies`. */
    val id: String,
    val titleKey: String,
    val moreHref: String?,
    val moreKey: String,
    val isLists: Boolean,
    val tiles: List<HomeTileView>,
    val lists: List<ListCardView>,
)

data class HomeTileView(
    val href: String,
    val tmdbId: Int,
    /** `movie` or `tv`, as POST /watchlist/toggle expects. */
    val type: String,
    val title: String,
    /** Year, or the release date in "Coming soon". */
    val meta: String?,
    val posterUrl: String?,
    val posterUrl2x: String?,
    val initial: String,
    val isMovie: Boolean,
    val showTypeTag: Boolean,
    /** Watching / Watched / Dropped badge (TMDB rows only). */
    val statusIcon: String?,
    val statusKey: String?,
    val scoreLabel: String?,
    /** Shows the one-click watchlist bookmark: the title has no status or is Want to watch. */
    val canToggleWatchlist: Boolean,
    val inWatchlist: Boolean,
)

fun HomeDashboard.toView(locale: Locale, today: LocalDate = LocalDate.now()) = HomeDashboardView(
    rows = rows.map { it.toView(locale, today) },
    showWelcome = !hasPersonalRows,
    tmdbNotConfigured = discover == DiscoverState.NOT_CONFIGURED,
    tmdbUnavailable = discover == DiscoverState.UNAVAILABLE,
)

private fun HomeRow.toView(locale: Locale, today: LocalDate): HomeRowView {
    // Personal rows mix movies and TV and share a status, so they show the type instead of the status.
    // "Want to watch" is never a badge: the watchlist bookmark shows it (filled).
    val showType = kind.isPersonal
    val showStatus = !kind.isPersonal
    return HomeRowView(
        id = "row-" + kind.apiName.replace('_', '-'),
        titleKey = kind.titleKey,
        moreHref = kind.moreHref,
        moreKey = if (kind.isPersonal) "home.row.see.all" else "home.row.more",
        isLists = kind == HomeRowKind.YOUR_LISTS,
        tiles = titles.map { it.toTile(kind, locale, today, showType, showStatus) },
        lists = lists.map { it.toCard(showOwner = false) },
    )
}

/** "See all" for personal rows, "More" (the Browse page) for TMDB rows. */
val HomeRowKind.moreHref: String
    get() = when (this) {
        HomeRowKind.CONTINUE_WATCHING -> "/watchlist?status=watching"
        HomeRowKind.UP_NEXT -> "/watchlist?status=want_to_watch"
        HomeRowKind.RECENTLY_RATED -> "/profile"
        HomeRowKind.YOUR_LISTS -> "/lists"
        HomeRowKind.TRENDING_MOVIES -> "/browse?type=movie&popular=week"
        HomeRowKind.TRENDING_TV -> "/browse?type=tv&popular=week"
        HomeRowKind.IN_THEATERS -> "/browse?type=movie&sort=release_desc"
        HomeRowKind.COMING_SOON -> "/browse?type=movie&decade=upcoming"
        HomeRowKind.POPULAR_TV -> "/browse?type=tv&popular=now"
    }

fun HomeTitle.href(): String = when (mediaType) {
    MediaType.movie -> "/movie/$tmdbId"
    MediaType.tvshow -> "/tv/$tmdbId"
}

private fun HomeTitle.toTile(kind: HomeRowKind, locale: Locale, today: LocalDate, showType: Boolean, showStatus: Boolean) = HomeTileView(
    href = href(),
    tmdbId = tmdbId,
    type = if (mediaType == MediaType.movie) "movie" else "tv",
    title = title,
    meta = if (kind == HomeRowKind.COMING_SOON && releaseDate != null) releaseLabel(releaseDate, locale, today) else year?.toString(),
    posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w185$it" },
    posterUrl2x = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
    initial = title.trim().take(1).uppercase().ifEmpty { "?" },
    isMovie = mediaType == MediaType.movie,
    showTypeTag = showType,
    statusIcon = status?.takeIf { showStatus && it != WatchStatus.WANT_TO_WATCH }?.icon,
    statusKey = status?.takeIf { showStatus && it != WatchStatus.WANT_TO_WATCH }?.messageKey,
    scoreLabel = score?.let { String.format(Locale.ROOT, "%.1f", it) },
    // POST /watchlist/toggle leaves Watching / Watched / Dropped alone, so those tiles get no button.
    canToggleWatchlist = status == null || status == WatchStatus.WANT_TO_WATCH,
    inWatchlist = status == WatchStatus.WANT_TO_WATCH,
)

/** "Dec 5" (in the UI language), with the year when it isn't this year. */
private fun releaseLabel(date: LocalDate, locale: Locale, today: LocalDate): String {
    val pattern = if (date.year == today.year) "MMMd" else "yMMMd"
    return DateTimeFormatter.ofLocalizedPattern(pattern).withLocale(locale).format(date)
}
