package org.ratelog.browse.discover

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.WatchRegion
import org.ratelog.browse.BrowseLibraryRepository
import org.ratelog.browse.BrowseTitle
import org.ratelog.browse.BrowseTitlePage
import org.ratelog.browse.LibraryMark
import org.ratelog.browse.TmdbBrowseClient
import org.ratelog.browse.TmdbBrowseError
import org.ratelog.browse.TmdbListRequest
import org.ratelog.test.InMemoryBrowseLibraryRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.UserFactory
import org.ratelog.user.User
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GetDiscoverRowsHandlerTest {

    private val client: TmdbBrowseClient = mock()
    private val library = InMemoryBrowseLibraryRepository()
    private val user = UserFactory.aUser(id = 3)
    private val query = GetDiscoverRows(user.id!!, Lang.en, WatchRegion("US"), MediaType.movie, hideSeen = true)
    private val requests: MutableList<TmdbListRequest> = Collections.synchronizedList(mutableListOf())
    private val handlers = mutableListOf<GetDiscoverRowsHandler>()

    /** Thread-safe: rows on the pool look marks up concurrently. */
    private val noMarks = object : BrowseLibraryRepository {
        override fun findMarks(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>): Map<Int, LibraryMark> = emptyMap()
    }

    private fun handler(eagerRows: Int = 4, parallelRows: Int = 1, waitMs: Long = 1_000, marks: BrowseLibraryRepository = library) =
        GetDiscoverRowsHandler(BrowseHandler(client, marks, MutableClock()), eagerRows, parallelRows, waitMs).also { handlers += it }

    @AfterEach
    fun tearDown() = handlers.forEach { it.shutdown() }

    private fun title(id: Int, poster: Boolean = true) =
        BrowseTitle(id, MediaType.movie, "Title $id", LocalDate.parse("2021-01-01"), if (poster) "/$id.jpg" else null, 7.0, 400, emptyList(), "en")

    /** Page p has ids p*100+1..p*100+20, all with posters unless [items] says otherwise. */
    private fun serve(totalPages: Int = 5, items: (TmdbListRequest, Int) -> List<BrowseTitle> = { _, p -> (1..20).map { title(p * 100 + it) } }) {
        whenever(client.page(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<TmdbListRequest>(0)
            requests += request
            val page = request.params.getValue("page").toInt()
            BrowseTitlePage(items(request, page), page, totalPages, totalPages * 20).right()
        }
    }

    private fun rate(tmdbId: Int) {
        val movie = library.movies.save(MovieFactory.aMovie(tmdbId = tmdbId, originalTitle = "Movie $tmdbId"))
        library.ratings.save(RatingFactory.aQuickRating(movieId = movie.id!!, userId = user.id!!, score = 7.0))
    }

    @Test
    fun `given the landing then loads the first rows and leaves the rest pending, in display order`() {
        serve()

        val landing = handler(eagerRows = 3).landing(query)

        assertNull(landing.error)
        assertEquals(DiscoverRows.forType(MediaType.movie, hideSeen = true), landing.rows.map { it.spec })
        assertEquals(listOf(false, false, false) + List(11) { true }, landing.rows.map { it.pending })
        assertEquals(3, requests.size)
        assertTrue(landing.rows.take(3).all { it.tiles!!.size == 20 })
    }

    @Test
    fun `given a row then it keeps only titles with posters and the viewer's unseen ones, at most 20`() {
        // Page 1: 10 rated + 3 without posters; topping up from page 2 then gives more than a row holds.
        serve { _, p -> (1..20).map { title(p * 100 + it, poster = !(p == 1 && it in 18..20)) } }
        (101..110).forEach(::rate)

        val tiles = handler().row(query, DiscoverRows.forType(MediaType.movie, true)[1]).getOrNull()!!

        assertEquals(GetDiscoverRowsHandler.ROW_LIMIT, tiles.size)
        assertEquals((111..117).toList() + (201..213).toList(), tiles.map { it.title.tmdbId })
        assertEquals(listOf("1", "2"), requests.map { it.params["page"] })
        assertEquals("27", requests.first().params["with_genres"])
    }

    @Test
    fun `given the viewer has seen a whole row then it is dropped, and with seen shown it is back`() {
        serve(totalPages = 3) { _, p -> (1..4).map { title(p * 100 + it) } }
        listOf(101, 102, 103, 104, 201, 202, 203, 204, 301, 302, 303, 304).forEach(::rate)

        val hidden = handler(eagerRows = 1).landing(query)
        assertTrue(hidden.rows.none { it.spec.id == DiscoverRows.POPULAR })
        assertNull(hidden.error)

        val shown = handler(eagerRows = 1).landing(query.copy(hideSeen = false))
        assertEquals(4, shown.rows.first { it.spec.id == DiscoverRows.POPULAR }.tiles!!.size)
        assertTrue(shown.rows.all { !it.spec.filters.hideSeen })
    }

    @Test
    fun `given some rows fail or throw then they are dropped and the landing still loads`() {
        serve()
        whenever(client.page(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<TmdbListRequest>(0)
            when (request.params["with_genres"]) {
                "27" -> TmdbBrowseError.Failed("IOException").left()
                "35" -> throw IllegalStateException("bug")
                else -> BrowseTitlePage((1..20).map { title(it) }, 1, 1, 20).right()
            }
        }

        val landing = handler().landing(query)

        assertNull(landing.error)
        assertEquals(listOf("popular", "action", "animation"), landing.rows.take(3).map { it.spec.id })
        assertEquals(listOf(false, false, true), landing.rows.take(3).map { it.pending })
    }

    @Test
    fun `given every loaded row fails then reports a key problem before a network one, with no rows`() {
        whenever(client.page(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<TmdbListRequest>(0)
            if (request.params["with_genres"] == "35") TmdbBrowseError.ApiKeyRejected.left() else TmdbBrowseError.Failed("x").left()
        }

        val landing = handler().landing(query)

        assertEquals(BrowseError.TmdbKeyRejected, landing.error)
        assertTrue(landing.rows.isEmpty())

        whenever(client.page(any())).thenReturn(TmdbBrowseError.Failed("x").left())
        assertEquals(BrowseError.TmdbUnavailable, handler().landing(query).error)
    }

    @Test
    fun `given several rows then they load in parallel but never more than the pool's size at once`() {
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        val bothStarted = CountDownLatch(2)
        whenever(client.page(any())).thenAnswer {
            val now = inFlight.incrementAndGet()
            maxInFlight.accumulateAndGet(now) { a, b -> maxOf(a, b) }
            bothStarted.countDown()
            // Only returns once two calls overlap: proves they run at the same time (sequential loading would time out).
            val overlapped = bothStarted.await(2, TimeUnit.SECONDS)
            Thread.sleep(30)
            inFlight.decrementAndGet()
            if (!overlapped) return@thenAnswer TmdbBrowseError.Failed("not parallel").left()
            BrowseTitlePage((1..20).map { title(it) }, 1, 1, 20).right()
        }

        val landing = handler(eagerRows = 4, parallelRows = 2, waitMs = 5_000, marks = noMarks).landing(query)

        assertNull(landing.error)
        assertEquals(4, landing.rows.count { !it.pending })
        assertEquals(2, maxInFlight.get())
    }

    @Test
    fun `given a row slower than the wait then it is left pending and the rest are shown`() {
        val release = CountDownLatch(1)
        whenever(client.page(any())).thenAnswer { invocation ->
            val request = invocation.getArgument<TmdbListRequest>(0)
            if (request.params["with_genres"] == "27") release.await(5, TimeUnit.SECONDS)
            BrowseTitlePage((1..20).map { title(it) }, 1, 1, 20).right()
        }
        try {
            val started = System.nanoTime()

            val landing = handler(eagerRows = 4, parallelRows = 4, waitMs = 300, marks = noMarks).landing(query)

            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(tookMs < 2_000, "waited ${tookMs}ms")
            assertEquals(listOf("popular", "horror", "comedy", "action"), landing.rows.take(4).map { it.spec.id })
            assertEquals(listOf(false, true, false, false), landing.rows.take(4).map { it.pending })
        } finally {
            release.countDown()
        }
    }
}
