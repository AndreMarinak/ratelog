package org.ratelog.stats.web

import org.ratelog.Genre
import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.stats.GetStats
import org.ratelog.stats.GetStatsHandler
import org.ratelog.stats.StatsPeriod
import org.ratelog.stats.StatsResult
import org.ratelog.stats.StatsTopTitle
import org.ratelog.user.User
import org.ratelog.watchlist.seen.TileActionsView
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Clock
import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** What templates/stats.html renders. Percentages are bar sizes (0-100) relative to the chart's largest value. */
data class StatsView(
    /** `2026` or `all`. */
    val param: String,
    /** The year shown, or null for "All time". */
    val year: Int?,
    val switcher: List<StatsYearLink>,
    val empty: Boolean,
    /** On an empty year: the newest year that has something (or All time), else null. */
    val emptyLink: StatsYearLink?,
    val headline: StatsHeadline,
    val distribution: List<StatsBar>,
    /** Months of the year, or years for All time. */
    val timeline: List<StatsStackBar>,
    val timelineByYear: Boolean,
    val genres: List<StatsRow>,
    val decades: List<StatsRow>,
    val generous: StatsRow?,
    val harshest: StatsRow?,
    val top: List<StatsTile>,
)

data class StatsYearLink(val label: String?, val labelKey: String?, val href: String, val active: Boolean, val period: String)

data class StatsHeadline(
    val moviesSeen: Int,
    val moviesRated: Int,
    val showsSeen: Int,
    val seasonsRated: Int,
    val ratings: Int,
    /** "7.4", or null with nothing rated. */
    val average: String?,
    /** "52" or "3.5" (under 10 hours). */
    val hours: String,
    val runtimeKnown: Int,
    /** Some movies have no known runtime: say what the hours are based on. */
    val runtimePartial: Boolean,
    val runtimePending: Boolean,
)

data class StatsBar(val label: String, val count: Int, val percent: Int)

data class StatsStackBar(
    /** "Jan" or "2025". */
    val label: String,
    /** "J" (phones) or "’25". */
    val shortLabel: String,
    /** "January 2026" or "2025", for the table and the tooltip. */
    val fullLabel: String,
    val movies: Int,
    val shows: Int,
    val moviesPercent: Int,
    val showsPercent: Int,
) {
    val total: Int get() = movies + shows
}

data class StatsRow(
    /** A messages.properties key (genres) or null when [label] is literal (decades). */
    val labelKey: String?,
    val label: String?,
    val count: Int,
    val percent: Int,
    /** "7.8", or null when none of them is rated. */
    val average: String?,
)

data class StatsTile(
    val href: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val posterUrl2x: String?,
    val initial: String,
    val isMovie: Boolean,
    val score: String,
    /** "#1" ... */
    val rank: Int,
    val actions: TileActionsView,
)

/**
 * GET /stats?year=2026|all: your own stats (no `year`: this year). Linked from the profile, the phone menu sheet and the
 * desktop header. The year switcher is a group of plain links that apply in place and replace the URL
 * (static/view-swap.js, "One Back leaves the page"). Only the viewer's stats; viewing someone else's is a follow-up.
 */
