package org.ratelog.stats

import org.ratelog.MediaType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor

/** A calendar year (in the app's time zone) or everything. */
sealed interface StatsPeriod {
    /** `2026` or `all`: the `year` parameter of `/stats` and `/api/v1/stats`. */
    val param: String

    data class Year(val year: Int) : StatsPeriod {
        override val param: String get() = year.toString()
    }

    data object AllTime : StatsPeriod {
        override val param: String get() = "all"
    }
}

/**
 * One title counted in a period. A movie: its rating, or its Watched mark when unrated. A show: the seasons you rated in
 * the period (score = their average, the TV rule restricted to the period), else its whole-show score (only when no
 * season is rated at all, since rated seasons win), else its Watched mark.
 */
data class StatsEntry(
    val type: MediaType,
    val tmdbId: Int,
    val score: Double?,
    /** The latest rating in the period (a show's last rated season), or the Watched mark. */
    val atMs: Long,
    val genreIds: List<Int>,
    val releaseYear: Int?,
    /** Movies only. */
    val runtimeMinutes: Int? = null,
    val runtimePending: Boolean = false,
    /** Seasons rated in the period. */
    val seasonsRated: Int = 0,
    /** A show counted through its whole-show score. */
    val wholeShow: Boolean = false,
) {
    val rated: Boolean get() = score != null
}

data class StatsTotals(
    /** Movies rated or marked seen in the period, or (fork, 2026-11-24) rewatched in it: each once. */
    val moviesSeen: Int,
    val moviesRated: Int,
    /** Shows with a season rated, a whole-show score or a Watched mark in the period, or rewatched in it: each once. */
    val showsSeen: Int,
    val showsRated: Int,
    val seasonsRated: Int,
    /** Movie ratings + season ratings + whole-show ratings (those in use) given in the period. */
    val ratings: Int,
    /** Average over the rated titles (a show counts once, with its season average). Null with nothing rated. */
    val averageScore: Double?,
    /**
     * Sum of the movies' runtimes that are known, once per watch: the first watch counted in the period plus each rewatch
     * in it. TV is not included (episode counts aren't stored).
     */
    val runtimeMinutes: Long,
    /** Movies whose runtime is known; the hours are "based on runtimeKnown of moviesSeen". */
    val runtimeKnown: Int,
    /** Movies whose runtime is being looked up. */
    val runtimePending: Int,
    /** Fork (2026-11-24): rewatches in the period (each one, a title rewatched twice counts 2). */
    val rewatches: Int = 0,
) {
    /** Titles watched in the period: first watches plus titles rewatched in it, each title once. */
    val titles: Int get() = moviesSeen + showsSeen
}

/** Fork (2026-11-24, rewatch log): a title by its rewatches (All time's "Most rewatched"). */
data class RewatchedTitle(
    val type: MediaType,
    val tmdbId: Int,
    val rewatches: Int,
    val lastAtMs: Long,
) {
    /** The first watch plus the rewatches. */
    val watchCount: Int get() = 1 + rewatches
}

/** Titles whose score rounds to [score] (1-10). */
data class ScoreBucket(val score: Int, val count: Int)

/** A month (1-12) of a year, or a year of "All time". */
data class TimelineBucket(val key: Int, val movies: Int, val shows: Int) {
    val total: Int get() = movies + shows
}

data class GenreStat(val genreId: Int, val count: Int, val rated: Int, val averageScore: Double?)

data class DecadeStat(val decade: Int, val count: Int, val rated: Int, val averageScore: Double?)

data class Stats(
    val period: StatsPeriod,
    /** Years with something rated or seen, newest first. */
    val years: List<Int>,
    val totals: StatsTotals,
    /** Always ten buckets, 1 to 10. */
    val distribution: List<ScoreBucket>,
    /** Twelve months for a year; every year from the first to the last with data for "All time". */
    val timeline: List<TimelineBucket>,
    /** Most titles first. */
    val genres: List<GenreStat>,
    /** Oldest first. */
    val decades: List<DecadeStat>,
    /** Highest score first, at most [StatsCalculator.TOP_LIMIT]. */
    val top: List<StatsEntry>,
    /** Highest / lowest average among genres with at least [StatsCalculator.GENRE_VERDICT_MIN] rated titles. */
    val generousGenre: GenreStat?,
    val harshestGenre: GenreStat?,
    /** Fork (2026-11-24): All time only, most rewatches first, at most [StatsCalculator.REWATCHED_LIMIT]; empty for a year. */
    val mostRewatched: List<RewatchedTitle> = emptyList(),
) {
    val isEmpty: Boolean get() = totals.moviesSeen == 0 && totals.showsSeen == 0
}

/** Pure counting, so the rules (year boundaries, the TV rule, buckets) are tested without a database. */
object StatsCalculator {
    const val TOP_LIMIT = 10
    const val GENRE_VERDICT_MIN = 3
    const val REWATCHED_LIMIT = 5

