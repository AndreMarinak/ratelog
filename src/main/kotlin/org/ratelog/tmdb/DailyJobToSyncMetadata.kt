package org.ratelog.tmdb

import org.ratelog.movie.Movie
import org.ratelog.security.LogSafe
import org.ratelog.movie.MovieDescriptionRepository
import org.ratelog.movie.MovieRepository
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvDescriptionRepository
import org.ratelog.tvshow.TvShowRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Service
class DailyJobToSyncMetadata(
    private val tmdbClient: TmdbClient,
    private val tvShowRepository: TvShowRepository,
    private val movieRepository: MovieRepository,
    private val tvDescriptionRepository: TvDescriptionRepository,
    private val movieDescriptionRepository: MovieDescriptionRepository,
) {
    private val logger = LoggerFactory.getLogger(DailyJobToSyncMetadata::class.java)

    private val concurrencyLimit = 10

    @Scheduled(cron = "0 0 6 * * *")
    fun sync() {
        logger.info("DailyJobToSyncMetadata started")
        try {
            Executors.newFixedThreadPool(concurrencyLimit).use { executor ->
                syncTvShows(executor)
                syncMovies(executor)
            }
            logger.info("DailyJobToSyncMetadata completed successfully")
        } catch (e: Exception) {
            // Fork: class name only; TMDB request URLs carry api_key and I/O errors echo the URL.
            logger.error("DailyJobToSyncMetadata failed: {}", e.javaClass.simpleName)
        }
    }

    private fun syncTvShows(executor: ExecutorService) {
        val showsToSync = tvShowRepository.findActiveTvShows()

        val futures = showsToSync.map { show ->
            executor.submit { syncTvShow(show) }
        }

        futures.forEach { it.get() }
    }

    private fun syncMovies(executor: ExecutorService) {
        val moviesToSync = movieRepository.findActiveMovies()

        val futures = moviesToSync.map { movie ->
            executor.submit { syncMovie(movie) }
        }

        futures.forEach { it.get() }
    }

    private fun syncTvShow(show: TvShow) {
        try {
            tmdbClient.tvShowDetails(show.tmdbId)
                .fold(
                    { err -> logger.error("Failed to sync TV show {}: {}", show.tmdbId.value, err.javaClass.simpleName) },
                    { it.copy(id = show.id).let(tvShowRepository::save) }
                )

            if (!tvDescriptionRepository.existsAnyByTmdbId(show.tmdbId)) {
                tmdbClient.tvTranslations(show.tmdbId, show.originalName)
                    .fold(
                        { err -> logger.error("Failed to sync TV translations {}: {}", show.tmdbId.value, err.javaClass.simpleName) },
                        { tvDescriptionRepository.saveAll(it) }
                    )
            }
        } catch (e: Exception) {
            logger.error("Error syncing TV show {}: {}{}", show.tmdbId.value, e.javaClass.simpleName, LogSafe.statusSuffix(e))
        }
    }

    private fun syncMovie(movie: Movie) {
        try {
            tmdbClient.movieDetails(movie.tmdbId)
                .fold(
                    { err -> logger.error("Failed to sync movie {}: {}", movie.tmdbId.value, err.javaClass.simpleName) },
                    { it.copy(id = movie.id).let(movieRepository::save) }
                )

            if (!movieDescriptionRepository.existsAnyByTmdbId(movie.tmdbId)) {
                tmdbClient.movieTranslations(movie.tmdbId, movie.originalTitle)
                    .fold(
                        { err -> logger.error("Failed to sync movie translations {}: {}", movie.tmdbId.value, err.javaClass.simpleName) },
                        { movieDescriptionRepository.saveAll(it) }
                    )
            }
        } catch (e: Exception) {
            logger.error("Error syncing movie {}: {}{}", movie.tmdbId.value, e.javaClass.simpleName, LogSafe.statusSuffix(e))
        }
    }
}
