package org.ratelog.ratings.scroll

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.movie.premieres.MoviePremieresHandler
import org.ratelog.movie.rating.RatingDAO
import org.ratelog.movie.top.GetTopMoviesResponse
import org.ratelog.movie.top.TopMovieController
import org.ratelog.movie.top.TopMovieHandler
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.RatingFactory
import org.ratelog.test.TvRatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.test.UserFactory
import org.ratelog.tvshow.premieres.TvShowPremieresHandler
import org.ratelog.tvshow.rating.TvRatingDAO
import org.ratelog.tvshow.top.GetTopTvShowsResponse
import org.ratelog.tvshow.top.TopTvShowController
import org.ratelog.tvshow.top.TopTvShowHandler
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.ui.ExtendedModelMap

/**
 * User request 2026-11-23: "the ratings page only natively loads 10 the default but can we just have it load more as
 * u scroll?" The rankings show 30, then 30 more per page as you scroll: pages must line up (no title twice, none
 * skipped), ranks continue, filters apply to every page, and the footer knows when the list ends.
 */
class RankingScrollTest {

    private val user = UserFactory.aUser(id = 1)
    private val userId = user.id!!

    // --- Movies, through the controller (what the page and the next-page fragment get) ---

    private val tags = InMemoryTagRepository()
    private val movieRatings = InMemoryRatingRepository(tags)
    private val movies = InMemoryMovieRepository()
    private val movieDescriptions = InMemoryMovieDescriptionRepository()
    private val movieController = TopMovieController(
        TopMovieHandler(movieRatings, movies, movieDescriptions),
        MoviePremieresHandler(movies, movieDescriptions),
        tags,
    )

    private fun rateMovie(title: String, score: Double): Long {
        val movie = movies.save(MovieFactory.aMovie(originalTitle = title))
        movieRatings.save(RatingFactory.aRating(movieId = movie.id!!, userId = userId,
            directing = score, cinematography = score, acting = score, soundtrack = score, screenplay = score))
        return movie.id.value
    }

    /** 45 movies, "M01" best (score 9.9) down to "M45". */
    private fun rate45Movies(): List<Long> = (1..45).map { rateMovie("M%02d".format(it), 10.0 - it * 0.1) }

    private class Page(val view: String, val tops: List<GetTopMoviesResponse>, val page: RankingPage)

    @Suppress("UNCHECKED_CAST")
    private fun moviesPage(offset: Int? = null, limit: Int? = null, partial: Boolean? = null, tag: Long? = null, ratingCategory: String? = null): Page {
        val model = ExtendedModelMap()
        val view = movieController.topsPage(user, null, ratingCategory, limit, null, tag, offset, partial, model)
        return Page(view, model["tops"] as List<GetTopMoviesResponse>, model["rankingPage"] as RankingPage)
    }

    @Test
    fun `the first page shows 30 of 45, then the next page the other 15, ranks 1 to 45 in order with none twice`() {
        rate45Movies()

        val first = moviesPage()
        assertEquals("movies", first.view)
        assertEquals((1..30).toList(), first.tops.map { it.rank })
        assertTrue(first.page.hasMore)
        assertEquals(30, first.page.shown)
        assertEquals("/movies?offset=30&limit=30&partial=1", first.page.nextHref)

        val second = moviesPage(offset = 30, limit = 30, partial = true)
        assertEquals("movies :: ranking", second.view, "the next page is the ranking fragment alone")
        assertEquals((31..45).toList(), second.tops.map { it.rank }, "ranks continue")
        assertFalse(second.page.hasMore, "45 is the end")
        assertEquals(45, second.page.shown, "That's all 45")

        val all = (first.tops + second.tops).map { it.title }
        assertEquals((1..45).map { "M%02d".format(it) }, all)
        assertEquals(all.size, all.toSet().size, "no duplicates")
    }

    @Test
    fun `page boundaries - exactly 30 is one page with no Show more, 31 needs a second page of one`() {
        (1..30).forEach { rateMovie("M%02d".format(it), 10.0 - it * 0.1) }
        val exact = moviesPage()
        assertEquals(30, exact.tops.size)
        assertFalse(exact.page.hasMore, "one more row is fetched to know there's nothing after the 30th")

        rateMovie("M31", 5.0)
        val first = moviesPage()
        assertEquals(30, first.tops.size)
        assertTrue(first.page.hasMore)
        val last = moviesPage(offset = 30, partial = true)
        assertEquals(listOf(31), last.tops.map { it.rank })
        assertFalse(last.page.hasMore)
        assertEquals(31, last.page.shown)
    }

