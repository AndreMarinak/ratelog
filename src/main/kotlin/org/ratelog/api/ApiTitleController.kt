package org.ratelog.api

import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.media.ImportError
import org.ratelog.api.media.ImportException
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.media.MediaImporter
import org.ratelog.customlist.membership.ListMembershipRepository
import org.ratelog.customlist.saved.intitle.OfficialTitleListsOrder
import org.ratelog.customlist.saved.intitle.OfficialTitleListsRepository
import org.ratelog.extras.TitleCredits
import org.ratelog.extras.gettitleinfo.GetTitleInfo
import org.ratelog.extras.gettitleinfo.GetTitleInfoHandler
import org.ratelog.extras.gettitleinfo.TitleInfo
import org.ratelog.movie.Movie
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.tmdb.TmdbError
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.user.User
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.HttpClientErrorException
import org.ratelog.rewatch.RewatchRepository
import org.ratelog.rewatch.RewatchSummary
import org.ratelog.watchlist.seen.SeenStateReader

data class TitleDetailDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    val url: String,
    val tmdbVoteAverage: Double?,
    val tmdbVoteCount: Int?,
    /** Movies only. */
    val runtimeMinutes: Int?,
    /** TV only. */
    val episodeRuntimeMinutes: Int?,
    /** TV only. */
    val numberOfSeasons: Int?,
    val trailerUrl: String?,
    val imdbId: String?,
    val imdbUrl: String?,
    /** Null when MDBList is not configured or has no scores for the title. */
    val externalScores: ExternalScoresDto?,
    /** The caller's own rating, null when not rated. */
    val userRating: UserRatingDto?,
    /** The caller's own lists that contain the title (newest list first), empty when none. */
    val inLists: List<TitleListDto> = emptyList(),
    /** Fork (saved lists, 2026-11-24): every public official list with the title, the caller's saved ones first. */
    val officialLists: List<TitleOfficialListDto> = emptyList(),
    /** Director, writers, cast, age rating, tagline... (fork, 2026-11-24). Null when TMDB extras aren't available yet. */
    val credits: TitleCreditsDto? = null,
    /** Fork (2026-11-24, rewatch log): seen (rated, or status `watched`; a show by the TV rule). */
    val seen: Boolean = false,
    /** Times seen: 1 (the first watch) + rewatches; 0 when not seen. */
    val watchCount: Int = 0,
    /** The latest rewatch (ISO instant), null with none. */
    val lastWatchedAt: String? = null,
)

/** Same data as the title page's Details and Cast cards. Lists are empty and values null when TMDB doesn't know them. */
data class TitleCreditsDto(
    val tagline: String?,
    /** US age rating (R, PG-13, TV-MA...). */
    val certification: String?,
    /** Movies. */
    val directors: List<CreditPersonDto>,
    /** Movies: each person once, with their writing jobs ("Screenplay", "Novel", "Writer"...). */
    val writers: List<CreditPersonDto>,
    /** TV. */
    val createdBy: List<CreditPersonDto>,
    /** Top billed, at most 10. */
    val cast: List<CastMemberDto>,
    /** Movies: production companies (at most 3). */
    val studios: List<String>,
    /** TV (at most 3). */
    val networks: List<String>,
    /** Movies, US dollars. */
    val budget: Long?,
    val revenue: Long?,
    /** TV. */
    val firstAirYear: Int?,
    val lastAirYear: Int?,
    val numberOfEpisodes: Int?,
    /** TV: TMDB's status ("Returning Series", "Ended", "Canceled"...). */
    val status: String?,
)

data class CreditPersonDto(val tmdbId: Int?, val name: String, val jobs: List<String> = emptyList())

data class CastMemberDto(
    val tmdbId: Int?,
    val name: String,
    val character: String?,
    /** w185 photo on image.tmdb.org, null when TMDB has none. */
    val profileUrl: String?,
    /** TV: episodes this person is in. */
    val episodes: Int?,
)

