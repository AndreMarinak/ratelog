package org.ratelog.import.letterboxd

import arrow.core.right
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.movie.Movie
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.UserFactory
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tmdb.TmdbMovieResponse
import org.ratelog.tmdb.TmdbTvShowResponse
import org.ratelog.user.User
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap
import java.io.ByteArrayInputStream
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Fork: the bounds on one Letterboxd import (LetterboxdImportLimits) and its background runner. */
class LetterboxdImportLimitsTest {

    private val tmdbClient: TmdbClient = mock()
    private val movies = InMemoryMovieRepository()
    private val ratings = InMemoryRatingRepository()
    private val handler = LetterboxdImportHandler(
        LetterboxdCsvParser(maxRows = 3), tmdbClient, movies, InMemoryMovieDescriptionRepository(), ratings,
        InMemoryTvShowRepository(), InMemoryTvDescriptionRepository(), InMemoryTvRatingRepository(),
    )
    private val user = User.Id(1)
    private val executors = mutableListOf<ExecutorService>()

    @AfterEach
    fun tearDown() = executors.forEach { it.shutdownNow() }

    private fun csv(rows: Int) = buildString {
        appendLine("Date,Name,Year,Letterboxd URI,Rating")
        (1..rows).forEach { appendLine("2024-01-15,Film $it,2000,https://boxd.it/$it,4.0") }
    }.toByteArray()

    private fun knownFilm(n: Int) {
        movies.save(MovieFactory.aMovie(id = n.toLong(), tmdbId = 1000 + n, originalTitle = "Film $n"))
        val match = TmdbMovieResponse(id = 1000 + n, title = "Film $n", originalTitle = "Film $n", releaseDate = "2000-01-01")
        whenever(tmdbClient.searchMovies("Film $n", Lang.en)).thenReturn((listOf(match) to 1).right())
    }

    private class CountingBudget(private val expireAfterCalls: Int = Int.MAX_VALUE) : ImportBudget {
        val calls = AtomicInteger()
        override fun beforeTmdbCall() { calls.incrementAndGet() }
        override fun isExpired(): Boolean = calls.get() >= expireAfterCalls
    }

    // --- Parser and handler ---

    @Test
    fun `a file with more rated rows than the limit is refused before any lookup`() {
        val result = LetterboxdCsvParser(maxRows = 3).parse(ByteArrayInputStream(csv(4)))

        assertEquals(LetterboxdParseError.TooManyRows, result.fold({ it }, { null }))
        assertEquals(LetterboxdImportError.TooManyRows, handler.parse(csv(4)).fold({ it }, { null }))
        assertEquals(3, handler.parse(csv(3)).getOrNull()!!.size)
    }

    @Test
    fun `the default row limit fits a heavy user's export`() {
        assertEquals(10_000, LetterboxdImportLimits.MAX_ROWS)
        assertEquals(2L * 1024 * 1024, LetterboxdImportLimits.MAX_FILE_BYTES)
        assertEquals(10_000, LetterboxdCsvParser().parse(ByteArrayInputStream(csv(10_000))).getOrNull()!!.size)
    }

    @Test
    fun `every TMDB lookup goes through the import budget`() {
        knownFilm(1)
        whenever(tmdbClient.searchMovies("Film 2", Lang.en)).thenReturn((emptyList<TmdbMovieResponse>() to 0).right())
        whenever(tmdbClient.searchTvShows("Film 2", Lang.en)).thenReturn((emptyList<TmdbTvShowResponse>() to 0).right())
        val budget = CountingBudget()

        val result = handler.importEntries(handler.parse(csv(2)).getOrNull()!!, user, budget)

        assertEquals(1, result.importedMovies)
        assertEquals(listOf("Film 2"), result.notFound)
        assertEquals(3, budget.calls.get(), "1 search for the known film, movie + TV search for the unknown one")
    }

    @Test
    fun `rows not started when the time budget runs out are counted as not processed`() {
        (1..3).forEach(::knownFilm)

        val result = handler.importEntries(handler.parse(csv(3)).getOrNull()!!, user, object : ImportBudget {
            override fun beforeTmdbCall() {}
            override fun isExpired(): Boolean = true
        })

        assertEquals(3, result.notProcessed)
        assertEquals(0, result.importedMovies)
        assertTrue((1L..3L).all { ratings.findByMovieIdAndUserId(Movie.Id(it), user) == null })
    }