@Controller
class StatsController(
    private val handler: GetStatsHandler,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    @GetMapping("/stats")
    fun stats(
        @CurrentUser user: User,
        @RequestParam("year", required = false) year: String?,
        model: Model,
    ): String {
        val currentYear = handler.currentYear()
        // Anything that isn't a valid year shows this year (the page never errors on a hand-typed URL).
        val period = parsePeriod(year, currentYear) ?: StatsPeriod.Year(currentYear)
        val result = handler.handle(GetStats(user.id!!, period, user.metadataLang)).getOrNull()
            ?: handler.handle(GetStats(user.id, StatsPeriod.Year(currentYear), user.metadataLang)).getOrNull()
            ?: error("the current year is always valid")
        model.addAttribute("stats", view(result, LocalDate.now(clock), LocaleContextHolder.getLocale()))
        return "stats"
    }

    companion object {
        const val GENRE_ROWS = 8

        /** `2025` -> that year, `all` -> All time; null for anything else (blank, junk, out of range). */
        fun parsePeriod(value: String?, currentYear: Int): StatsPeriod? {
            val v = value?.trim()?.lowercase()
            if (v.isNullOrEmpty()) return null
            if (v == "all" || v == "all-time" || v == "alltime") return StatsPeriod.AllTime
            val year = v.toIntOrNull() ?: return null
            return if (year in GetStatsHandler.FIRST_YEAR..currentYear) StatsPeriod.Year(year) else null
        }

        fun href(period: StatsPeriod, currentYear: Int): String = when (period) {
            StatsPeriod.AllTime -> "/stats?year=all"
            is StatsPeriod.Year -> if (period.year == currentYear) "/stats" else "/stats?year=${period.year}"
        }

        fun number(value: Double, locale: Locale): String = String.format(locale, "%.1f", value)

        fun hours(minutes: Long, locale: Locale): String {
            val hours = minutes / 60.0
            return if (hours < 10 && minutes > 0) String.format(locale, "%.1f", hours) else String.format(locale, "%,d", hours.roundToInt())
        }

        private fun percent(value: Int, max: Int): Int = if (max <= 0 || value <= 0) 0 else (value * 100.0 / max).roundToInt().coerceIn(2, 100)

        fun view(result: StatsResult, today: LocalDate, locale: Locale): StatsView {
            val stats = result.stats
            val period = stats.period
            val current = result.currentYear
            val selectedYear = (period as? StatsPeriod.Year)?.year

            val years = (stats.years.filter { it <= current } + current + listOfNotNull(selectedYear)).distinct().sortedDescending()
            val switcher = years.map { y ->
                StatsYearLink(y.toString(), null, href(StatsPeriod.Year(y), current), selectedYear == y, y.toString())
            } + StatsYearLink(null, "stats.year.all", href(StatsPeriod.AllTime, current), period == StatsPeriod.AllTime, "all")

            val emptyLink = if (!stats.isEmpty) null else {
                val latest = stats.years.firstOrNull { it != selectedYear && it <= current }
                when {
                    latest != null -> StatsYearLink(latest.toString(), null, href(StatsPeriod.Year(latest), current), false, latest.toString())
                    else -> null
                }
            }

            val t = stats.totals
            val headline = StatsHeadline(
                moviesSeen = t.moviesSeen,
                moviesRated = t.moviesRated,
                showsSeen = t.showsSeen,
                seasonsRated = t.seasonsRated,
                ratings = t.ratings,
                average = t.averageScore?.let { number(it, locale) },
                hours = hours(t.runtimeMinutes, locale),
                runtimeKnown = t.runtimeKnown,
                runtimePartial = t.runtimeKnown < t.moviesSeen,
                runtimePending = t.runtimePending > 0,
            )

            val maxBucket = stats.distribution.maxOfOrNull { it.count } ?: 0
            val distribution = stats.distribution.map { StatsBar(it.score.toString(), it.count, percent(it.count, maxBucket)) }

            val maxTimeline = stats.timeline.maxOfOrNull { it.total } ?: 0
            val timeline = stats.timeline.map { b ->
                val (label, short, full) = if (selectedYear != null) {
                    val month = Month.of(b.key)
                    Triple(
                        month.getDisplayName(TextStyle.SHORT, locale),
                        month.getDisplayName(TextStyle.NARROW, locale),
                        month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) } + " " + selectedYear,
                    )
                } else {
                    Triple(b.key.toString(), "’" + (b.key % 100).toString().padStart(2, '0'), b.key.toString())
                }
                StatsStackBar(label, short, full, b.movies, b.shows, percent(b.movies, maxTimeline), percent(b.shows, maxTimeline))
            }

            val knownGenres = stats.genres.filter { Genre.fromTmdbId(it.genreId) != null }
            val maxGenre = knownGenres.maxOfOrNull { it.count } ?: 0
            fun genreRow(g: org.ratelog.stats.GenreStat) =
                StatsRow("genre.${g.genreId}", null, g.count, percent(g.count, maxGenre), g.averageScore?.let { number(it, locale) })
            val genres = knownGenres.take(GENRE_ROWS).map(::genreRow)

            val maxDecade = stats.decades.maxOfOrNull { it.count } ?: 0
            val decades = stats.decades.map {
                StatsRow(null, "${it.decade}s", it.count, percent(it.count, maxDecade), it.averageScore?.let { avg -> number(avg, locale) })
            }

            val generous = stats.generousGenre?.takeIf { Genre.fromTmdbId(it.genreId) != null }?.let(::genreRow)
            val harshest = stats.harshestGenre?.takeIf { Genre.fromTmdbId(it.genreId) != null }?.let(::genreRow)

            return StatsView(
                param = period.param,
                year = selectedYear,
                switcher = switcher,
                empty = stats.isEmpty,
                emptyLink = emptyLink,
                headline = headline,
                distribution = distribution,
                timeline = timeline,
                timelineByYear = selectedYear == null,
                genres = genres,
                decades = decades,
                generous = if (generous != null && harshest != null) generous else null,
                harshest = if (generous != null && harshest != null) harshest else null,
                top = result.top.mapIndexed { i, top -> top.toTile(i + 1, today, locale) },
            )
        }

        private fun StatsTopTitle.toTile(rank: Int, today: LocalDate, locale: Locale): StatsTile {
            val type = title.type
            val score = entry.score ?: 0.0
            return StatsTile(
                href = if (type == MediaType.movie) "/movie/${title.tmdbId}" else "/tv/${title.tmdbId}",
                title = title.title,
                year = title.year,
                posterUrl = title.posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                posterUrl2x = title.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" },
                initial = title.title.trim().take(1).uppercase().ifEmpty { "?" },
                isMovie = type == MediaType.movie,
                score = number(score, locale),
                rank = rank,
                actions = TileActionsView.of(
                    tmdbId = title.tmdbId,
                    mediaType = type,
                    status = title.status,
                    rated = true,
                    releaseDate = title.releaseDate,
                    today = today,
                    locale = locale,
                    score = score,
                    onWatchlist = title.onWatchlist,
                ),
            )
        }
    }
}