/** A public official list that has the title; `rank` only on a numbered list; `saved` = the caller saved the list. */
data class TitleOfficialListDto(val id: Long, val name: String, val rank: Int?, val saved: Boolean, val url: String)

/** One of the caller's lists that has the title; `rank` is its place in the list (1 = first), as the list page shows it, or null on a plain list. */
data class TitleListDto(val id: Long, val name: String, val rank: Int?, val url: String)

data class ExternalScoresDto(
    val imdbRating: Double?,
    val imdbVotes: Long?,
    val rottenTomatoesCritics: Int?,
    val rottenTomatoesAudience: Int?,
    val metacritic: Int?,
    val letterboxd: Double?,
    val fetchedAt: String?,
    val source: String = "MDBList",
)

data class UserRatingDto(
    /** Movies: the rating's score. TV: the average over rated seasons, or the whole-show score when none is rated. 0-10. */
    val score: Double?,
    val reviewTitle: String?,
    val reviewText: String?,
    /** TV only. */
    val seasons: List<SeasonRatingDto>?,
    /** TV only (fork): the whole-show score; it is [score] only while no season is rated. */
    val showScore: Double? = null,
)

data class SeasonRatingDto(
    val season: Int,
    val score: Double,
    val reviewTitle: String?,
    val reviewText: String?,
)

