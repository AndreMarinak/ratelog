package org.ratelog.extras.gettitleinfo

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.extras.ExternalScores
import org.ratelog.extras.ExtrasTaskRunner
import org.ratelog.extras.TitleExtrasRefresher
import org.ratelog.extras.TmdbExtras
import org.ratelog.extras.TmdbExtrasClient
import org.ratelog.extras.TmdbExtrasError
import org.ratelog.extras.mdblist.MdbListClient
import org.ratelog.extras.mdblist.MdbListError
import org.ratelog.test.InMemoryTitleExtrasRepository
import org.ratelog.test.MutableClock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Executor

class GetTitleInfoHandlerTest {

    private val movie = TmdbId(603)
    private val query = GetTitleInfo(MediaType.movie, movie)

    private val clock = MutableClock()
    private val tmdbExtrasClient: TmdbExtrasClient = mock()
    private val mdbListClient: MdbListClient = mock()
    private lateinit var repository: InMemoryTitleExtrasRepository

    /** Tasks queued here only run when the test says so ("background"). */
    private val queued = mutableListOf<Runnable>()
    private val queueingExecutor = Executor { queued += it }
    private val inlineExecutor = Executor { it.run() }

    private val matrixExtras = TmdbExtras(
        title = "The Matrix",
        latestReleaseDate = LocalDate.parse("1999-03-31"),
        voteAverage = 8.2,
        voteCount = 26_543,
        runtimeMinutes = 136,
        numberOfSeasons = null,
        trailerYoutubeKey = "vKQi3bBA1y8",
        imdbId = "tt0133093",
    )
    private val matrixScores = ExternalScores(
        imdbId = "tt0133093", imdbRating = 8.7, imdbVotes = 2_134_567,
        rtCritics = 83, rtAudience = 85, metacritic = 73, letterboxd = 4.2,
    )

    @BeforeEach
    fun setUp() {
        repository = InMemoryTitleExtrasRepository()
        whenever(mdbListClient.isConfigured()).thenReturn(true)
        whenever(mdbListClient.isPaused()).thenReturn(false)
        whenever(tmdbExtrasClient.fetch(MediaType.movie, movie)).thenReturn(matrixExtras.right())
        whenever(mdbListClient.fetch(MediaType.movie, movie)).thenReturn(matrixScores.right())
    }

    private fun handler(executor: Executor = inlineExecutor, syncTimeoutMs: Long = 3000) = GetTitleInfoHandler(
        repository = repository,
        refresher = TitleExtrasRefresher(repository, tmdbExtrasClient, mdbListClient, clock),
        runner = ExtrasTaskRunner(executor),
        mdbListClient = mdbListClient,
        syncTimeoutMs = syncTimeoutMs,
        clock = clock,
    )

    private fun runQueued() {
        val tasks = queued.toList()
        queued.clear()
        tasks.forEach { it.run() }
    }

    @Test
    fun `given a title never fetched when viewed then fetches TMDB and MDBList now and caches both`() {
        // When
        val info = handler().handle(query).getOrNull()!!

        // Then
        assertEquals(8.2, info.tmdbVoteAverage)
        assertEquals(26_543, info.tmdbVoteCount)
        assertEquals(136, info.runtimeMinutes)
        assertEquals("https://www.youtube.com/watch?v=vKQi3bBA1y8", info.trailerUrl)
        assertEquals("https://www.imdb.com/title/tt0133093/", info.imdbUrl)
        assertEquals(matrixScores, info.scores)

        val cached = repository.find(MediaType.movie, movie)!!
        assertEquals(clock.now + Duration.ofDays(7), cached.tmdbNextRefreshAt)
        assertEquals(clock.now + Duration.ofDays(30), cached.scoresNextRefreshAt, "released in 1999: monthly")
    }

    @Test
    fun `given a fresh cache when viewed then calls nobody`() {
        // Given
        handler().handle(query)
        clock.advance(Duration.ofDays(6))

        // When
        val info = handler().handle(query).getOrNull()!!

        // Then
        assertEquals(matrixScores, info.scores)
        verify(tmdbExtrasClient, times(1)).fetch(MediaType.movie, movie)
        verify(mdbListClient, times(1)).fetch(MediaType.movie, movie)
    }

