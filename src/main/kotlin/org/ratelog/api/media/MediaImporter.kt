package org.ratelog.api.media

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieDescriptionRepository
import org.ratelog.movie.MovieRepository
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tvshow.TvDescriptionRepository
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.springframework.stereotype.Component

/** A movie or show that exists in Ratelog's own tables (so it can go into lists and be rated). */
data class LocalMedia(
    val id: Long,
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val year: Int?,
    val posterPath: String?,
)

sealed interface ImportError {
    data object NotFoundOnTmdb : ImportError
    data object TmdbNotConfigured : ImportError
}

/**
 * Makes sure a TMDB title is saved locally, the same way opening its detail page does
 * (details + translations), and returns it. Existing rows are reused untouched.
 */
@Component
class MediaImporter(
    private val tmdbClient: TmdbClient,
    private val movieRepository: MovieRepository,
    private val movieDescriptionRepository: MovieDescriptionRepository,
    private val tvShowRepository: TvShowRepository,
    private val tvDescriptionRepository: TvDescriptionRepository,
) {
    fun ensure(tmdbId: Int, type: MediaType, lang: Lang): Result<LocalMedia> = runCatching {
        when (type) {
            MediaType.movie -> ensureMovie(TmdbId(tmdbId), lang)
            MediaType.tvshow -> ensureShow(TmdbId(tmdbId), lang)
        }
    }

    fun findLocal(tmdbId: Int, type: MediaType, lang: Lang): LocalMedia? = when (type) {
        MediaType.movie -> movieRepository.findByTmdbId(TmdbId(tmdbId))?.let { toLocal(it, lang) }
        MediaType.tvshow -> tvShowRepository.findByTmdbId(TmdbId(tmdbId))?.let { toLocal(it, lang) }
    }

    fun findLocalById(id: Long, type: MediaType, lang: Lang): LocalMedia? = when (type) {
        MediaType.movie -> movieRepository.findById(Movie.Id(id))?.let { toLocal(it, lang) }
        MediaType.tvshow -> tvShowRepository.findById(TvShow.Id(id))?.let { toLocal(it, lang) }
    }

    private fun ensureMovie(tmdbId: TmdbId, lang: Lang): LocalMedia {
        val movie = movieRepository.findByTmdbId(tmdbId)
            ?: tmdbClient.movieDetails(tmdbId).fold({ throw ImportException(it.toImportError()) }, movieRepository::save)
        if (!movieDescriptionRepository.existsAnyByTmdbId(movie.tmdbId)) {
            tmdbClient.movieTranslations(movie.tmdbId, movie.originalTitle).onRight(movieDescriptionRepository::saveAll)
        }
        return toLocal(movie, lang)
    }

    private fun ensureShow(tmdbId: TmdbId, lang: Lang): LocalMedia {
        val show = tvShowRepository.findByTmdbId(tmdbId)
            ?: tmdbClient.tvShowDetails(tmdbId).fold({ throw ImportException(it.toImportError()) }, tvShowRepository::save)
        if (!tvDescriptionRepository.existsAnyByTmdbId(show.tmdbId)) {
            tmdbClient.tvTranslations(show.tmdbId, show.originalName).onRight(tvDescriptionRepository::saveAll)
        }
        return toLocal(show, lang)
    }

    private fun toLocal(movie: Movie, lang: Lang) = LocalMedia(
        id = movie.id!!.value,
        tmdbId = movie.tmdbId.value,
        type = MediaType.movie,
        title = (movieDescriptionRepository.findByTmdbIdAndLang(movie.tmdbId, lang)
            ?: movieDescriptionRepository.findByTmdbIdAndLang(movie.tmdbId, Lang.en))?.title?.value
            ?: movie.originalTitle.value,
        year = movie.releaseYear,
        posterPath = movie.posterPath?.value,
    )

    private fun toLocal(show: TvShow, lang: Lang) = LocalMedia(
        id = show.id!!.value,
        tmdbId = show.tmdbId.value,
        type = MediaType.tvshow,
        title = (tvDescriptionRepository.findByTmdbIdAndLang(show.tmdbId, lang)
            ?: tvDescriptionRepository.findByTmdbIdAndLang(show.tmdbId, Lang.en))?.name?.value
            ?: show.originalName.value,
        year = show.firstAirYear,
        posterPath = show.posterPath?.value,
    )

    private fun org.ratelog.tmdb.TmdbError.toImportError(): ImportError = when (this) {
        org.ratelog.tmdb.TmdbError.ApiKeyMissing -> ImportError.TmdbNotConfigured
        else -> ImportError.NotFoundOnTmdb
    }
}

class ImportException(val error: ImportError) : RuntimeException(error.toString())