@RestController
@RequestMapping("/api/v1/titles")
class ApiTitleController(
    private val mediaImporter: MediaImporter,
    private val getTitleInfoHandler: GetTitleInfoHandler,
    private val ratingRepository: RatingRepository,
    private val tvRatingRepository: TvRatingRepository,
    private val listMembershipRepository: ListMembershipRepository,
    private val officialTitleListsRepository: OfficialTitleListsRepository,
    /** Fork (2026-11-24, rewatch log): `seen`, `watchCount`, `lastWatchedAt`. */
    private val seenStateReader: SeenStateReader? = null,
    private val rewatchRepository: RewatchRepository? = null,
) {
    /** One title with TMDB extras, external scores (when available) and the caller's own rating. */
    @GetMapping("/{type}/{tmdbId}")
    fun title(@CurrentUser user: User, @PathVariable type: String, @PathVariable tmdbId: Int): TitleDetailDto {
        val mediaType = parseMediaType(type) ?: throw badRequest("invalid_type", "type must be \"movie\" or \"tv\"")
        if (tmdbId <= 0) throw badRequest("invalid_parameter", "tmdbId must be a positive integer")

        val local = mediaImporter.ensure(tmdbId, mediaType, user.metadataLang).getOrElse { throw importFailure(it, mediaType, tmdbId) }
        val info = getTitleInfoHandler.handle(GetTitleInfo(mediaType, TmdbId(tmdbId))).getOrNull()
        val inLists = listMembershipRepository.findListsContaining(user.id!!, local.type, local.id)
            .map { TitleListDto(it.listId.value, it.name, it.rank, "/list/${it.listId.value}") }
        val officialLists = OfficialTitleListsOrder.sort(officialTitleListsRepository.findContaining(user.id, local.type, local.id))
            .map { TitleOfficialListDto(it.listId.value, it.name, it.rank, it.saved, "/list/${it.listId.value}") }
        val seen = seenStateReader?.read(user.id, local.type, local.id)?.seen == true
        val rewatch = if (seen) rewatchRepository?.summary(user.id, local.type, local.id) ?: RewatchSummary.NONE else null
        return toDto(local, info, userRating(local, user), inLists).copy(
            officialLists = officialLists,
            seen = seen,
            watchCount = rewatch?.watchCount ?: 0,
            lastWatchedAt = rewatch?.lastAtEpochMs?.let { java.time.Instant.ofEpochMilli(it).toString() },
        )
    }

    private fun toDto(local: LocalMedia, info: TitleInfo?, userRating: UserRatingDto?, inLists: List<TitleListDto>) = TitleDetailDto(
        tmdbId = local.tmdbId,
        type = local.type.apiName(),
        title = local.title,
        year = local.year,
        posterUrl = local.posterPath?.let(::posterUrl),
        url = when (local.type) {
            MediaType.movie -> "/movie/${local.tmdbId}"
            MediaType.tvshow -> "/tv/${local.tmdbId}"
        },
        tmdbVoteAverage = info?.tmdbVoteAverage,
        tmdbVoteCount = info?.tmdbVoteCount,
        runtimeMinutes = info?.runtimeMinutes?.takeIf { local.type == MediaType.movie },
        episodeRuntimeMinutes = info?.runtimeMinutes?.takeIf { local.type == MediaType.tvshow },
        numberOfSeasons = info?.numberOfSeasons,
        trailerUrl = info?.trailerUrl,
        imdbId = info?.imdbId,
        imdbUrl = info?.imdbUrl,
        externalScores = info?.scores?.let { s ->
            ExternalScoresDto(
                imdbRating = s.imdbRating,
                imdbVotes = s.imdbVotes,
                rottenTomatoesCritics = s.rtCritics,
                rottenTomatoesAudience = s.rtAudience,
                metacritic = s.metacritic,
                letterboxd = s.letterboxd,
                fetchedAt = info.scoresFetchedAt?.toString(),
            )
        },
        userRating = userRating,
        inLists = inLists,
        credits = info?.credits?.let(::creditsDto),
    )

    private fun creditsDto(c: TitleCredits) = TitleCreditsDto(
        tagline = c.tagline,
        certification = c.certification,
        directors = c.directors.map { CreditPersonDto(it.id, it.name) },
        writers = c.writers.map { CreditPersonDto(it.id, it.name, it.jobs) },
        createdBy = c.createdBy.map { CreditPersonDto(it.id, it.name) },
        cast = c.cast.map { CastMemberDto(it.id, it.name, it.character, it.profilePath?.let { p -> "https://image.tmdb.org/t/p/w185$p" }, it.episodes) },
        studios = c.studios,
        networks = c.networks,
        budget = c.budget,
        revenue = c.revenue,
        firstAirYear = c.firstAirYear,
        lastAirYear = c.lastAirYear,
        numberOfEpisodes = c.numberOfEpisodes,
        status = c.status,
    )

    private fun userRating(local: LocalMedia, user: User): UserRatingDto? = when (local.type) {
        MediaType.movie -> ratingRepository.findByMovieIdAndUserId(Movie.Id(local.id), user.id!!)?.let { rating ->
            UserRatingDto(
                score = rating.score?.value,
                reviewTitle = rating.review?.title?.value,
                reviewText = rating.review?.text?.value,
                seasons = null,
            )
        }
        MediaType.tvshow -> tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(local.id), user.id!!)
            ?.takeIf { it.isRated }
            ?.let { rating ->
                UserRatingDto(
                    score = rating.score?.value,
                    reviewTitle = null,
                    reviewText = null,
                    seasons = rating.seasonRatings.sortedBy { it.seasonNumber.value }.map { s ->
                        SeasonRatingDto(s.seasonNumber.value, s.score.value, s.review?.title?.value, s.review?.text?.value)
                    },
                    showScore = rating.showScore?.value,
                )
            }
    }

    private fun importFailure(e: Throwable, type: MediaType, tmdbId: Int): ApiException = when {
        (e as? ImportException)?.error == ImportError.TmdbNotConfigured -> tmdbFailure(TmdbError.ApiKeyMissing)
        e is ImportException || e is HttpClientErrorException.NotFound ->
            notFound("No ${type.apiName()} with TMDB id $tmdbId")
        else -> ApiException(HttpStatus.BAD_GATEWAY, "tmdb_error", "TMDB lookup failed")
    }
}