    fun calculate(source: StatsSource, period: StatsPeriod, zone: ZoneId): Stats {
        val entries = entries(source, period, zone)
        val rewatches = rewatches(source, period, zone)
        val genres = genres(entries)
        val verdictGenres = genres.filter { it.rated >= GENRE_VERDICT_MIN && it.averageScore != null }
        val generous = verdictGenres.maxWithOrNull(compareBy<GenreStat> { it.averageScore!! }.thenBy { it.rated })
        val harshest = verdictGenres.minWithOrNull(compareBy<GenreStat> { it.averageScore!! }.thenByDescending { it.rated })
        val distinct = generous != null && harshest != null && generous.genreId != harshest.genreId &&
            generous.averageScore!! > harshest.averageScore!!
        return Stats(
            period = period,
            years = yearsWithData(source, zone),
            totals = totals(entries, rewatches),
            distribution = distribution(entries),
            timeline = timeline(entries, period, zone, rewatches),
            genres = genres,
            decades = decades(entries),
            top = entries.filter { it.rated }
                .sortedWith(compareByDescending<StatsEntry> { it.score }.thenByDescending { it.atMs }.thenBy { it.tmdbId })
                .take(TOP_LIMIT),
            generousGenre = generous.takeIf { distinct },
            harshestGenre = harshest.takeIf { distinct },
            mostRewatched = if (period == StatsPeriod.AllTime) mostRewatched(rewatches) else emptyList(),
        )
    }

    /** Fork (2026-11-24): the rewatches that happened in the period (a rewatch counts when it happened). */
    fun rewatches(source: StatsSource, period: StatsPeriod, zone: ZoneId): List<StatsRewatch> {
        val range = range(period, zone) ?: return source.rewatches
        return source.rewatches.filter { it.atMs in range }
    }

    fun mostRewatched(rewatches: List<StatsRewatch>): List<RewatchedTitle> =
        rewatches.groupBy { it.type to it.tmdbId }
            .map { (key, list) -> RewatchedTitle(key.first, key.second, list.size, list.maxOf { it.atMs }) }
            .sortedWith(compareByDescending<RewatchedTitle> { it.rewatches }.thenByDescending { it.lastAtMs }.thenBy { it.tmdbId })
            .take(REWATCHED_LIMIT)