    @Test
    fun `ties at a page boundary keep one order across pages`() {
        // 40 movies with the same score: only the rating id orders them (as the SQL's ROW_NUMBER tiebreaker).
        (1..40).forEach { rateMovie("T%02d".format(it), 7.0) }
        val pages = moviesPage().tops + moviesPage(offset = 30, partial = true).tops

        assertEquals(40, pages.map { it.tmdbId }.toSet().size, "every title once")
        assertEquals((1..40).map { "T%02d".format(it) }, pages.map { it.title })
    }

    @Test
    fun `a reload after scrolling restores what was loaded (limit), and the next page continues after it`() {
        rate45Movies()
        // The script replaced the URL with ?limit=60 after loading the second page.
        val restored = moviesPage(limit = 60)
        assertEquals((1..45).toList(), restored.tops.map { it.rank })
        assertFalse(restored.page.hasMore)

        val forty = moviesPage(limit = 40)
        assertEquals(40, forty.tops.size)
        assertEquals("/movies?offset=40&limit=30&partial=1", forty.page.nextHref)
        assertEquals((41..45).toList(), moviesPage(offset = 40, partial = true).tops.map { it.rank })
    }

    @Test
    fun `a filter applies to every page, with overall ranks`() {
        val ids = rate45Movies()
        val comfort = tags.save(Tag(null, userId, TagName("comfort"), null, 0))
        // Every odd one tagged: 23 of 45.
        ids.filterIndexed { i, _ -> i % 2 == 0 }.forEach { tags.assign(comfort.id!!, MediaType.movie, it, 0) }

        val first = moviesPage(limit = 10, tag = comfort.id!!.value)
        assertEquals((1..19 step 2).toList(), first.tops.map { it.rank }, "overall ranks, as before")
        assertTrue(first.page.hasMore)
        assertEquals("/movies?tag=${comfort.id.value}&offset=10&limit=30&partial=1", first.page.nextHref, "the filter rides along")

        val rest = moviesPage(offset = 10, tag = comfort.id.value, partial = true)
        assertEquals((21..45 step 2).toList(), rest.tops.map { it.rank })
        assertFalse(rest.page.hasMore)
        assertEquals(23, rest.page.shown)
    }

    @Test
    fun `a category ranking pages over detailed ratings only`() {
        rate45Movies()
        val quick = movies.save(MovieFactory.aMovie(originalTitle = "Quick"))
        movieRatings.save(RatingFactory.aQuickRating(movieId = quick.id!!, userId = userId, score = 9.99))

        val overall = moviesPage().tops
        assertEquals("Quick", overall.first().title, "a quick rating ranks by its score overall")

        val first = moviesPage(ratingCategory = "acting")
        val second = moviesPage(offset = 30, ratingCategory = "acting", partial = true)
        assertEquals(45, (first.tops + second.tops).size)
        assertFalse((first.tops + second.tops).any { it.title == "Quick" })
        assertEquals("/movies?ratingCategory=acting&offset=30&limit=30&partial=1", first.page.nextHref)
    }

    @Test
    fun `past the end there's nothing, and bad paging values are clamped`() {
        rate45Movies()
        val past = moviesPage(offset = 90, partial = true)
        assertTrue(past.tops.isEmpty())
        assertFalse(past.page.hasMore)

        val negative = moviesPage(offset = -5, limit = -1)
        assertEquals(listOf(1), negative.tops.map { it.rank }, "offset 0, limit at least 1")
        assertEquals(0, negative.page.offset)
    }

    @Test
    fun `the full page still gets the filters' model, the fragment skips it`() {
        rate45Movies()
        val full = ExtendedModelMap()
        movieController.topsPage(user, null, null, null, null, null, null, null, full)
        assertNotNull(full["userTags"])

        val partial = ExtendedModelMap()
        movieController.topsPage(user, null, null, null, null, null, 30, true, partial)
        assertFalse(partial.containsAttribute("userTags"), "no tag list or premieres for the next cards")
        assertFalse(partial.containsAttribute("premieres"))
    }

    // --- TV, the same paging ---

