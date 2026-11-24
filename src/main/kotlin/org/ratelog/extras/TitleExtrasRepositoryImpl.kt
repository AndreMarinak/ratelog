package org.ratelog.extras

import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.springframework.stereotype.Repository
import java.time.Instant
import kotlin.jvm.optionals.getOrNull

@Repository
class TitleExtrasRepositoryImpl(
    private val dao: TitleExtrasDAO,
) : TitleExtrasRepository {

    override fun find(type: MediaType, tmdbId: TmdbId): TitleExtrasRecord? =
        dao.findByMediaTypeAndTmdbId(type.name, tmdbId.value).getOrNull()?.toDomain()

    override fun saveTmdb(type: MediaType, tmdbId: TmdbId, extras: TmdbExtras, fetchedAt: Instant, nextRefreshAt: Instant) =
        dao.upsertTmdb(
            mediaType = type.name,
            tmdbId = tmdbId.value,
            imdbId = extras.imdbId,
            title = extras.title?.take(500),
            latestReleaseDate = extras.latestReleaseDate,
            voteAverage = extras.voteAverage,
            voteCount = extras.voteCount,
            runtimeMinutes = extras.runtimeMinutes,
            numberOfSeasons = extras.numberOfSeasons,
            trailerYoutubeKey = extras.trailerYoutubeKey,
            creditsJson = extras.credits?.let { TitleCredits.toJson(it) },
            fetchedAtEpochMs = fetchedAt.toEpochMilli(),
            nextRefreshAtEpochMs = nextRefreshAt.toEpochMilli(),
        )

    override fun saveScores(type: MediaType, tmdbId: TmdbId, scores: ExternalScores?, fetchedAt: Instant, nextRefreshAt: Instant) =
        dao.upsertScores(
            mediaType = type.name,
            tmdbId = tmdbId.value,
            imdbId = scores?.imdbId,
            imdbRating = scores?.imdbRating,
            imdbVotes = scores?.imdbVotes,
            rtCritics = scores?.rtCritics,
            rtAudience = scores?.rtAudience,
            metacritic = scores?.metacritic,
            letterboxd = scores?.letterboxd,
            noData = scores == null,
            fetchedAtEpochMs = fetchedAt.toEpochMilli(),
            nextRefreshAtEpochMs = nextRefreshAt.toEpochMilli(),
        )

    override fun deferTmdb(type: MediaType, tmdbId: TmdbId, nextRefreshAt: Instant) =
        dao.deferTmdb(type.name, tmdbId.value, nextRefreshAt.toEpochMilli())

    override fun deferScores(type: MediaType, tmdbId: TmdbId, nextRefreshAt: Instant) =
        dao.deferScores(type.name, tmdbId.value, nextRefreshAt.toEpochMilli())

    private fun TitleExtrasEntity.toDomain() = TitleExtrasRecord(
        mediaType = MediaType.valueOf(mediaType),
        tmdbId = TmdbId(tmdbId),
        imdbId = imdbId,
        tmdb = tmdbFetchedAtEpochMs?.let {
            TmdbExtras(
                title = title,
                latestReleaseDate = latestReleaseDate,
                voteAverage = tmdbVoteAverage,
                voteCount = tmdbVoteCount,
                runtimeMinutes = runtimeMinutes,
                numberOfSeasons = numberOfSeasons,
                trailerYoutubeKey = trailerYoutubeKey,
                imdbId = imdbId,
                credits = TitleCredits.fromJson(creditsJson),
            )
        },
        tmdbFetchedAt = tmdbFetchedAtEpochMs?.let(Instant::ofEpochMilli),
        tmdbNextRefreshAt = tmdbNextRefreshAtEpochMs?.let(Instant::ofEpochMilli),
        scores = if (scoresFetchedAtEpochMs == null || scoresNoData) null else ExternalScores(
            imdbId = imdbId,
            imdbRating = imdbRating,
            imdbVotes = imdbVotes,
            rtCritics = rtCritics,
            rtAudience = rtAudience,
            metacritic = metacritic,
            letterboxd = letterboxd,
        ),
        scoresNoData = scoresNoData,
        scoresFetchedAt = scoresFetchedAtEpochMs?.let(Instant::ofEpochMilli),
        scoresNextRefreshAt = scoresNextRefreshAtEpochMs?.let(Instant::ofEpochMilli),
    )
}