    /** `[start, end)` in epoch ms, or null for "All time". */
    fun range(period: StatsPeriod, zone: ZoneId): LongRange? = when (period) {
        StatsPeriod.AllTime -> null
        is StatsPeriod.Year -> {
            val start = LocalDate.of(period.year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = LocalDate.of(period.year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            start until end
        }
    }

    fun entries(source: StatsSource, period: StatsPeriod, zone: ZoneId): List<StatsEntry> {
        val range = range(period, zone)
        fun inPeriod(ms: Long?) = ms != null && (range == null || ms in range)

        val movies = source.movies.filter { inPeriod(it.atMs) }.map {
            StatsEntry(
                type = MediaType.movie,
                tmdbId = it.tmdbId,
                score = if (it.rated) it.score else null,
                atMs = it.atMs,
                genreIds = it.genreIds,
                releaseYear = it.releaseYear,
                runtimeMinutes = it.runtimeMinutes,
                runtimePending = it.runtimeMinutes == null && it.runtimePending,
            )
        }

        val seasonsByShow = source.seasons.groupBy { it.tmdbId }
        val shows = source.shows.mapNotNull { show ->
            val allSeasons = seasonsByShow[show.tmdbId].orEmpty()
            val seasons = allSeasons.filter { inPeriod(it.atMs) }
            when {
                seasons.isNotEmpty() -> StatsEntry(
                    type = MediaType.tvshow,
                    tmdbId = show.tmdbId,
                    score = seasons.map { it.score }.average(),
                    atMs = seasons.maxOf { it.atMs },
                    genreIds = show.genreIds,
                    releaseYear = show.releaseYear,
                    seasonsRated = seasons.size,
                )
                // Rated seasons win: a whole-show score is unused while any season is rated (even in another year).
                allSeasons.isNotEmpty() -> null
                show.showScore != null -> show.showRatedAtMs.takeIf(::inPeriod)?.let { at ->
                    StatsEntry(MediaType.tvshow, show.tmdbId, show.showScore, at, show.genreIds, show.releaseYear, wholeShow = true)
                }
                else -> show.watchedAtMs.takeIf(::inPeriod)?.let { at ->
                    StatsEntry(MediaType.tvshow, show.tmdbId, null, at, show.genreIds, show.releaseYear)
                }
            }
        }
        return movies + shows
    }

    /** Every year that has at least one entry, newest first. */
    fun yearsWithData(source: StatsSource, zone: ZoneId): List<Int> {
        val seasonShows = source.seasons.map { it.tmdbId }.toSet()
        val times = source.movies.map { it.atMs } +
            source.seasons.map { it.atMs } +
            source.shows.filter { it.tmdbId !in seasonShows }.mapNotNull { if (it.showScore != null) it.showRatedAtMs else it.watchedAtMs } +
            source.rewatches.map { it.atMs }
        return times.map { yearOf(it, zone) }.distinct().sortedDescending()
    }

    /**
     * Ratings, scores and seasons come from the entries only (a rewatch is not a rating). Fork (2026-11-24): the title
     * counts and the hours also take the period's rewatches: a title rewatched in the period counts once (even if it was
     * first seen years ago), and each rewatch of a movie adds its runtime.
     */
    fun totals(entries: List<StatsEntry>, rewatches: List<StatsRewatch> = emptyList()): StatsTotals {
        val movies = entries.filter { it.type == MediaType.movie }
        val shows = entries.filter { it.type == MediaType.tvshow }
        val rated = entries.mapNotNull { it.score }
        val seasons = shows.sumOf { it.seasonsRated }
        val movieRewatches = rewatches.filter { it.type == MediaType.movie }
        // Each movie once, with what is known of its runtime (an entry's, else a rewatch's).
        val movieRuntime = LinkedHashMap<Int, Pair<Int?, Boolean>>()
        movies.forEach { movieRuntime[it.tmdbId] = it.runtimeMinutes to it.runtimePending }
        movieRewatches.forEach { movieRuntime.putIfAbsent(it.tmdbId, it.runtimeMinutes to (it.runtimeMinutes == null && it.runtimePending)) }
        val showIds = shows.map { it.tmdbId }.toSet() + rewatches.filter { it.type == MediaType.tvshow }.map { it.tmdbId }
        return StatsTotals(
            moviesSeen = movieRuntime.size,
            moviesRated = movies.count { it.rated },
            showsSeen = showIds.size,
            showsRated = shows.count { it.rated },
            seasonsRated = seasons,
            ratings = movies.count { it.rated } + seasons + shows.count { it.wholeShow },
            averageScore = rated.takeIf { it.isNotEmpty() }?.average(),
            runtimeMinutes = movies.sumOf { (it.runtimeMinutes ?: 0).toLong() } + movieRewatches.sumOf { (it.runtimeMinutes ?: 0).toLong() },
            runtimeKnown = movieRuntime.values.count { it.first != null },
            runtimePending = movieRuntime.values.count { it.first == null && it.second },
            rewatches = rewatches.size,
        )
    }

    /** Rounded to the nearest whole point (7.5 counts as 8); anything under 1.5 counts as 1. */
    fun bucket(score: Double): Int = floor(score + 0.5).toInt().coerceIn(1, 10)

    fun distribution(entries: List<StatsEntry>): List<ScoreBucket> {
        val counts = entries.mapNotNull { it.score }.groupingBy(::bucket).eachCount()
        return (1..10).map { ScoreBucket(it, counts[it] ?: 0) }
    }

    /**
     * Watches per month (per year for All time): each entry when it counts, plus (fork, 2026-11-24) each rewatch when it
     * happened, split into movies and TV.
     */
    fun timeline(entries: List<StatsEntry>, period: StatsPeriod, zone: ZoneId, rewatches: List<StatsRewatch> = emptyList()): List<TimelineBucket> {
        val watches = entries.map { it.type to it.atMs } + rewatches.map { it.type to it.atMs }
        fun bucketsOf(keys: IntRange, keyOf: (Long) -> Int): List<TimelineBucket> {
            val byKey = watches.groupBy { keyOf(it.second) }
            return keys.map { key ->
                val list = byKey[key].orEmpty()
                TimelineBucket(key, list.count { it.first == MediaType.movie }, list.count { it.first == MediaType.tvshow })
            }
        }
        return when (period) {
            is StatsPeriod.Year -> bucketsOf(1..12) { Instant.ofEpochMilli(it).atZone(zone).monthValue }
            StatsPeriod.AllTime -> {
                if (watches.isEmpty()) return emptyList()
                val years = watches.map { yearOf(it.second, zone) }
                bucketsOf(years.min()..years.max()) { yearOf(it, zone) }
            }
        }
    }

    fun genres(entries: List<StatsEntry>): List<GenreStat> =
        entries.flatMap { entry -> entry.genreIds.map { it to entry } }
            .groupBy({ it.first }, { it.second })
            .map { (genreId, list) ->
                val scores = list.mapNotNull { it.score }
                GenreStat(genreId, list.size, scores.size, scores.takeIf { it.isNotEmpty() }?.average())
            }
            .sortedWith(compareByDescending<GenreStat> { it.count }.thenByDescending { it.averageScore ?: -1.0 }.thenBy { it.genreId })

    fun decades(entries: List<StatsEntry>): List<DecadeStat> =
        entries.filter { it.releaseYear != null && it.releaseYear > 0 }
            .groupBy { it.releaseYear!! / 10 * 10 }
            .map { (decade, list) ->
                val scores = list.mapNotNull { it.score }
                DecadeStat(decade, list.size, scores.size, scores.takeIf { it.isNotEmpty() }?.average())
            }
            .sortedBy { it.decade }

    private fun yearOf(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).year
}
