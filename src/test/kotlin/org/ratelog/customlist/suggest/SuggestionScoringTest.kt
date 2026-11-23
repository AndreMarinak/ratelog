package org.ratelog.customlist.suggest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.recommendations.CollectionRef
import org.ratelog.recommendations.RelatedTitle
import org.ratelog.recommendations.TitleCollection
import java.time.LocalDate

class SuggestionScoringTest {

    private val today = LocalDate.parse("2026-11-23")

    private fun movie(id: Int, date: String? = "2010-01-01", votes: Int = 5_000, average: Double? = 7.5, poster: String? = "/p$id.jpg", name: String = "Movie $id") =
        RelatedTitle(id, MediaType.movie, name, date?.let(LocalDate::parse), poster, average, votes)

    private fun show(id: Int, votes: Int = 5_000) =
        RelatedTitle(id, MediaType.tvshow, "Show $id", LocalDate.parse("2012-01-01"), "/s$id.jpg", 7.5, votes)

    private fun inList(vararg ids: Int, type: MediaType = MediaType.movie) =
        ids.mapIndexed { i, id -> ListTitle(itemId = 100L + i, type = type, tmdbId = id, position = i + 1) }

    private fun seed(title: ListTitle, vararg recs: RelatedTitle, collection: CollectionRef? = null) =
        SeedRecommendations(title, recs.toList(), collection)

    // --- Seeds ---

    @Test
    fun `a list of ten or fewer uses every title`() {
        val list = inList(*IntArray(10) { it + 1 })
        assertEquals(list, SuggestionScoring.pickSeeds(list))
    }

    @Test
    fun `a bigger list takes a few of the newest additions plus titles spread over the list, starting at the top`() {
        // 40 titles; the newest additions are the highest item ids (here the first five ranks were added last).
        val list = (1..40).map { rank -> ListTitle(itemId = if (rank <= 5) 1000L + rank else rank.toLong(), type = MediaType.movie, tmdbId = rank, position = rank) }

        val seeds = SuggestionScoring.pickSeeds(list)

        assertEquals(10, seeds.size)
        assertEquals(seeds, seeds.sortedBy { it.position }, "seeds stay in list order")
        assertTrue(seeds.map { it.tmdbId }.containsAll(listOf(3, 4, 5)), "the three newest additions: $seeds")
        assertFalse(seeds.any { it.tmdbId == 2 }, "only three of them")
        val spread = seeds.map { it.tmdbId }.filter { it !in 3..5 }
        assertEquals(7, spread.size)
        assertEquals(1, spread.first(), "spread starts at #1")
        assertTrue(spread.last() >= 30, "and reaches the end of the list: $spread")
    }

    @Test
    fun `seeds follow the list's mix of movies and shows, at least one of each`() {
        val movies = (1..27).map { ListTitle(it.toLong(), MediaType.movie, it, it) }
        val shows = (28..30).map { ListTitle(it.toLong(), MediaType.tvshow, it, it) }

        val seeds = SuggestionScoring.pickSeeds(movies + shows)
        assertEquals(9, seeds.count { it.type == MediaType.movie })
        assertEquals(1, seeds.count { it.type == MediaType.tvshow })

        val oneShow = SuggestionScoring.pickSeeds((1..40).map { ListTitle(it.toLong(), MediaType.movie, it, it) } + ListTitle(41, MediaType.tvshow, 41, 41))
        assertEquals(1, oneShow.count { it.type == MediaType.tvshow }, "a lone show still gets a seed")
        assertEquals(10, oneShow.size)
    }

    @Test
    fun `quotas never exceed the cap or a type's size`() {
        assertEquals(mapOf(MediaType.movie to 5, MediaType.tvshow to 5), SuggestionScoring.quotas(mapOf(MediaType.movie to 20, MediaType.tvshow to 20), 10))
        assertEquals(mapOf(MediaType.movie to 9, MediaType.tvshow to 1), SuggestionScoring.quotas(mapOf(MediaType.movie to 100, MediaType.tvshow to 1), 10))
        assertEquals(mapOf(MediaType.movie to 10), SuggestionScoring.quotas(mapOf(MediaType.movie to 12, MediaType.tvshow to 0), 10))
    }

    // --- Scoring ---

