package org.ratelog.security

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Status
import org.ratelog.api.ApiExceptionHandler
import org.ratelog.system.GlobalExceptionHandler
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.tmdb.DailyJobToSyncMetadata
import org.ratelog.tmdb.TmdbClient
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.client.ResourceAccessException
import java.util.concurrent.ExecutionException

/** Fork: TMDB request URLs carry the API key; no log line or logged stack trace may contain it. */
class SecretLoggingTest {

    private val secret = "SECRETKEY0123456789"
    private val tmdbFailure = ResourceAccessException("I/O error on GET request for \"https://api.themoviedb.org/3/movie/603?api_key=$secret\": Connection reset")
    private val appenders = mutableListOf<Pair<Logger, ListAppender<ILoggingEvent>>>()

    private fun capture(type: Class<*>): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(type) as Logger
        return ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
            appenders += logger to it
        }
    }

    @AfterEach
    fun tearDown() = appenders.forEach { (logger, appender) -> logger.detachAppender(appender) }

    private fun everything(events: List<ILoggingEvent>): String = events.joinToString("\n") { event ->
        event.formattedMessage + generateSequence(event.throwableProxy) { it.cause }.joinToString("") { proxy: IThrowableProxy ->
            "\n" + proxy.className + ": " + proxy.message
        }
    }

    @Test
    fun `the daily sync job logs the failing title and exception class only`() {
        val tmdb: TmdbClient = mock()
        whenever(tmdb.movieDetails(any())).thenThrow(tmdbFailure)
        whenever(tmdb.tvShowDetails(any())).thenThrow(tmdbFailure)
        val movies = InMemoryMovieRepository().apply { save(MovieFactory.aMovie(id = 1, tmdbId = 603, originalTitle = "The Matrix", status = Status.POST_PRODUCTION)) }
        val shows = InMemoryTvShowRepository().apply { save(TvShowFactory.aTvShow(id = 1, tmdbId = 1396, originalName = "Breaking Bad", status = Status.RETURNING_SERIES)) }
        val events = capture(DailyJobToSyncMetadata::class.java)

        DailyJobToSyncMetadata(tmdb, shows, movies, InMemoryTvDescriptionRepository(), InMemoryMovieDescriptionRepository()).sync()

        val logged = everything(events.list)
        assertTrue(logged.contains("Error syncing movie 603: ResourceAccessException"), logged)
        assertTrue(logged.contains("Error syncing TV show 1396: ResourceAccessException"), logged)
        assertFalse(logged.contains(secret))
        assertFalse(logged.contains("api.themoviedb.org"))
    }

    @Test
    fun `unexpected web errors are logged with a redacted stack trace`() {
        val events = capture(GlobalExceptionHandler::class.java)
        val request = MockHttpServletRequest("GET", "/movie/603")

        val response = GlobalExceptionHandler().handleGeneric(ExecutionException(tmdbFailure), request)

        assertEquals(500, response.statusCode.value())
        val logged = everything(events.list)
        assertTrue(logged.contains("Unhandled ExecutionException on GET /movie/603"), logged)
        assertTrue(logged.contains("org.springframework.web.client.ResourceAccessException"), "the cause chain is kept")
        assertTrue(logged.contains("api_key=***"))
        assertFalse(logged.contains(secret))
    }

    @Test
    fun `unexpected API errors are logged with a redacted stack trace`() {
        val events = capture(ApiExceptionHandler::class.java)

        ApiExceptionHandler().handleGeneric(IllegalStateException("lookup failed", tmdbFailure))

        val logged = everything(events.list)
        assertTrue(logged.contains("Unhandled API error: IllegalStateException"), logged)
        assertFalse(logged.contains(secret))
    }
}