    @Test
    fun `a failed lookup is recorded by exception class, never its message`() {
        whenever(tmdbClient.searchMovies(any(), any(), any())).thenThrow(ResourceAccessException("I/O error on GET request for \"https://api.themoviedb.org/3/search/movie?api_key=SECRET\""))

        val result = handler.importEntries(handler.parse(csv(1)).getOrNull()!!, user)

        assertEquals(listOf("Film 1: ResourceAccessException"), result.errors)
    }

    // --- Runner ---

    private fun runner(executor: ExecutorService = Executors.newSingleThreadExecutor().also(executors::add)) =
        LetterboxdImportRunner(handler, executor) { ImportBudget.UNLIMITED }

    @Test
    fun `an import runs in the background and returns the queued row count`() {
        knownFilm(1)
        val runner = runner()

        assertEquals(1, runner.start(user, csv(1)).getOrNull())

        val deadline = System.currentTimeMillis() + 5_000
        while (runner.isRunning(user) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(runner.isRunning(user))
        assertTrue(ratings.findByMovieIdAndUserId(Movie.Id(1), user) != null)
    }

    @Test
    fun `one import at a time per user`() {
        val release = CountDownLatch(1)
        whenever(tmdbClient.searchMovies(any(), any(), any())).thenAnswer { release.await(5, TimeUnit.SECONDS); (emptyList<TmdbMovieResponse>() to 0).right() }
        whenever(tmdbClient.searchTvShows(any(), any(), any())).thenReturn((emptyList<TmdbTvShowResponse>() to 0).right())
        val runner = runner()

        assertTrue(runner.start(user, csv(1)).isRight())
        assertEquals(LetterboxdImportError.AlreadyRunning, runner.start(user, csv(1)).fold({ it }, { null }))
        release.countDown()
    }

    @Test
    fun `when every import slot is busy the upload is refused and the user can try again`() {
        val full = object : AbstractExecutorService() {
            override fun execute(command: Runnable) = throw RejectedExecutionException()
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
        }
        val runner = runner(full)

        assertEquals(LetterboxdImportError.Busy, runner.start(user, csv(1)).fold({ it }, { null }))
        assertFalse(runner.isRunning(user), "not left marked as running")
    }

    @Test
    fun `format errors are reported at upload time`() {
        assertEquals(LetterboxdImportError.ParseError, runner().start(user, "not,a,letterboxd\nfile".toByteArray()).fold({ it }, { null }))
        assertEquals(LetterboxdImportError.TooManyRows, runner().start(user, csv(4)).fold({ it }, { null }))
    }

    // --- Controller ---

    private fun upload(bytes: ByteArray, runner: LetterboxdImportRunner = runner()): Pair<String, RedirectAttributesModelMap> {
        val flash = RedirectAttributesModelMap()
        val view = LetterboxdImportController(runner).importCsv(UserFactory.aUser(id = 1), MockMultipartFile("file", "ratings.csv", "text/csv", bytes), flash)
        return view to flash
    }

    @Test
    fun `the controller goes straight to the profile page with a message`() {
        knownFilm(1)
        val (view, flash) = upload(csv(1))

        assertEquals("redirect:/profile/1", view)
        assertEquals("import.letterboxd.started", flash.flashAttributes["success"])
    }

    @Test
    fun `the controller explains each refusal`() {
        assertEquals("error.import.empty", upload(ByteArray(0)).second.flashAttributes["error"])
        assertEquals("error.import.too.large", upload(ByteArray((LetterboxdImportLimits.MAX_FILE_BYTES + 1).toInt())).second.flashAttributes["error"])
        assertEquals("error.import.too.many.rows", upload(csv(4)).second.flashAttributes["error"])
        assertEquals("error.import.parse", upload("x".toByteArray()).second.flashAttributes["error"])
    }

    @Test
    fun `the upload field's size check matches the server limit`() {
        val modal = javaClass.classLoader.getResource("templates/fragments/letterboxd-import-modal.html")!!.readText()

        assertTrue(modal.contains("""data-max-bytes="${LetterboxdImportLimits.MAX_FILE_BYTES}""""))
    }
}