    @Test
    fun `given stale scores when viewed then renders the cached ones and refreshes in the background`() {
        // Given
        handler().handle(query)
        clock.advance(Duration.ofDays(31))
        whenever(mdbListClient.fetch(MediaType.movie, movie)).thenReturn(matrixScores.copy(imdbRating = 8.8).right())
        val handler = handler(queueingExecutor)

        // When
        val info = handler.handle(query).getOrNull()!!

        // Then: the page got the cached values without waiting
        assertEquals(8.7, info.scores?.imdbRating)
        assertEquals(1, queued.size)
        verify(mdbListClient, times(1)).fetch(MediaType.movie, movie)

        // And the background refresh updates both stale halves
        runQueued()
        assertEquals(8.8, repository.find(MediaType.movie, movie)!!.scores?.imdbRating)
        assertEquals(8.8, handler.handle(query).getOrNull()!!.scores?.imdbRating)
        verify(tmdbExtrasClient, times(2)).fetch(MediaType.movie, movie)
    }

    @Test
    fun `given only TMDB data is stale when viewed then MDBList is not called`() {
        // Given: TMDB refreshes weekly, an old title's scores monthly
        handler().handle(query)
        clock.advance(Duration.ofDays(8))

        // When
        handler().handle(query)

        // Then
        verify(tmdbExtrasClient, times(2)).fetch(MediaType.movie, movie)
        verify(mdbListClient, times(1)).fetch(MediaType.movie, movie)
    }

