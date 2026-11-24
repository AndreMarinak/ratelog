package org.ratelog.api.stats

import org.ratelog.MediaType
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.posterUrl
import org.ratelog.stats.GetStats
import org.ratelog.stats.GetStatsError
import org.ratelog.stats.GetStatsHandler
import org.ratelog.stats.StatsPeriod
import org.ratelog.stats.StatsTopTitle
import org.ratelog.user.User
import org.springframework.context.MessageSource
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.Locale

data class StatsResponse(
    /** `2026` or `"all"`. */
    val year: String,
    /** Years with something rated or seen, newest first. */
    val years: List<Int>,
    val timeZone: String,
    val totals: StatsTotalsDto,
    val hours: StatsHoursDto,
    /** Ten buckets, 1 to 10 (a score rounds to the nearest point; under 1.5 counts as 1). */
    val distribution: List<StatsBucketDto>,
    /** Months 1-12 of the year (`month`), or every year from the first to the last with data (`year`) for `all`. */
    val timeline: List<StatsTimelineDto>,
    val genres: List<StatsGenreDto>,
    val decades: List<StatsDecadeDto>,
    val generousGenre: StatsGenreDto?,
    val harshestGenre: StatsGenreDto?,
    val top: List<StatsTopDto>,
)

data class StatsTotalsDto(
    val moviesSeen: Int,
    val moviesRated: Int,
    val showsSeen: Int,
    val showsRated: Int,
    val seasonsRated: Int,
    val ratings: Int,
    val averageScore: Double?,
)

data class StatsHoursDto(
    /** Movies only: TV episode counts aren't stored. */
    val hours: Double,
    val minutes: Long,
    /** The hours are based on this many of `totals.moviesSeen` movies. */
    val moviesWithRuntime: Int,
    /** Movies whose runtime is being looked up in the background (ask again shortly). */
    val pending: Int,
    val includesTv: Boolean = false,
)

data class StatsBucketDto(val score: Int, val count: Int)

data class StatsTimelineDto(val month: Int?, val year: Int?, val movies: Int, val shows: Int, val total: Int)

data class StatsGenreDto(val id: Int, val name: String?, val count: Int, val rated: Int, val averageScore: Double?)

data class StatsDecadeDto(val decade: Int, val count: Int, val rated: Int, val averageScore: Double?)

data class StatsTopDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val url: String,
    /** Your score in the period (a show: the average of the seasons rated in it). */
    val score: Double,
    val ratedAt: String,
)

/**
 * `GET /api/v1/stats?year=YYYY|all`: the numbers of the Stats page (`/stats`) for the token's user, same rules. No
 * `year`: this year. See docs/api.md#stats.
 */
@RestController
@RequestMapping("/api/v1/stats")
class ApiStatsController(
    private val handler: GetStatsHandler,
    private val messages: MessageSource,
) {
    @GetMapping
    fun stats(
        @CurrentUser user: User,
        @RequestParam("year", required = false) year: String?,
    ): StatsResponse {
        val period = parse(year)
        val result = handler.handle(GetStats(user.id!!, period, user.metadataLang)).fold(
            { error ->
                when (error) {
                    is GetStatsError.InvalidYear -> throw invalidYear()
                }
            },
            { it },
        )
        val s = result.stats
        val t = s.totals
        return StatsResponse(
            year = s.period.param,
            years = s.years,
            timeZone = result.zone.id,
            totals = StatsTotalsDto(t.moviesSeen, t.moviesRated, t.showsSeen, t.showsRated, t.seasonsRated, t.ratings, t.averageScore?.round()),
            hours = StatsHoursDto(
                hours = (t.runtimeMinutes / 60.0).round(1),
                minutes = t.runtimeMinutes,
                moviesWithRuntime = t.runtimeKnown,
                pending = t.runtimePending,
            ),
            distribution = s.distribution.map { StatsBucketDto(it.score, it.count) },
            timeline = s.timeline.map {
                val byYear = s.period == StatsPeriod.AllTime
                StatsTimelineDto(if (byYear) null else it.key, if (byYear) it.key else null, it.movies, it.shows, it.total)
            },
            genres = s.genres.map { it.toDto() },
            decades = s.decades.map { StatsDecadeDto(it.decade, it.count, it.rated, it.averageScore?.round()) },
            generousGenre = s.generousGenre?.toDto(),
            harshestGenre = s.harshestGenre?.toDto(),
            top = result.top.map { it.toDto() },
        )
    }

    private fun parse(value: String?): StatsPeriod {
        val v = value?.trim()?.lowercase()
        if (v.isNullOrEmpty()) return StatsPeriod.Year(handler.currentYear())
        if (v == "all") return StatsPeriod.AllTime
        return StatsPeriod.Year(v.toIntOrNull() ?: throw invalidYear())
    }

    private fun invalidYear() =
        badRequest("invalid_year", "year must be \"all\" or a year from ${GetStatsHandler.FIRST_YEAR} to ${handler.currentYear()}")

    private fun org.ratelog.stats.GenreStat.toDto() =
        StatsGenreDto(genreId, genreName(genreId), count, rated, averageScore?.round())

    /** English names (the API's language, from messages.properties), e.g. "Science Fiction"; null for an unknown id. */
    private fun genreName(id: Int): String? = messages.getMessage("genre.$id", null, null, Locale.ENGLISH)

    private fun StatsTopTitle.toDto() = StatsTopDto(
        tmdbId = title.tmdbId,
        type = title.type.apiName(),
        title = title.title,
        year = title.year,
        posterUrl = title.posterPath?.let(::posterUrl),
        url = if (title.type == MediaType.movie) "/movie/${title.tmdbId}" else "/tv/${title.tmdbId}",
        score = (entry.score ?: 0.0).round(),
        ratedAt = Instant.ofEpochMilli(entry.atMs).toString(),
    )

    companion object {
        private fun Double.round(places: Int = 2): Double = BigDecimal.valueOf(this).setScale(places, RoundingMode.HALF_UP).toDouble()
    }
}
