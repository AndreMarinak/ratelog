package org.ratelog.stats

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneId

data class GetStats(
    val userId: User.Id,
    val period: StatsPeriod,
    val lang: Lang,
)

sealed interface GetStatsError {
    /** Before [GetStatsHandler.FIRST_YEAR] or after the current year. */
    data class InvalidYear(val year: Int) : GetStatsError
}

/** A "Highest rated" tile: the counted entry plus its title and poster. */
data class StatsTopTitle(val entry: StatsEntry, val title: StatsTitle)

/** Fork (2026-11-24): a "Most rewatched" row (All time): the title's rewatches plus its title and poster. */
data class StatsRewatchedTitle(val rewatched: RewatchedTitle, val title: StatsTitle)

data class StatsResult(
    val stats: Stats,
    val top: List<StatsTopTitle>,
    val currentYear: Int,
    val zone: ZoneId,
    val mostRewatched: List<StatsRewatchedTitle> = emptyList(),
)

/**
 * The signed-in user's own stats for a year or all time (user request, 2026-11-23: "go with the stats"). Three
 * queries read everything rated or seen, [StatsCalculator] counts the period, one query per type fetches the ten
 * "Highest rated" titles. Missing movie runtimes are queued for a background lookup ([RuntimeFiller]); the page never
 * waits on TMDB.
 */
@Component
class GetStatsHandler(
    private val repository: StatsRepository,
    private val runtimeFiller: RuntimeFiller,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val logger = LoggerFactory.getLogger(GetStatsHandler::class.java)

    fun currentYear(): Int = clock.instant().atZone(clock.zone).year

    fun handle(query: GetStats): Either<GetStatsError, StatsResult> = either {
        val currentYear = currentYear()
        val period = query.period
        if (period is StatsPeriod.Year) {
            ensure(period.year in FIRST_YEAR..currentYear) { GetStatsError.InvalidYear(period.year) }
        }
        val zone = clock.zone
        val source = repository.load(query.userId, clock.millis())
        val stats = StatsCalculator.calculate(source, period, zone)

        // Fork (2026-11-24): a rewatched movie needs its runtime too (each rewatch adds its hours).
        val pending = (
            source.movies.filter { it.runtimeMinutes == null && it.runtimePending }.map { it.tmdbId } +
                source.rewatches.filter { it.type == MediaType.movie && it.runtimeMinutes == null && it.runtimePending }.map { it.tmdbId }
            ).distinct()
        if (pending.isNotEmpty()) {
            try {
                runtimeFiller.fill(pending)
            } catch (e: Exception) {
                logger.warn("Stats runtime lookups not queued: {}", e.javaClass.simpleName)
            }
        }

        StatsResult(stats, topTitles(query, stats.top), currentYear, zone, rewatchedTitles(query, stats.mostRewatched))
    }

    private fun rewatchedTitles(query: GetStats, rewatched: List<RewatchedTitle>): List<StatsRewatchedTitle> {
        if (rewatched.isEmpty()) return emptyList()
        val titles = MediaType.entries.flatMap { type ->
            repository.titles(query.userId, type, rewatched.filter { it.type == type }.map { it.tmdbId }, query.lang)
        }.associateBy { it.type to it.tmdbId }
        return rewatched.mapNotNull { r -> titles[r.type to r.tmdbId]?.let { StatsRewatchedTitle(r, it) } }
    }

    private fun topTitles(query: GetStats, top: List<StatsEntry>): List<StatsTopTitle> {
        if (top.isEmpty()) return emptyList()
        val titles = MediaType.entries.flatMap { type ->
            repository.titles(query.userId, type, top.filter { it.type == type }.map { it.tmdbId }, query.lang)
        }.associateBy { it.type to it.tmdbId }
        return top.mapNotNull { entry -> titles[entry.type to entry.tmdbId]?.let { StatsTopTitle(entry, it) } }
    }

    companion object {
        const val FIRST_YEAR = 1900
    }
}
