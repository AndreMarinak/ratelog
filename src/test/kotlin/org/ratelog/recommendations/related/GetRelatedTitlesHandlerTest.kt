package org.ratelog.recommendations.related

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.browse.BrowseLibraryRepository
import org.ratelog.browse.LibraryMark
import org.ratelog.recommendations.CollectionRef
import org.ratelog.recommendations.RecommendationsPage
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TitleCollection
import org.ratelog.recommendations.TmdbRecommendationsClient
import org.ratelog.recommendations.TmdbRecommendationsError
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class GetRelatedTitlesHandlerTest {

    /** Marks by (type, TMDB id); records every lookup. */
    private class FakeLibrary : BrowseLibraryRepository {
        val marks = mutableMapOf<Pair<MediaType, Int>, LibraryMark>()
        val calls = mutableListOf<List<Int>>()

        fun mark(type: MediaType, tmdbId: Int, status: WatchStatus? = null, score: Double? = null, rated: Boolean = score != null) {
            marks[type to tmdbId] = LibraryMark(score, rated, status)
        }

        override fun findMarks(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>): Map<Int, LibraryMark> {
            calls += tmdbIds.toList()
            return tmdbIds.mapNotNull { id -> marks[type to id]?.let { id to it } }.toMap()
        }
    }

    private val client: TmdbRecommendationsClient = mock()
    private val library = FakeLibrary()
    private val clock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC)
    private val handler = GetRelatedTitlesHandler(client, library, clock)
    private val me = User.Id(1)

    private fun title(id: Int, name: String = "Title $id", date: String? = "2015-01-01", poster: String? = "/p$id.jpg", votes: Int = 500, type: MediaType = MediaType.movie) =
        RelatedTitle(id, type, name, date?.let(LocalDate::parse), poster, 7.0, votes)

    private fun page(vararg titles: RelatedTitle, collection: CollectionRef? = null, page: Int = 1, totalPages: Int = 1) =
        RecommendationsPage(collection, titles.toList(), page, totalPages)

    private val toyStory = listOf(
        title(1084244, "Toy Story 5", "2026-06-17"),
        title(862, "Toy Story", "1995-11-22"),
        title(10193, "Toy Story 3", "2010-06-16"),
        title(863, "Toy Story 2", "1999-10-30"),
        title(301528, "Toy Story 4", "2019-06-19"),
    )

    private fun serveMovie(first: RecommendationsPage, collection: TitleCollection? = null, second: RecommendationsPage? = null) {
        whenever(client.recommendations(eq(MediaType.movie), eq(863), any(), eq(1))).thenReturn(first.right())
        if (collection != null) whenever(client.collection(eq(collection.id), any())).thenReturn(collection.right())
        whenever(client.recommendations(eq(MediaType.movie), eq(863), any(), eq(2)))
            .thenReturn(second?.right() ?: TmdbRecommendationsError.Unavailable.left())
    }

    private fun handle(type: MediaType = MediaType.movie, tmdbId: Int = 863, hideSeen: Boolean = true) =
        handler.handle(GetRelatedTitles(me, type, tmdbId, Lang.en, hideSeen)).getOrNull()!!

    @Test
    fun `the series lists every entry in release order with this title marked and the viewer's marks`() {
        serveMovie(
            page(*(1..14).map { title(it) }.toTypedArray(), collection = CollectionRef(10194, "Toy Story Collection")),
            TitleCollection(10194, "Toy Story Collection", toyStory),
        )
        library.mark(MediaType.movie, 862, score = 8.5)
        library.mark(MediaType.movie, 863, status = WatchStatus.WATCHED)

        val series = handle().series!!

        assertEquals(10194, series.collectionId)
        assertEquals("Toy Story Collection", series.name)
        assertEquals(listOf("Toy Story", "Toy Story 2", "Toy Story 3", "Toy Story 4", "Toy Story 5"), series.entries.map { it.title.title })
        assertEquals(listOf(false, true, false, false, false), series.entries.map { it.current })
        assertEquals(listOf(true, true, false, false, false), series.entries.map { it.seen })
        assertEquals(8.5, series.entries[0].mark?.score)
    }

    @Test
    fun `undated entries go last, a one-entry collection is no series, and a blank name falls back to the movie's`() {
        serveMovie(
            page(title(5), collection = CollectionRef(1, "Dune Collection")),
            TitleCollection(1, "", listOf(title(9, "Dune: Part Four", date = null), title(863, "Dune: Part Three", "2026-12-15"), title(8, "Dune", "2021-09-15"))),
        )
        val series = handle().series!!
        assertEquals(listOf("Dune", "Dune: Part Three", "Dune: Part Four"), series.entries.map { it.title.title })
        assertEquals("Dune Collection", series.name)

        serveMovie(page(title(5), collection = CollectionRef(2, "Solo")), TitleCollection(2, "Solo", listOf(title(863))))
        assertNull(handle().series)
    }

    @Test
    fun `more like this leaves out this title, its series, posterless titles and released titles almost nobody voted on`() {
        serveMovie(
            page(
                title(10193, "Toy Story 3"),
                title(863, "Toy Story 2"),
                title(585, "Monsters, Inc."),
                title(12, "No poster", poster = null),
                title(13, "Obscure", votes = 3),
                title(14, "Upcoming, no votes yet", date = "2027-03-01", votes = 0),
                title(585, "Monsters, Inc. again"),
                collection = CollectionRef(10194, "Toy Story Collection"),
            ),
            TitleCollection(10194, "Toy Story Collection", toyStory),
        )

        val more = handle().moreLikeThis

        assertEquals(listOf(585, 14), more.map { it.title.tmdbId })
        assertTrue(more.none { it.current })
    }

    @Test
    fun `seen titles are hidden by default, watchlist and dropped ones stay, hideSeen false keeps them`() {
        serveMovie(page(title(1), title(2), title(3), title(4), title(5)))
        library.mark(MediaType.movie, 1, score = 7.0)
        library.mark(MediaType.movie, 2, status = WatchStatus.WATCHED)
        library.mark(MediaType.movie, 3, status = WatchStatus.WANT_TO_WATCH)
        library.mark(MediaType.movie, 4, status = WatchStatus.DROPPED)

        assertEquals(listOf(3, 4, 5), handle().moreLikeThis.map { it.title.tmdbId })
        val all = handle(hideSeen = false).moreLikeThis
        assertEquals(listOf(1, 2, 3, 4, 5), all.map { it.title.tmdbId })
        assertEquals(listOf(true, true, false, false, false), all.map { it.seen })
    }

    @Test
    fun `shows use the tile Seen rule and never ask for a collection`() {
        whenever(client.recommendations(eq(MediaType.tvshow), eq(1396), any(), eq(1))).thenReturn(
            page(
                title(60059, "Better Call Saul", type = MediaType.tvshow),
                title(1, "Watching, rated seasons", type = MediaType.tvshow),
                title(2, "Watched", type = MediaType.tvshow),
                title(3, "Rated, no status", type = MediaType.tvshow),
                collection = CollectionRef(7, "Shows have none"),
            ).right()
        )
        library.mark(MediaType.tvshow, 1, status = WatchStatus.WATCHING, score = 8.0)
        library.mark(MediaType.tvshow, 2, status = WatchStatus.WATCHED)
        library.mark(MediaType.tvshow, 3, score = 6.0)

        val related = handle(MediaType.tvshow, 1396)

        assertNull(related.series)
        assertEquals(listOf(60059, 1), related.moreLikeThis.map { it.title.tmdbId })
        verify(client, never()).collection(any(), any())
    }

    @Test
    fun `when hiding leaves fewer than twelve, page two tops the row up to twenty without repeats`() {
        val first = (1..20).map { title(it) }
        serveMovie(
            page(*first.toTypedArray(), totalPages = 36),
            second = page(*((15..40).map { title(it) }.toTypedArray()), page = 2, totalPages = 36),
        )
        (1..10).forEach { library.mark(MediaType.movie, it, status = WatchStatus.WATCHED) }

        val more = handle().moreLikeThis

        assertEquals((11..30).toList(), more.map { it.title.tmdbId })
        assertEquals(2, library.calls.size, "one marks lookup per page")
        assertFalse(library.calls[1].any { it in 15..20 }, "page two's repeats are dropped before the lookup")
    }

    @Test
    fun `no top-up when the row is full enough, or when there is no second page`() {
        serveMovie(page(*(1..20).map { title(it) }.toTypedArray(), totalPages = 36))
        assertEquals(20, handle().moreLikeThis.size)
        verify(client, never()).recommendations(any(), any(), any(), eq(2))

        serveMovie(page(title(1), title(2), totalPages = 1))
        assertEquals(2, handle().moreLikeThis.size)
        verify(client, never()).recommendations(any(), any(), any(), eq(2))
    }

    @Test
    fun `a failing page two or collection only costs its part`() {
        whenever(client.recommendations(eq(MediaType.movie), eq(863), any(), eq(1)))
            .thenReturn(page(title(585), collection = CollectionRef(10194, "Toy Story Collection"), totalPages = 36).right())
        whenever(client.collection(any(), any())).thenReturn(TmdbRecommendationsError.Unavailable.left())
        whenever(client.recommendations(eq(MediaType.movie), eq(863), any(), eq(2))).thenReturn(TmdbRecommendationsError.Unavailable.left())

        val related = handle()

        assertNull(related.series)
        assertEquals(listOf(585), related.moreLikeThis.map { it.title.tmdbId })
    }

    @Test
    fun `a failing first page is an error the caller can map`() {
        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(TmdbRecommendationsError.Unavailable.left())
        assertEquals(GetRelatedTitlesError.TmdbUnavailable, handler.handle(GetRelatedTitles(me, MediaType.movie, 863, Lang.en)).leftOrNull())

        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(TmdbRecommendationsError.ApiKeyMissing.left())
        assertEquals(GetRelatedTitlesError.TmdbNotConfigured, handler.handle(GetRelatedTitles(me, MediaType.movie, 863, Lang.en)).leftOrNull())

        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(TmdbRecommendationsError.NotFound.left())
        assertEquals(GetRelatedTitlesError.NotFound, handler.handle(GetRelatedTitles(me, MediaType.movie, 863, Lang.en)).leftOrNull())
        assertTrue(library.calls.isEmpty())
    }

    @Test
    fun `nothing at all is an empty result, not an error`() {
        serveMovie(page())
        val related = handle()
        assertTrue(related.isEmpty)
    }
}
