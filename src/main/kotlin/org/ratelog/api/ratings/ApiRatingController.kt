package org.ratelog.api.ratings

import com.fasterxml.jackson.annotation.JsonInclude
import org.ratelog.CategoryScores
import org.ratelog.MediaType
import org.ratelog.Review
import org.ratelog.Score
import org.ratelog.SeasonNumber
import org.ratelog.annotations.CurrentUser
import org.ratelog.api.ApiException
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.media.ImportError
import org.ratelog.api.media.ImportException
import org.ratelog.api.media.LocalMedia
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.notFound
import org.ratelog.api.parseMediaType
import org.ratelog.api.posterUrl
import org.ratelog.api.tmdbFailure
import org.ratelog.movie.Movie
import org.ratelog.movie.rating.Rating
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.movie.rating.add.AddRating
import org.ratelog.movie.rating.add.AddRatingHandler
import org.ratelog.movie.rating.add.AddRatingHandlerError
import org.ratelog.movie.rating.delete.DeleteRating
import org.ratelog.movie.rating.delete.DeleteRatingHandler
import org.ratelog.movie.rating.update.UpdateRating
import org.ratelog.movie.rating.update.UpdateRatingHandler
import org.ratelog.movie.rating.update.UpdateRatingHandlerError
import org.ratelog.tmdb.TmdbError
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.tvshow.rating.SeasonRating
import org.ratelog.tvshow.rating.TvRating
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.tvshow.rating.addseason.AddSeasonRating
import org.ratelog.tvshow.rating.addseason.AddSeasonRatingHandler
import org.ratelog.tvshow.rating.addseason.AddSeasonRatingHandlerError
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRating
import org.ratelog.tvshow.rating.deleteseason.DeleteSeasonRatingHandler
import org.ratelog.tvshow.rating.updateseason.UpdateSeasonRating
import org.ratelog.tvshow.rating.updateseason.UpdateSeasonRatingHandler
import org.ratelog.tvshow.rating.updateseason.UpdateSeasonRatingHandlerError
import org.ratelog.user.User
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import kotlin.math.roundToLong

/**
 * Body of a PUT: `score` alone (quick rating) or all five categories (detailed rating).
 * Review fields that are left out keep the saved review; an empty string clears that field.
 */
data class RatingRequest(
    val score: Double? = null,
    val directing: Double? = null,
    val cinematography: Double? = null,
    val acting: Double? = null,
    val soundtrack: Double? = null,
    val screenplay: Double? = null,
    val reviewTitle: String? = null,
    val reviewText: String? = null,
)

data class CategoriesDto(
    val directing: Double,
    val cinematography: Double,
    val acting: Double,
    val soundtrack: Double,
    val screenplay: Double,
)

data class MovieRatingDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    /** quick | detailed */
    val mode: String,
    val score: Double?,
    val categories: CategoriesDto?,
    val reviewTitle: String?,
    val reviewText: String?,
    val createdAt: String,
    val url: String,
)

data class SeasonRatingDto(
    val season: Int,
    /** quick | detailed */
    val mode: String,
    val score: Double,
    val categories: CategoriesDto?,
    val reviewTitle: String?,
    val reviewText: String?,
    val createdAt: String,
)

data class TvRatingDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    /** Average of the rated seasons. */
    val score: Double?,
    val seasons: List<SeasonRatingDto>,
    val url: String,
)