    @Test
    fun `titles several list titles recommend come first, and titles in the list are left out`() {
        val list = inList(1, 2, 3)
        val pool = SuggestionScoring.rank(
            listOf(
                seed(list[0], movie(10), movie(20), movie(2)),
                seed(list[1], movie(30), movie(20)),
                seed(list[2], movie(40), movie(20), movie(30)),
            ),
            emptyList(), list, today,
        )

        assertEquals(listOf(20, 30, 10, 40), pool.map { it.title.tmdbId })
        assertEquals(3, pool.first().recommendedBy)
        assertFalse(pool.any { it.title.tmdbId == 2 }, "already in the list")
        assertTrue(pool.all { it.reason == SuggestionReason.RECOMMENDED })
    }

    @Test
    fun `TMDB's first picks count more than its last ones`() {
        assertEquals(1.0, SuggestionScoring.positionWeight(0))
        assertEquals(0.5, SuggestionScoring.positionWeight(8))
        assertTrue(SuggestionScoring.positionWeight(19) < 0.31)
    }

    @Test
    fun `quality favours well known, well rated titles`() {
        val hit = SuggestionScoring.quality(movie(1, votes = 20_000, average = 8.0))
        val niche = SuggestionScoring.quality(movie(2, votes = 80, average = 8.0))
        val poor = SuggestionScoring.quality(movie(3, votes = 20_000, average = 4.5))
        assertEquals(1.0, hit, 1e-9)
        assertTrue(niche < hit)
        assertTrue(poor < 0.61)
        assertTrue(SuggestionScoring.quality(movie(4, votes = 0, average = null)) > 0.3, "upcoming titles aren't zeroed")
    }

    @Test
    fun `a popular title beats an obscure one that only one more seed recommends at the same places`() {
        val list = inList(1, 2)
        val pool = SuggestionScoring.rank(
            listOf(
                seed(list[0], movie(10, votes = 60, average = 5.0), movie(20, votes = 15_000)),
                seed(list[1], movie(20, votes = 15_000), movie(10, votes = 60, average = 5.0)),
            ),
            emptyList(), list, today,
        )
        assertEquals(listOf(20, 10), pool.map { it.title.tmdbId })
    }

    @Test
    fun `posterless and rarely voted released titles are dropped, upcoming ones kept`() {
        val list = inList(1)
        val pool = SuggestionScoring.rank(
            listOf(
                seed(
                    list[0],
                    movie(10, poster = null),
                    movie(11, votes = 49),
                    movie(12, votes = 50),
                    movie(13, date = "2027-05-01", votes = 0, average = null),
                ),
            ),
            emptyList(), list, today,
        )
        assertEquals(listOf(12, 13), pool.map { it.title.tmdbId })
    }

    // --- Series ---

    private val potter = TitleCollection(
        1241, "Harry Potter Collection",
        listOf(
            movie(12445, "2011-07-12", name = "Deathly Hallows: Part 2"),
            movie(671, "2001-11-16"),
            movie(674, "2005-11-16", name = "Goblet of Fire"),
            movie(672, "2002-11-13"),
            movie(675, "2007-06-28", name = "Order of the Phoenix", votes = 10),
            movie(673, "2004-05-31"),
            movie(99999, null, name = "Undated", poster = "/u.jpg"),
            movie(88888, "2030-01-01", name = "No poster", poster = null),
        ),
    )

    @Test
    fun `a franchise list gets its missing entries first, in release order, whatever their votes`() {
        val list = inList(671, 672, 673)
        val ref = CollectionRef(1241, "Harry Potter Collection")
        val pool = SuggestionScoring.rank(
            listOf(
                seed(list[0], movie(500, votes = 30_000), movie(12445), collection = ref),
                seed(list[1], movie(500, votes = 30_000), collection = ref),
                seed(list[2], movie(501), collection = ref),
            ),
            listOf(potter), list, today,
        )

        assertEquals(listOf(674, 675, 12445, 99999, 500, 501), pool.map { it.title.tmdbId })
        assertEquals(List(4) { SuggestionReason.SERIES }, pool.take(4).map { it.reason })
        assertEquals(1, pool[2].recommendedBy, "Deathly Hallows was also recommended once")
        assertFalse(pool.any { it.title.tmdbId == 88888 }, "posterless entries stay out")
    }

