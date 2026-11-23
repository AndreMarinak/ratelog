package org.ratelog.customlist.suggest

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atMost
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.browse.BrowseLibraryRepository
import org.ratelog.browse.LibraryMark
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.recommendations.CollectionRef
import org.ratelog.recommendations.RecommendationsPage
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TitleCollection
import org.ratelog.recommendations.TmdbRecommendationsClient
import org.ratelog.recommendations.TmdbRecommendationsError
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryListTitlesRepository
import org.ratelog.user.User
import org.ratelog.watchlist.WatchStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

class GetListSuggestionsHandlerTest {

    /** Marks by (type, TMDB id); records every lookup. */
    private class FakeLibrary : BrowseLibraryRepository {
        val marks = mutableMapOf<Pair<MediaType, Int>, LibraryMark>()
        val calls = mutableListOf<Pair<MediaType, List<Int>>>()

        override fun findMarks(userId: User.Id, type: MediaType, tmdbIds: Collection<Int>): Map<Int, LibraryMark> {
            calls += type to tmdbIds.toList()
            return tmdbIds.mapNotNull { id -> marks[type to id]?.let { id to it } }.toMap()
        }
    }

    private val lists = InMemoryCustomListRepository()
    private val titles = InMemoryListTitlesRepository(lists)
    private val client: TmdbRecommendationsClient = mock()
    private val library = FakeLibrary()
    private val clock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC)
    private val me = User.Id(1)
    private val other = User.Id(2)

    /** Recommendations per (type, TMDB id); unknown titles answer an empty page. */
    private val recs = ConcurrentHashMap<Pair<MediaType, Int>, RecommendationsPage>()

    private fun handler(parallel: Int = 1) = GetListSuggestionsHandler(lists, titles, client, library, cacheHours = 6, parallel = parallel, clock = clock)

    private fun title(id: Int, type: MediaType = MediaType.movie, votes: Int = 5_000, date: String = "2012-01-01") =
        RelatedTitle(id, type, "Title $id", LocalDate.parse(date), "/p$id.jpg", 7.5, votes)

    private fun recommend(seed: Int, vararg ids: Int, type: MediaType = MediaType.movie, collection: CollectionRef? = null) {
        recs[type to seed] = RecommendationsPage(collection, ids.map { title(it, type) }, 1, 1)
    }

    private fun serve() {
        whenever(client.recommendations(any(), any(), any(), any())).thenAnswer { inv ->
            val type = inv.getArgument<MediaType>(0)
            val id = inv.getArgument<Int>(1)
            (recs[type to id] ?: RecommendationsPage(null, emptyList(), 1, 1)).right()
        }
    }

    private fun list(owner: User.Id = me, vararg tmdbIds: Int, type: MediaType = MediaType.movie): CustomList {
        val list = lists.save(CustomList(null, owner, ListName("Pixar"), isPublic = false, createdAtEpochMs = 1_000))
        tmdbIds.forEachIndexed { i, id -> lists.addItem(CustomListItem(null, list.id!!, id.toLong(), type, i + 1, 1_000L + i)) }
        return list
    }

    private fun add(list: CustomList, tmdbId: Int, type: MediaType = MediaType.movie) {
        val position = (lists.findById(list.id!!)!!.items.maxOfOrNull { it.position } ?: 0) + 1
        lists.addItem(CustomListItem(null, list.id!!, tmdbId.toLong(), type, position, 2_000))
    }

    private fun suggest(list: CustomList, skip: Set<TitleKey> = emptySet(), limit: Int = 20, handler: GetListSuggestionsHandler = handler()) =
        handler.handle(GetListSuggestions(list.id!!, me, Lang.en, skip, limit))

    private fun ids(result: ListSuggestions) = result.suggestions.map { it.title.tmdbId }

    @Test
    fun `someone else's list gets nothing and costs no TMDB call`() {
        serve()
        val theirs = list(owner = other, 862, 585)

        assertEquals(GetListSuggestionsError.NotOwner.left(), suggest(theirs))
        assertEquals(GetListSuggestionsError.ListNotFound.left(), handler().handle(GetListSuggestions(CustomList.Id(999), me, Lang.en)))
        verify(client, never()).recommendations(any(), any(), any(), any())
    }

    @Test
    fun `an empty list has no suggestions and costs no TMDB call`() {
        serve()
        val result = suggest(list()).getOrNull()!!

        assertTrue(result.exhausted)
        assertFalse(result.skipped)
        assertEquals(0, result.listSize)
        verify(client, never()).recommendations(any(), any(), any(), any())
    }

    @Test
    fun `suggestions come from the list's recommendations, without its own titles, with the viewer's marks`() {
        recommend(862, 863, 585, 10193)
        recommend(585, 863, 2062, 862)
        serve()
        library.marks[MediaType.movie to 2062] = LibraryMark(score = 8.0, rated = true, status = WatchStatus.WATCHED)
        val pixar = list(me, 862, 585)

        val result = suggest(pixar).getOrNull()!!

        assertEquals(863, ids(result).first(), "recommended by both")
        assertEquals(setOf(863, 10193, 2062), ids(result).toSet())
        assertEquals(2, result.basedOn)
        assertEquals(2, result.listSize)
        assertEquals("Pixar", result.listName)
        val ratatouille = result.suggestions.single { it.title.tmdbId == 2062 }
        assertTrue(ratatouille.seen, "seen titles stay in, marked")
        assertEquals(listOf(MediaType.movie to ids(result)), library.calls, "one marks query for the batch")
        verify(client).recommendations(eq(MediaType.movie), eq(862), eq(Lang.en), eq(1))
    }

    @Test
    fun `batches - the limit, then skip for the next titles, and what is left`() {
        recommend(1, *(100..129).toList().toIntArray())
        serve()
        val one = list(me, 1)

        val first = suggest(one, limit = 20).getOrNull()!!
        assertEquals((100..119).toList(), ids(first))
        assertEquals(10, first.remaining)

        val second = suggest(one, skip = first.suggestions.map { TitleKey(MediaType.movie, it.title.tmdbId) }.toSet()).getOrNull()!!
        assertEquals((120..129).toList(), ids(second))
        assertEquals(0, second.remaining)
        assertTrue(second.skipped)

        val done = suggest(one, skip = (100..129).map { TitleKey(MediaType.movie, it) }.toSet()).getOrNull()!!
        assertTrue(done.exhausted)
        assertTrue(done.skipped)
    }

    @Test
    fun `the pool is cached until the list changes, and a title added leaves the suggestions`() {
        recommend(862, 863, 585)
        recommend(585, 2062)
        serve()
        val pixar = list(me, 862)
        val handler = handler()

        suggest(pixar, handler = handler)
        suggest(pixar, handler = handler)
        verify(client, times(1)).recommendations(eq(MediaType.movie), eq(862), any(), any())

        add(pixar, 585)
        val after = suggest(pixar, handler = handler).getOrNull()!!

        assertFalse(585 in ids(after), "now in the list")
        assertTrue(2062 in ids(after), "the new title's recommendations count")
        verify(client, times(2)).recommendations(eq(MediaType.movie), eq(862), any(), any())
        assertEquals(3, titles.queries.get(), "one titles query per request")
    }

    @Test
    fun `a big list asks TMDB for at most ten titles' recommendations and four collections`() {
        (1..30).forEach { seed -> recommend(seed, 1000 + seed, 2000, collection = CollectionRef(500 + seed, "C$seed")) }
        serve()
        whenever(client.collection(any(), any())).thenAnswer { inv ->
            TitleCollection(inv.getArgument(0), "C", emptyList()).right()
        }
        val big = list(me, *(1..30).toList().toIntArray())

        val result = suggest(big).getOrNull()!!

        assertEquals(10, result.basedOn)
        assertEquals(30, result.listSize)
        assertEquals(2000, ids(result).first(), "every seed recommends it")
        verify(client, times(10)).recommendations(any(), any(), any(), any())
        verify(client, times(4)).collection(any(), any())
    }

    @Test
    fun `a franchise list gets the series' missing entries first`() {
        val potter = CollectionRef(1241, "Harry Potter Collection")
        recommend(671, 500, collection = potter)
        recommend(672, 500, collection = potter)
        serve()
        whenever(client.collection(eq(1241), any())).thenReturn(
            TitleCollection(1241, "Harry Potter Collection", listOf(title(671, date = "2001-11-16"), title(673, date = "2004-05-31"), title(672, date = "2002-11-13"))).right(),
        )
        val hp = list(me, 671, 672)

        val result = suggest(hp).getOrNull()!!

        assertEquals(listOf(673, 500), ids(result))
        assertEquals(SuggestionReason.SERIES, result.suggestions.first().reason)
    }

    @Test
    fun `TMDB failing for every title is an error, and the failure isn't cached`() {
        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(TmdbRecommendationsError.Unavailable.left())
        val pixar = list(me, 862, 585)
        val handler = handler()

        assertEquals(GetListSuggestionsError.TmdbUnavailable.left(), suggest(pixar, handler = handler))

        recommend(862, 863)
        serve()
        assertEquals(listOf(863), ids(suggest(pixar, handler = handler).getOrNull()!!), "tried again")
    }

    @Test
    fun `no TMDB key is reported as such`() {
        whenever(client.recommendations(any(), any(), any(), any())).thenReturn(TmdbRecommendationsError.ApiKeyMissing.left())

        assertEquals(GetListSuggestionsError.TmdbNotConfigured.left(), suggest(list(me, 862)))
    }

    @Test
    fun `one failing title drops only its recommendations, and that pool is built again next time`() {
        recommend(585, 2062)
        serve()
        whenever(client.recommendations(eq(MediaType.movie), eq(862), any(), any())).thenReturn(TmdbRecommendationsError.Unavailable.left())
        val pixar = list(me, 862, 585)
        val handler = handler()

        assertEquals(listOf(2062), ids(suggest(pixar, handler = handler).getOrNull()!!))
        suggest(pixar, handler = handler)
        verify(client, times(2)).recommendations(eq(MediaType.movie), eq(585), any(), any())
    }

    @Test
    fun `a list of shows asks for TV recommendations and gets shows`() {
        recommend(1396, 60059, 1398, type = MediaType.tvshow)
        serve()
        val tv = list(me, 1396, 1398, type = MediaType.tvshow)

        val result = suggest(tv).getOrNull()!!

        assertEquals(listOf(60059), ids(result))
        assertTrue(result.suggestions.all { it.title.type == MediaType.tvshow })
        verify(client).recommendations(eq(MediaType.tvshow), eq(1396), any(), any())
        verify(client, never()).collection(any(), any())
    }

    @Test
    fun `the calls can run in parallel with the same result`() {
        (1..12).forEach { seed -> recommend(seed, 100 + seed % 3, 200 + seed) }
        serve()
        val big = list(me, *(1..12).toList().toIntArray())

        val sequential = ids(suggest(big, handler = handler(parallel = 1)).getOrNull()!!)
        val parallel = ids(suggest(big, handler = handler(parallel = 4)).getOrNull()!!)

        assertEquals(sequential, parallel)
        verify(client, atMost(20)).recommendations(any(), any(), any(), any())
    }
}
