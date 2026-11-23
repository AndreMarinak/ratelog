package org.ratelog.home.dashboard

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.customlist.ListSummary
import org.ratelog.customlist.ListSummaryRepository
import org.ratelog.home.DiscoverError
import org.ratelog.home.DiscoverFeed
import org.ratelog.home.DiscoverTitle
import org.ratelog.home.HomeRepository
import org.ratelog.home.HomeTitle
import org.ratelog.home.TmdbDiscoverClient
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

data class GetHomeDashboard(
    val userId: User.Id,
    /** Metadata language: titles and TMDB feeds. */
    val lang: Lang,
    /** For "In theaters" and "Coming soon". Defaults to the region of the metadata language, like watch providers. */
    val region: WatchRegion = WatchRegion.fromLang(lang),
)

/** Rows in display order. `apiName` is the row id in the JSON API and the HTML anchor. */
enum class HomeRowKind(val apiName: String, val titleKey: String, val feed: DiscoverFeed? = null) {
    // User preference: what's new leads the page (trending, in theaters, coming soon), then the viewer's own rows,
    // then Popular TV.
    TRENDING_MOVIES("trending_movies", "home.row.trending.movies", DiscoverFeed.TRENDING_MOVIES),
    TRENDING_TV("trending_tv", "home.row.trending.tv", DiscoverFeed.TRENDING_TV),
    IN_THEATERS("in_theaters", "home.row.in.theaters", DiscoverFeed.NOW_PLAYING),
    COMING_SOON("coming_soon", "home.row.coming.soon", DiscoverFeed.UPCOMING),
    CONTINUE_WATCHING("continue_watching", "home.row.continue.watching"),
    UP_NEXT("up_next", "home.row.up.next"),
    RECENTLY_RATED("recently_rated", "home.row.recently.rated"),
    YOUR_LISTS("your_lists", "home.row.your.lists"),
    CLASSICS("classics", "home.row.classics", DiscoverFeed.CLASSICS),
    POPULAR_TV("popular_tv", "home.row.popular.tv", DiscoverFeed.POPULAR_TV);

    val isPersonal: Boolean get() = feed == null
}

/** A row has titles, except [HomeRowKind.YOUR_LISTS], which has lists. Rows are never empty. */
data class HomeRow(
    val kind: HomeRowKind,
    val titles: List<HomeTitle> = emptyList(),
    val lists: List<ListSummary> = emptyList(),
)

enum class DiscoverState {
    /** At least one TMDB row loaded (or TMDB simply had nothing). */
    OK,

    /** No TMDB API key, so there are no discovery rows. */
    NOT_CONFIGURED,

    /** Every TMDB feed failed. */
    UNAVAILABLE,
}

data class HomeDashboard(
    val rows: List<HomeRow>,
    val discover: DiscoverState,
) {
    val hasPersonalRows: Boolean get() = rows.any { it.kind.isPersonal }
}

/**
 * The home page: rows in [HomeRowKind] order (trending / in theaters / coming soon, the viewer's own non-empty rows, classics, popular TV).
 * A TMDB feed that fails just leaves its row out; the dashboard itself never fails.
 */
@Service
class GetHomeDashboardHandler(
    private val homeRepository: HomeRepository,
    private val listSummaryRepository: ListSummaryRepository,
    private val discoverClient: TmdbDiscoverClient,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    fun handle(query: GetHomeDashboard): HomeDashboard {
        val personal = listOf(
            HomeRow(HomeRowKind.CONTINUE_WATCHING, titles = homeRepository.findByStatus(query.userId, WatchStatus.WATCHING, query.lang, ROW_LIMIT)),
            HomeRow(HomeRowKind.UP_NEXT, titles = homeRepository.findByStatus(query.userId, WatchStatus.WANT_TO_WATCH, query.lang, ROW_LIMIT)),
            HomeRow(HomeRowKind.RECENTLY_RATED, titles = homeRepository.findRecentlyRated(query.userId, query.lang, ROW_LIMIT)),
            HomeRow(HomeRowKind.YOUR_LISTS, lists = listSummaryRepository.findByOwner(query.userId).take(ROW_LIMIT)),
        )

        val feeds = HomeRowKind.entries.mapNotNull { kind ->
            kind.feed?.let { feed -> kind to discoverClient.fetch(feed, query.lang, query.region) }
        }
        val today = LocalDate.now(clock)
        val discovered = feeds.mapNotNull { (kind, result) ->
            result.getOrNull()?.let { titles -> kind to shape(kind, titles, today) }
        }
        val marked = withMarks(query.userId, discovered)
            .map { (kind, titles) -> HomeRow(kind, titles = titles) }

        val discover = when {
            feeds.isNotEmpty() && feeds.all { it.second.leftOrNull() == DiscoverError.NotConfigured } -> DiscoverState.NOT_CONFIGURED
            feeds.isNotEmpty() && feeds.all { it.second.isLeft() } -> DiscoverState.UNAVAILABLE
            else -> DiscoverState.OK
        }

        return HomeDashboard(
            rows = (personal + marked).filter { it.titles.isNotEmpty() || it.lists.isNotEmpty() }.sortedBy { it.kind.ordinal },
            discover = discover,
        )
    }

    /** Drops posterless titles (a wall of letters isn't discovery); "Coming soon" keeps only future dates, soonest first. */
    private fun shape(kind: HomeRowKind, titles: List<DiscoverTitle>, today: LocalDate): List<DiscoverTitle> {
        val withPosters = titles.filter { it.posterPath != null }.distinctBy { it.tmdbId }
        val shaped = if (kind == HomeRowKind.COMING_SOON) {
            withPosters.filter { it.releaseDate?.isAfter(today) == true }.sortedBy { it.releaseDate }
        } else {
            withPosters
        }
        return shaped.take(ROW_LIMIT)
    }

    /** Adds the viewer's status and score with one query per media type for every TMDB row. */
    private fun withMarks(
        userId: User.Id,
        rows: List<Pair<HomeRowKind, List<DiscoverTitle>>>,
    ): List<Pair<HomeRowKind, List<HomeTitle>>> {
        val all = rows.flatMap { it.second }
        val marks = MediaType.entries.associateWith { type ->
            val ids = all.filter { it.mediaType == type }.map { it.tmdbId }.toSet()
            if (ids.isEmpty()) emptyMap() else homeRepository.findMarks(userId, type, ids)
        }
        return rows.map { (kind, titles) ->
            kind to titles.map { title ->
                val mark = marks[title.mediaType]?.get(title.tmdbId)
                HomeTitle(
                    tmdbId = title.tmdbId,
                    mediaType = title.mediaType,
                    title = title.title,
                    year = title.year,
                    posterPath = title.posterPath,
                    status = mark?.status,
                    score = mark?.score,
                    releaseDate = title.releaseDate,
                )
            }
        }
    }

    companion object {
        const val ROW_LIMIT = 20
    }
}