    @Test
    fun `with one title of the series in the list its other entries get a boost, not a pinned place`() {
        val list = inList(12, 2)
        val nemo = TitleCollection(137697, "Finding Nemo Collection", listOf(movie(12, "2003-05-30"), movie(127380, "2016-06-16", votes = 13_000)))
        val pool = SuggestionScoring.rank(
            listOf(
                seed(list[0], movie(20, votes = 15_000), movie(30, votes = 15_000), collection = CollectionRef(137697, "Finding Nemo Collection")),
                seed(list[1], movie(20, votes = 15_000), movie(30, votes = 15_000)),
            ),
            listOf(nemo), list, today,
        )

        assertEquals(listOf(20, 30, 127380), pool.map { it.title.tmdbId }, "two seeds' top picks still beat the bonus")
        val dory = pool.last()
        assertEquals(SuggestionReason.SERIES, dory.reason)
        assertEquals(0, dory.recommendedBy)
        assertTrue(dory.score > 0.9, "worth about one seed's first pick: ${dory.score}")
    }

    @Test
    fun `collections are read for the seeds' series, the most shared first, at most four`() {
        val list = inList(1, 2, 3, 4, 5, 6, 7)
        fun ref(id: Int) = CollectionRef(id, "C$id")
        val refs = SuggestionScoring.collectionsToFetch(
            listOf(
                seed(list[0], collection = ref(1)),
                seed(list[1], collection = ref(2)),
                seed(list[2], collection = ref(2)),
                seed(list[3], collection = ref(3)),
                seed(list[4], collection = ref(4)),
                seed(list[5], collection = ref(5)),
                seed(list[6]),
            ),
        )
        assertEquals(listOf(2, 1, 3, 4), refs.map { it.id })
    }

    // --- Media mix ---

    @Test
    fun `movies and shows are interleaved by the list's share`() {
        val movies = (1..7).map { ListTitle(it.toLong(), MediaType.movie, it, it) }
        val shows = (8..9).map { ListTitle(it.toLong(), MediaType.tvshow, it, it) }
        val list = movies + shows
        val pool = SuggestionScoring.rank(
            listOf(
                SeedRecommendations(movies[0], (100..120).map { movie(it) }, null),
                SeedRecommendations(shows[0], (200..220).map { show(it) }, null),
                SeedRecommendations(shows[1], (200..220).map { show(it) }, null),
            ),
            emptyList(), list, today,
        )

        // The shows are recommended by both show seeds, so on score alone they would all come first.
        val firstNine = pool.take(9).map { it.title.type }
        assertEquals(7, firstNine.count { it == MediaType.movie }, firstNine.toString())
        assertEquals(2, firstNine.count { it == MediaType.tvshow })
        assertEquals(42, pool.size)
    }

    @Test
    fun `a movie-only list only gets movies even if a seed answered with shows`() {
        val list = inList(1, 2)
        val pool = SuggestionScoring.rank(listOf(seed(list[0], movie(10)), seed(list[1], movie(11))), emptyList(), list, today)
        assertTrue(pool.all { it.title.type == MediaType.movie })
    }

    // --- Keys ---

    @Test
    fun `skip keys read the short and the long form and ignore junk`() {
        val keys = TitleKey.parseList("m862.t1396,movie:585 tv:60059;tvshow:1;x12.m0.m-4.m12345678901", 300)
        assertEquals(
            setOf(
                TitleKey(MediaType.movie, 862), TitleKey(MediaType.tvshow, 1396), TitleKey(MediaType.movie, 585),
                TitleKey(MediaType.tvshow, 60059), TitleKey(MediaType.tvshow, 1),
            ),
            keys,
        )
        assertEquals("m862", TitleKey(MediaType.movie, 862).compact)
        assertEquals("tv:1396", TitleKey(MediaType.tvshow, 1396).tileKey)
        assertEquals(emptySet<TitleKey>(), TitleKey.parseList(null, 10))
    }

    @Test
    fun `only the most recent skip keys are kept`() {
        val raw = (1..50).joinToString(".") { "m$it" }
        val keys = TitleKey.parseList(raw, 10)
        assertEquals((41..50).map { TitleKey(MediaType.movie, it) }.toSet(), keys)
    }
}