    @Test
    fun `given the first fetch is slower than the timeout when viewed then renders without it and caches it later`() {
        // Given
        val handler = handler(queueingExecutor, syncTimeoutMs = 20)

        // When
        val result = handler.handle(query)

        // Then
        assertEquals(GetTitleInfoError.NotAvailable, result.leftOrNull())
        assertEquals(1, queued.size, "the fetch keeps going in the background")

        // And when it finishes, the next view is served from the cache
        runQueued()
        assertEquals(matrixScores, handler.handle(query).getOrNull()?.scores)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `given a view while the same refresh is running then it joins it instead of starting another`() {
        // Given
        val handler = handler(queueingExecutor, syncTimeoutMs = 10)
        handler.handle(query)

        // When
        handler.handle(query)

        // Then
        assertEquals(1, queued.size)
    }

    @Test
    fun `given no MDBLIST_API_KEY when viewed then scores are hidden and MDBList is never called`() {
        // Given
        whenever(mdbListClient.isConfigured()).thenReturn(false)

        // When
        val info = handler().handle(query).getOrNull()!!

        // Then
        assertNull(info.scores)
        assertEquals(8.2, info.tmdbVoteAverage)
        verify(mdbListClient, never()).fetch(any(), any())
        assertNull(repository.find(MediaType.movie, movie)!!.scoresNextRefreshAt)
    }

    @Test
    fun `given scores were cached but the key was removed when viewed then they are hidden`() {
        handler().handle(query)
        whenever(mdbListClient.isConfigured()).thenReturn(false)

        assertNull(handler().handle(query).getOrNull()!!.scores)
    }

    @Test
    fun `given MDBList is paused after a 429 when viewed then it is not asked and nothing is recorded`() {
        // Given
        whenever(mdbListClient.isPaused()).thenReturn(true)

        // When
        val info = handler().handle(query).getOrNull()!!

        // Then
        assertNull(info.scores)
        assertNotNull(info.trailerYoutubeKey)
        verify(mdbListClient, never()).fetch(any(), any())

        // And once the pause is over, the first view fetches the scores
        whenever(mdbListClient.isPaused()).thenReturn(false)
        assertEquals(matrixScores, handler().handle(query).getOrNull()!!.scores)
    }

    @Test
    fun `given MDBList answers 429 during a fetch then the title is retried after the pause, not marked`() {
        // Given
        whenever(mdbListClient.fetch(MediaType.movie, movie))
            .thenReturn(MdbListError.RateLimited(clock.now.plusSeconds(60)).left())

        // When
        val info = handler().handle(query).getOrNull()!!

        // Then
        assertNull(info.scores)
        val cached = repository.find(MediaType.movie, movie)!!
        assertNull(cached.scoresNextRefreshAt)
        assertFalse(cached.scoresNoData)
    }

    @Test
    fun `given MDBList has no data for a title then it is marked and not asked again for 7 days`() {
        // Given
        whenever(mdbListClient.fetch(MediaType.movie, movie)).thenReturn(MdbListError.NotFound.left())

        // When
        val info = handler().handle(query).getOrNull()!!
        clock.advance(Duration.ofDays(6))
        handler().handle(query)

        // Then
        assertNull(info.scores)
        val cached = repository.find(MediaType.movie, movie)!!
        assertTrue(cached.scoresNoData)
        verify(mdbListClient, times(1)).fetch(MediaType.movie, movie)

        // And after 7 days it is asked again
        clock.advance(Duration.ofDays(1))
        handler().handle(query)
        verify(mdbListClient, times(2)).fetch(MediaType.movie, movie)
    }

    @Test
    fun `given MDBList returns a response without any tracked score then it counts as no data`() {
        whenever(mdbListClient.fetch(MediaType.movie, movie)).thenReturn(ExternalScores(imdbId = "tt0133093").right())

        val info = handler().handle(query).getOrNull()!!

        assertNull(info.scores)
        val cached = repository.find(MediaType.movie, movie)!!
        assertTrue(cached.scoresNoData)
        assertEquals(clock.now + Duration.ofDays(7), cached.scoresNextRefreshAt)
    }

    @Test
    fun `given MDBList fails then cached scores stay and it is retried after an hour`() {
        // Given
        handler().handle(query)
        clock.advance(Duration.ofDays(31))
        whenever(mdbListClient.fetch(MediaType.movie, movie)).thenReturn(MdbListError.Failed("HTTP 502").left())

        // When
        val info = handler().handle(query).getOrNull()!!
        handler().handle(query)

        // Then
        assertEquals(matrixScores, info.scores)
        assertEquals(matrixScores, repository.find(MediaType.movie, movie)!!.scores)
        assertEquals(clock.now + Duration.ofHours(1), repository.find(MediaType.movie, movie)!!.scoresNextRefreshAt)
        verify(mdbListClient, times(2)).fetch(MediaType.movie, movie)
    }

    @Test
    fun `given a title released last week then its scores refresh daily`() {
        // Given
        whenever(tmdbExtrasClient.fetch(MediaType.movie, movie))
            .thenReturn(matrixExtras.copy(latestReleaseDate = LocalDate.parse("2026-11-15")).right())

        // When
        handler().handle(query)

        // Then
        assertEquals(clock.now + Duration.ofDays(1), repository.find(MediaType.movie, movie)!!.scoresNextRefreshAt)
    }

    @Test
    fun `given TMDB and MDBList both fail on first view then nothing is shown and both retry in an hour`() {
        // Given
        whenever(tmdbExtrasClient.fetch(MediaType.movie, movie)).thenReturn(TmdbExtrasError.Failed("timeout").left())
        whenever(mdbListClient.fetch(MediaType.movie, movie)).thenReturn(MdbListError.Failed("timeout").left())

        // When
        val result = handler().handle(query)
        handler().handle(query)

        // Then: no 3 s wait on every view while they are down
        assertEquals(GetTitleInfoError.NotAvailable, result.leftOrNull())
        val cached = repository.find(MediaType.movie, movie)!!
        assertEquals(clock.now + Duration.ofHours(1), cached.tmdbNextRefreshAt)
        assertEquals(clock.now + Duration.ofHours(1), cached.scoresNextRefreshAt)
        verify(tmdbExtrasClient, times(1)).fetch(MediaType.movie, movie)
    }

    @Test
    fun `given TMDB fails but MDBList answers then scores are still shown`() {
        whenever(tmdbExtrasClient.fetch(MediaType.movie, movie)).thenReturn(TmdbExtrasError.Failed("timeout").left())

        val info = handler().handle(query).getOrNull()!!

        assertEquals(matrixScores, info.scores)
        assertNull(info.trailerYoutubeKey)
        assertEquals("tt0133093", info.imdbId)
    }

    @Test
    fun `given a tv show then it is cached separately from a movie with the same id`() {
        // Given
        val show = GetTitleInfo(MediaType.tvshow, movie)
        whenever(tmdbExtrasClient.fetch(MediaType.tvshow, movie))
            .thenReturn(matrixExtras.copy(title = "A Show", runtimeMinutes = 45, numberOfSeasons = 3).right())
        whenever(mdbListClient.fetch(MediaType.tvshow, movie)).thenReturn(MdbListError.NotFound.left())

        // When
        val movieInfo = handler().handle(query).getOrNull()!!
        val showInfo = handler().handle(show).getOrNull()!!

        // Then
        assertEquals(2, repository.count())
        assertEquals(3, showInfo.numberOfSeasons)
        assertNull(showInfo.scores)
        assertEquals(matrixScores, movieInfo.scores)
    }
}