/** A list left out by the `type` filter is omitted. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class RatingsResponse(
    val movies: List<MovieRatingDto>?,
    val tv: List<TvRatingDto>?,
)

/** Rate movies and TV seasons, quick (one score) or detailed (five categories). */
@RestController
@RequestMapping("/api/v1/ratings")
class ApiRatingController(
    private val mediaImporter: MediaImporter,
    private val ratingRepository: RatingRepository,
    private val tvRatingRepository: TvRatingRepository,
    private val tvShowRepository: TvShowRepository,
    private val addRatingHandler: AddRatingHandler,
    private val updateRatingHandler: UpdateRatingHandler,
    private val deleteRatingHandler: DeleteRatingHandler,
    private val addSeasonRatingHandler: AddSeasonRatingHandler,
    private val updateSeasonRatingHandler: UpdateSeasonRatingHandler,
    private val deleteSeasonRatingHandler: DeleteSeasonRatingHandler,
) {
    /** The user's ratings, highest score first. */
    @GetMapping
    fun list(@CurrentUser user: User, @RequestParam(required = false) type: String?): RatingsResponse {
        val mediaType = parseMediaType(type)
        val movies = if (mediaType == null || mediaType == MediaType.movie) {
            ratingRepository.findRankedByUserIdWithFilters(user.id!!, null, MAX_LISTED, null, null).mapNotNull { (_, rating) ->
                mediaImporter.findLocalById(rating.movieId.value, MediaType.movie, user.metadataLang)?.let { rating.toDto(it) }
            }
        } else null
        val tv = if (mediaType == null || mediaType == MediaType.tvshow) {
            tvRatingRepository.findRankedByUserIdWithFilters(user.id!!, null, MAX_LISTED, null, null).mapNotNull { (_, rating) ->
                mediaImporter.findLocalById(rating.tvShowId.value, MediaType.tvshow, user.metadataLang)?.let { rating.toDto(it) }
            }
        } else null
        return RatingsResponse(movies, tv)
    }

    @GetMapping("/movie/{tmdbId}")
    fun movie(@CurrentUser user: User, @PathVariable tmdbId: Int): MovieRatingDto {
        val media = localOrNotRated(tmdbId, MediaType.movie, user)
        val rating = ratingRepository.findByMovieIdAndUserId(Movie.Id(media.id), user.id!!) ?: throw notRated(MediaType.movie, tmdbId)
        return rating.toDto(media)
    }

    /** Creates or replaces the rating. 201 when it is new, 200 when it replaced one. */
    @PutMapping("/movie/{tmdbId}")
    fun rateMovie(@CurrentUser user: User, @PathVariable tmdbId: Int, @RequestBody body: RatingRequest): ResponseEntity<MovieRatingDto> {
        val media = ensureLocal(tmdbId, MediaType.movie, user)
        val movieId = Movie.Id(media.id)
        val existing = ratingRepository.findByMovieIdAndUserId(movieId, user.id!!)
        if (existing == null) {
            addRatingHandler.handle(
                AddRating(
                    movieId = movieId,
                    userId = user.id,
                    directing = body.directing,
                    cinematography = body.cinematography,
                    acting = body.acting,
                    soundtrack = body.soundtrack,
                    screenplay = body.screenplay,
                    reviewTitle = body.reviewTitle?.takeIf { it.isNotBlank() },
                    reviewText = body.reviewText?.takeIf { it.isNotBlank() },
                    score = body.score,
                )
            ).onLeft { throw it.toApiException() }
        } else {
            updateRatingHandler.handle(
                UpdateRating(
                    movieId = movieId,
                    userId = user.id,
                    directing = body.directing,
                    cinematography = body.cinematography,
                    acting = body.acting,
                    soundtrack = body.soundtrack,
                    screenplay = body.screenplay,
                    reviewTitle = reviewTitle(body, existing.review),
                    reviewText = reviewText(body, existing.review),
                    score = body.score,
                )
            ).onLeft { throw it.toApiException() }
        }
        val saved = ratingRepository.findByMovieIdAndUserId(movieId, user.id)!!
        return ResponseEntity.status(if (existing == null) HttpStatus.CREATED else HttpStatus.OK).body(saved.toDto(media))
    }

    @DeleteMapping("/movie/{tmdbId}")
    fun deleteMovie(@CurrentUser user: User, @PathVariable tmdbId: Int): ResponseEntity<Unit> {
        val media = localOrNotRated(tmdbId, MediaType.movie, user)
        deleteRatingHandler.handle(DeleteRating(Movie.Id(media.id), user.id!!))
            .onLeft { throw notRated(MediaType.movie, tmdbId) }
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/tv/{tmdbId}")
    fun show(@CurrentUser user: User, @PathVariable tmdbId: Int): TvRatingDto {
        val media = localOrNotRated(tmdbId, MediaType.tvshow, user)
        val rating = tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(media.id), user.id!!) ?: throw notRated(MediaType.tvshow, tmdbId)
        return rating.toDto(media)
    }

    /** Creates or replaces one season's rating; returns the whole show. 201 when the season is new, 200 otherwise. */
    @PutMapping("/tv/{tmdbId}/seasons/{season}")
    fun rateSeason(
        @CurrentUser user: User,
        @PathVariable tmdbId: Int,
        @PathVariable season: Int,
        @RequestBody body: RatingRequest,
    ): ResponseEntity<TvRatingDto> {
        checkSeason(season)
        val media = ensureLocal(tmdbId, MediaType.tvshow, user)
        val showId = TvShow.Id(media.id)
        val lastSeason = tvShowRepository.findById(showId)?.lastSeasonNumber
        if (lastSeason != null && season > lastSeason) {
            throw badRequest("invalid_season", "${media.title} has $lastSeason season(s) on TMDB")
        }

        val seasonNumber = SeasonNumber(season)
        val existing = tvRatingRepository.findByTvShowIdAndUserId(showId, user.id!!)
            ?.seasonRatings?.find { it.seasonNumber == seasonNumber }
        if (existing == null) {
            addSeasonRatingHandler.handle(
                AddSeasonRating(
                    tvShowId = showId,
                    seasonNumber = seasonNumber,
                    userId = user.id,
                    directing = body.directing.toScore(),
                    cinematography = body.cinematography.toScore(),
                    acting = body.acting.toScore(),
                    soundtrack = body.soundtrack.toScore(),
                    screenplay = body.screenplay.toScore(),
                    reviewTitle = body.reviewTitle?.takeIf { it.isNotBlank() },
                    reviewText = body.reviewText?.takeIf { it.isNotBlank() },
                    score = body.score.toScore(),
                )
            ).onLeft { throw it.toApiException() }
        } else {
            updateSeasonRatingHandler.handle(
                UpdateSeasonRating(
                    tvShowId = showId,
                    seasonNumber = seasonNumber,
                    userId = user.id,
                    directing = body.directing,
                    cinematography = body.cinematography,
                    acting = body.acting,
                    soundtrack = body.soundtrack,
                    screenplay = body.screenplay,
                    reviewTitle = reviewTitle(body, existing.review),
                    reviewText = reviewText(body, existing.review),
                    score = body.score,
                )
            ).onLeft { throw it.toApiException() }
        }
        val saved = tvRatingRepository.findByTvShowIdAndUserId(showId, user.id)!!
        return ResponseEntity.status(if (existing == null) HttpStatus.CREATED else HttpStatus.OK).body(saved.toDto(media))
    }

    /** Deleting the last rated season removes the show's rating too. */
    @DeleteMapping("/tv/{tmdbId}/seasons/{season}")
    fun deleteSeason(@CurrentUser user: User, @PathVariable tmdbId: Int, @PathVariable season: Int): ResponseEntity<Unit> {
        checkSeason(season)
        val media = localOrNotRated(tmdbId, MediaType.tvshow, user)
        val showId = TvShow.Id(media.id)
        val rated = tvRatingRepository.findByTvShowIdAndUserId(showId, user.id!!)
            ?.seasonRatings?.any { it.seasonNumber.value == season } ?: false
        if (!rated) throw notFound("You have not rated season $season of tv $tmdbId")
        deleteSeasonRatingHandler.handle(DeleteSeasonRating(showId, SeasonNumber(season), user.id))
            .onLeft { throw notFound("You have not rated season $season of tv $tmdbId") }
        return ResponseEntity.noContent().build()
    }

    private fun ensureLocal(tmdbId: Int, type: MediaType, user: User): LocalMedia {
        checkTmdbId(tmdbId)
        return mediaImporter.ensure(tmdbId, type, user.metadataLang).getOrElse { e ->
            throw when ((e as? ImportException)?.error) {
                ImportError.TmdbNotConfigured -> tmdbFailure(TmdbError.ApiKeyMissing)
                ImportError.NotFoundOnTmdb -> notFound("TMDB has no ${type.apiName()} with id $tmdbId")
                null -> e
            }
        }
    }

    /** Only looks locally: a title that was never saved can't have a rating. */
    private fun localOrNotRated(tmdbId: Int, type: MediaType, user: User): LocalMedia {
        checkTmdbId(tmdbId)
        return mediaImporter.findLocal(tmdbId, type, user.metadataLang) ?: throw notRated(type, tmdbId)
    }

    private fun checkTmdbId(tmdbId: Int) {
        if (tmdbId <= 0) throw badRequest("invalid_parameter", "tmdbId must be a positive integer")
    }

    private fun checkSeason(season: Int) {
        if (season < 1) throw badRequest("invalid_season", "season must be 1 or higher")
    }

    private fun notRated(type: MediaType, tmdbId: Int) = notFound("You have not rated ${type.apiName()} $tmdbId")

    // On update, a review field left out of the body keeps its saved value; "" clears it.
    private fun reviewTitle(body: RatingRequest, saved: Review?): String? =
        if (body.reviewTitle == null) saved?.title?.value else body.reviewTitle.takeIf { it.isNotBlank() }

    private fun reviewText(body: RatingRequest, saved: Review?): String? =
        if (body.reviewText == null) saved?.text?.value else body.reviewText.takeIf { it.isNotBlank() }

    private fun Double?.toScore(): Score? = this?.let { if (it in 0.0..10.0) Score(it) else throw invalidScore() }

    private fun AddRatingHandlerError.toApiException(): ApiException = when (this) {
        AddRatingHandlerError.InvalidRatingValue -> invalidScore()
        AddRatingHandlerError.InvalidRatingCombination -> invalidCombination()
        AddRatingHandlerError.InvalidReviewTitle -> invalidReviewTitle()
        AddRatingHandlerError.InvalidReviewText -> invalidReviewText()
        AddRatingHandlerError.RatingAlreadyExists -> alreadyRated()
        AddRatingHandlerError.MovieNotFound -> notFound("Movie not found")
    }

    private fun UpdateRatingHandlerError.toApiException(): ApiException = when (this) {
        UpdateRatingHandlerError.InvalidRatingValue -> invalidScore()
        UpdateRatingHandlerError.InvalidRatingCombination -> invalidCombination()
        UpdateRatingHandlerError.InvalidReviewTitle -> invalidReviewTitle()
        UpdateRatingHandlerError.InvalidReviewText -> invalidReviewText()
        UpdateRatingHandlerError.RatingNotFound -> notFound("Rating not found")
    }

    private fun AddSeasonRatingHandlerError.toApiException(): ApiException = when (this) {
        AddSeasonRatingHandlerError.InvalidRatingValue -> invalidScore()
        AddSeasonRatingHandlerError.InvalidRatingCombination -> invalidCombination()
        AddSeasonRatingHandlerError.InvalidReviewTitle -> invalidReviewTitle()
        AddSeasonRatingHandlerError.InvalidReviewText -> invalidReviewText()
        AddSeasonRatingHandlerError.RatingAlreadyExists -> alreadyRated()
        AddSeasonRatingHandlerError.TvShowNotFound -> notFound("TV show not found")
    }

    private fun UpdateSeasonRatingHandlerError.toApiException(): ApiException = when (this) {
        UpdateSeasonRatingHandlerError.InvalidRatingValue -> invalidScore()
        UpdateSeasonRatingHandlerError.InvalidRatingCombination -> invalidCombination()
        UpdateSeasonRatingHandlerError.InvalidReviewTitle -> invalidReviewTitle()
        UpdateSeasonRatingHandlerError.InvalidReviewText -> invalidReviewText()
        UpdateSeasonRatingHandlerError.RatingNotFound,
        UpdateSeasonRatingHandlerError.SeasonRatingNotFound -> notFound("Rating not found")
    }

    private fun invalidScore() = badRequest("invalid_score", "Scores must be numbers from 0 to 10")

    private fun invalidCombination() = badRequest(
        "invalid_rating",
        "Send either \"score\" alone (quick rating) or all five of directing, cinematography, acting, soundtrack " +
            "and screenplay without \"score\" (detailed rating)",
    )

    private fun invalidReviewTitle() = badRequest("invalid_review", "reviewTitle must be at most 100 characters")

    private fun invalidReviewText() = badRequest("invalid_review", "reviewText must be at most 5000 characters")

    private fun alreadyRated() = ApiException(HttpStatus.CONFLICT, "conflict", "The rating was created by another request; retry")

    companion object {
        /** Upper bound for GET /ratings; a personal library stays well below it. */
        const val MAX_LISTED = 10_000
    }
}

