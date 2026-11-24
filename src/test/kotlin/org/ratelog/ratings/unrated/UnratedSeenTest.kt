package org.ratelog.ratings.unrated

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.test.MutableClock
import org.ratelog.test.UserFactory
import org.ratelog.user.User
import org.springframework.ui.ExtendedModelMap
import java.time.LocalDate
import java.util.Locale

/** Canned rows per type, newest first like the queries. */
class FakeUnratedSeenRepository(private val titles: List<UnratedSeenTitle>) : UnratedSeenRepository {
    val types = mutableListOf<MediaType>()

    override fun find(userId: User.Id, type: MediaType, lang: Lang, limit: Int): List<UnratedSeenTitle> {
        types += type
        return titles.filter { it.type == type }.sortedByDescending { it.seenAtEpochMs }.take(limit)
    }

    override fun count(userId: User.Id) = UnratedSeenCount(
        movies = titles.count { it.type == MediaType.movie },
        shows = titles.count { it.type == MediaType.tvshow },
    )
}

class UnratedSeenTest {

    private val user = UserFactory.aUser(id = 1)
    private val today = LocalDate.parse("2026-11-23")

    private fun title(tmdbId: Int, type: MediaType, seenAt: Long, release: LocalDate? = LocalDate.parse("2001-01-01")) =
        UnratedSeenTitle(tmdbId, type, "Title $tmdbId", 2001, "/p$tmdbId.jpg", release, seenAt)

    private val repo = FakeUnratedSeenRepository(
        listOf(
            title(603, MediaType.movie, 300),
            title(1396, MediaType.tvshow, 500),
            title(949, MediaType.movie, 100),
        ),
    )
    private val handler = GetUnratedSeenHandler(repo)

    @Test
    fun `movies and shows together, newest Seen first, with the counts`() {
        val result = handler.handle(GetUnratedSeen(user.id!!, null, Lang.en))

        assertEquals(listOf(1396, 603, 949), result.titles.map { it.tmdbId })
        assertEquals(UnratedSeenCount(movies = 2, shows = 1), result.count)
        assertEquals(3, result.count.total)
    }

    @Test
    fun `one type only reads that type`() {
        val result = handler.handle(GetUnratedSeen(user.id!!, MediaType.movie, Lang.en))

        assertEquals(listOf(603, 949), result.titles.map { it.tmdbId })
        assertEquals(listOf(MediaType.movie), repo.types)
    }

    @Test
    fun `the view - tiles carry the quick actions (seen, not rated), tabs count per type`() {
        val view = UnratedSeenController.view(handler.handle(GetUnratedSeen(user.id!!, null, Lang.en)), null, today, Locale.ENGLISH)

        assertEquals(listOf("all" to 3, "movie" to 2, "tv" to 1), view.tabs.map { it.type to it.count })
        assertTrue(view.tabs.first().active)
        assertNull(view.emptyKey)
        val movie = view.tiles.first { it.isMovie }
        assertEquals("/movie/603", movie.href)
        assertEquals("https://image.tmdb.org/t/p/w342/p603.jpg", movie.posterUrl)
        assertTrue(movie.actions.seen)
        assertFalse(movie.actions.seenLocked)
        assertNull(movie.actions.score)
        assertEquals("tile.rate.mark", movie.actions.rateLabelKey)
        assertEquals("/tv/1396?rate=1", view.tiles.first { !it.isMovie }.actions.rateHref)
    }

    @Test
    fun `an empty tab says what's missing`() {
        val empty = GetUnratedSeenHandler(FakeUnratedSeenRepository(emptyList()))
        fun key(type: MediaType?) = UnratedSeenController.view(empty.handle(GetUnratedSeen(user.id!!, type, Lang.en)), type, today, Locale.ENGLISH).emptyKey

        assertEquals("unrated.empty", key(null))
        assertEquals("unrated.empty.movies", key(MediaType.movie))
        assertEquals("unrated.empty.tv", key(MediaType.tvshow))
    }

    @Test
    fun `the page reads the type from the query and renders ratings-unrated`() {
        val controller = UnratedSeenController(handler, MutableClock())
        val model = ExtendedModelMap()

        assertEquals("ratings-unrated", controller.unrated(user, "tv", model))
        val view = model["unrated"] as UnratedSeenView
        assertEquals(listOf(1396), view.tiles.map { it.actions.tmdbId })
        assertTrue(view.tabs.single { it.type == "tv" }.active)
        assertEquals(MediaType.movie, UnratedSeenController.parseType("movies"))
        assertNull(UnratedSeenController.parseType("books"))
    }
}