    private val tvTags = InMemoryTagRepository()
    private val tvRatings = InMemoryTvRatingRepository(tvTags)
    private val shows = InMemoryTvShowRepository()
    private val tvDescriptions = InMemoryTvDescriptionRepository()
    private val tvController = TopTvShowController(
        TopTvShowHandler(tvRatings, shows, tvDescriptions),
        TvShowPremieresHandler(shows, tvDescriptions),
        tvTags,
    )

    private fun rateShow(name: String, score: Double) {
        val show = shows.save(TvShowFactory.aTvShow(originalName = name))
        val season = TvRatingFactory.aSeasonRating(tvShowId = show.id!!, seasonNumber = 1, userId = userId,
            directing = score, cinematography = score, acting = score, soundtrack = score, screenplay = score)
        tvRatings.save(TvRatingFactory.aTvRating(tvShowId = show.id, userId = userId, seasonRatings = listOf(season)))
    }

    @Suppress("UNCHECKED_CAST")
    private fun tvPage(offset: Int? = null, partial: Boolean? = null): Pair<List<GetTopTvShowsResponse>, RankingPage> {
        val model = ExtendedModelMap()
        val view = tvController.topsPage(user, null, null, null, null, null, offset, partial, model)
        assertEquals(if (partial == true) "tvshows :: ranking" else "tvshows", view)
        return (model["tops"] as List<GetTopTvShowsResponse>) to (model["rankingPage"] as RankingPage)
    }

    @Test
    fun `the TV ranking pages the same way`() {
        (1..35).forEach { rateShow("S%02d".format(it), 10.0 - it * 0.1) }

        val (first, firstPage) = tvPage()
        assertEquals((1..30).toList(), first.map { it.rank })
        assertEquals("/tvshows?offset=30&limit=30&partial=1", firstPage.nextHref)

        val (rest, restPage) = tvPage(offset = 30, partial = true)
        assertEquals((31..35).toList(), rest.map { it.rank })
        assertFalse(restPage.hasMore)
        assertEquals(35, restPage.shown)
        assertEquals((1..35).map { "S%02d".format(it) }, (first + rest).map { it.name })
    }

    // --- The footer's links ---

    @Test
    fun `window defaults to one page of 30 from the top`() {
        assertEquals(RankingScroll.Window(0, 30), RankingScroll.window(null, null))
        assertEquals(31, RankingScroll.window(null, null).fetch)
        assertEquals(RankingScroll.Window(60, 30), RankingScroll.window(60, null))
        assertEquals(RankingScroll.Window(0, 1000), RankingScroll.window(-1, 5000))
    }

    @Test
    fun `the footer's links carry the set filters, encoded, and only the address after page one says how many`() {
        val window = RankingScroll.window(null, null)
        val page = RankingScroll.page("/movies", window, 31,
            listOf("category" to "28", "tag" to null, "ratingCategory" to "", "name" to "C++ & co"))

        assertEquals("/movies?category=28&name=C%2B%2B+%26+co&limit=60#rank-pos-31", page.moreHref, "no-JS: one more page, at the first new card")
        assertEquals("/movies?category=28&name=C%2B%2B+%26+co&offset=30&limit=30&partial=1", page.nextHref)
        assertEquals("/movies?category=28&name=C%2B%2B+%26+co", page.addressHref, "page one: no limit in the address")
        assertEquals(1, page.position(1), "the first card's anchor on page one")

        val second = RankingScroll.page("/movies", RankingScroll.window(30, 30), 15, emptyList())
        assertEquals("/movies?limit=45", second.addressHref, "a reload shows the 45 loaded")
        assertEquals(45, second.position(15))
    }

    // --- The query behind it ---

    @Test
    fun `the ranking queries page with OFFSET and break ties by rating id`() {
        listOf(
            RatingDAO::class.java.getMethod("findRankedRows", Long::class.java, String::class.java, String::class.java, Int::class.java, String::class.java, Long::class.javaObjectType, Int::class.java),
            TvRatingDAO::class.java.getMethod("findRankedRows", Long::class.java, String::class.java, String::class.java, Int::class.java, String::class.java, Long::class.javaObjectType, Int::class.java),
        ).forEach { method ->
            val sql = method.getAnnotation(Query::class.java).value
            assertTrue(sql.contains("END DESC NULLS LAST, r.id"), "${method.declaringClass.simpleName}: a stable order across pages")
            assertTrue(sql.contains("ORDER BY ranked.rank\n        LIMIT :limit OFFSET :offset") || sql.contains("LIMIT :limit OFFSET :offset"), method.declaringClass.simpleName)
        }
    }
}