private fun Rating.toDto(media: LocalMedia) = MovieRatingDto(
    tmdbId = media.tmdbId,
    type = MediaType.movie.apiName(),
    title = media.title,
    year = media.year,
    posterUrl = media.posterPath?.let(::posterUrl),
    mode = if (isQuick) "quick" else "detailed",
    score = score?.value?.round2(),
    categories = categories?.toDto(),
    reviewTitle = review?.title?.value,
    reviewText = review?.text?.value,
    createdAt = createdAt.toString(),
    url = "/movie/${media.tmdbId}",
)

private fun TvRating.toDto(media: LocalMedia) = TvRatingDto(
    tmdbId = media.tmdbId,
    type = MediaType.tvshow.apiName(),
    title = media.title,
    year = media.year,
    posterUrl = media.posterPath?.let(::posterUrl),
    score = score?.value?.round2(),
    seasons = seasonRatings.sortedBy { it.seasonNumber.value }.map { it.toDto() },
    url = "/tv/${media.tmdbId}",
)

private fun SeasonRating.toDto() = SeasonRatingDto(
    season = seasonNumber.value,
    mode = if (isQuick) "quick" else "detailed",
    score = score.value.round2(),
    categories = categories?.toDto(),
    reviewTitle = review?.title?.value,
    reviewText = review?.text?.value,
    createdAt = createdAt.toString(),
)

private fun CategoryScores.toDto() = CategoriesDto(
    directing = directing.value.round2(),
    cinematography = cinematography.value.round2(),
    acting = acting.value.round2(),
    soundtrack = soundtrack.value.round2(),
    screenplay = screenplay.value.round2(),
)

/** Category columns are REAL in the DB, so 8.3 comes back as 8.300000190734863 without this. */
private fun Double.round2(): Double = (this * 100).roundToLong() / 100.0
