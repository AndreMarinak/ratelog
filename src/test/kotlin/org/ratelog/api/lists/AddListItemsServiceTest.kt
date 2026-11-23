package org.ratelog.api.lists

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.ratelog.Lang
import org.ratelog.ListName
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.api.ItemInput
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.media.TitleResolver
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.movie.MovieDescription
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.tag.TagRepository
import org.ratelog.tag.assign.AssignTagsHandler
import org.ratelog.test.InMemoryCustomListRepository
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryTagRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.InMemoryListOrderRepository
import org.ratelog.customlist.order.move.MoveListItemHandler
import org.ratelog.test.MovieFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.tmdb.TmdbClient
import org.ratelog.tmdb.TmdbError
import org.ratelog.tmdb.TmdbMovieResponse
import org.ratelog.tmdb.TmdbTvShowResponse
import org.ratelog.tvshow.TvDescription
import org.ratelog.user.User

class AddListItemsServiceTest {

    private val tmdbClient: TmdbClient = mock()
    private val lists = InMemoryCustomListRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val tags = InMemoryTagRepository()
    private val service = AddListItemsService(
        TitleResolver(tmdbClient),
        MediaImporter(tmdbClient, movies, InMemoryMovieDescriptionRepository(), shows, InMemoryTvDescriptionRepository()),
        AddToListHandler(lists),
        AssignTagsHandler(tags, movies, shows),
        lists,
        MoveListItemHandler(lists, InMemoryListOrderRepository(lists)),
    )
    private val user = User.Id(1)
    private lateinit var listId: CustomList.Id

    @BeforeEach
    fun setUp() {
        listId = lists.save(CustomList(null, user, ListName("Researched"), false, System.currentTimeMillis())).id!!

        // Defaults: empty searches, no translations. Specific stubs below override these.
        whenever(tmdbClient.searchMovies(any(), any(), any())).thenReturn((emptyList<TmdbMovieResponse>() to 1).right())
        whenever(tmdbClient.searchTvShows(any(), any(), any())).thenReturn((emptyList<TmdbTvShowResponse>() to 1).right())
        whenever(tmdbClient.movieTranslations(any(), any())).thenReturn(emptyList<MovieDescription>().right())
        whenever(tmdbClient.tvTranslations(any(), any())).thenReturn(emptyList<TvDescription>().right())

        whenever(tmdbClient.searchMovies("Heat", Lang.en, 1)).thenReturn(
            (listOf(
                TmdbMovieResponse(949, "Heat", "Heat", releaseDate = "1995-12-15"),
                TmdbMovieResponse(11152, "Heat", "Heat", releaseDate = "1986-03-14"),
            ) to 1).right()
        )
        whenever(tmdbClient.movieDetails(TmdbId(949))).thenReturn(
            MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseYear = 1995).right()
        )
        whenever(tmdbClient.tvShowDetails(TmdbId(1396))).thenReturn(
            TvShowFactory.aTvShow(tmdbId = 1396, originalName = "Breaking Bad", firstAirYear = 2008).right()
        )
    }

    @Test
    fun `adds a title matched by name and year, in order`() {
        val result = service.addItems(
            listId, user, Lang.en,
            listOf(ItemInput(title = "Heat", year = 1995), ItemInput(tmdbId = 1396, type = "tv")),
        )

        assertEquals(listOf(949, 1396), result.added.map { it.tmdbId })
        assertEquals(listOf("movie", "tv"), result.added.map { it.type })
        assertEquals(listOf(949, 1396), lists.findById(listId)!!.items.sortedBy { it.position }.map {
            if (it.mediaType.name == "movie") movies.findById(org.ratelog.movie.Movie.Id(it.mediaId))!!.tmdbId.value
            else shows.findById(org.ratelog.tvshow.TvShow.Id(it.mediaId))!!.tmdbId.value
        })
    }

    @Test
    fun `reports duplicates instead of failing`() {
        service.addItems(listId, user, Lang.en, listOf(ItemInput(title = "Heat", year = 1995)))
        val second = service.addItems(listId, user, Lang.en, listOf(ItemInput(title = "Heat", year = 1995)))

        assertEquals(0, second.added.size)
        assertEquals(listOf(949), second.alreadyInList.map { it.tmdbId })
        assertEquals(1, lists.findById(listId)!!.items.size)
    }

    @Test
    fun `ambiguous title is reported with candidates and nothing is added`() {
        val result = service.addItems(listId, user, Lang.en, listOf(ItemInput(title = "Heat")))

        assertEquals("ambiguous", result.unresolved.single().reason)
        assertEquals(setOf(949, 11152), result.unresolved.single().candidates.map { it.tmdbId }.toSet())
        assertEquals(0, lists.findById(listId)!!.items.size)
    }

    @Test
    fun `unknown title is not found`() {
        val result = service.addItems(listId, user, Lang.en, listOf(ItemInput(title = "Definitely Not A Film", year = 2001)))
        assertEquals("not_found", result.unresolved.single().reason)
    }

    @Test
    fun `tmdbId without a type is rejected because movie and tv ids overlap`() {
        val result = service.addItems(listId, user, Lang.en, listOf(ItemInput(tmdbId = 949)))
        assertEquals("invalid", result.unresolved.single().reason)
    }

    @Test
    fun `bad type and empty item are invalid but do not stop the batch`() {
        val result = service.addItems(
            listId, user, Lang.en,
            listOf(ItemInput(title = "Heat", type = "podcast"), ItemInput(), ItemInput(title = "Heat", year = 1995, type = "film")),
        )
        assertEquals(listOf("invalid", "invalid"), result.unresolved.map { it.reason })
        assertEquals(listOf(949), result.added.map { it.tmdbId })
    }

    @Test
    fun `missing TMDB key is reported per item`() {
        whenever(tmdbClient.searchMovies("Heat", Lang.en, 1)).thenReturn(TmdbError.ApiKeyMissing.left())
        val result = service.addItems(listId, user, Lang.en, listOf(ItemInput(title = "Heat", year = 1995, type = "movie")))
        assertEquals("tmdb_error", result.unresolved.single().reason)
    }

    @Test
    fun `existing local titles are reused without calling TMDB details`() {
        movies.save(MovieFactory.aMovie(tmdbId = 603, originalTitle = "The Matrix", releaseYear = 1999))
        val result = service.addItems(listId, user, Lang.en, listOf(ItemInput(tmdbId = 603, type = "movie")))
        assertEquals(listOf(603), result.added.map { it.tmdbId })
        assertEquals("The Matrix", result.added.single().title)
    }

    @Test
    fun `item tags are applied to added and already-present titles`() {
        service.addItems(listId, user, Lang.en, listOf(ItemInput(tmdbId = 1396, type = "tv")))

        val result = service.addItems(
            listId, user, Lang.en,
            listOf(
                ItemInput(title = "Heat", year = 1995, tags = listOf("heist", "Comfort watch")),
                ItemInput(tmdbId = 1396, type = "tv", tags = listOf("comfort watch")),
            ),
        )

        assertEquals(listOf(949), result.added.map { it.tmdbId })
        assertEquals(listOf(1396), result.alreadyInList.map { it.tmdbId })
        val heat = movies.findByTmdbId(TmdbId(949))!!.id!!.value
        val breakingBad = shows.findByTmdbId(TmdbId(1396))!!.id!!.value
        assertEquals(listOf("Comfort watch", "heist"), tags.findForTitle(user, MediaType.movie, heat).map { it.name.value })
        assertEquals(listOf("Comfort watch"), tags.findForTitle(user, MediaType.tvshow, breakingBad).map { it.name.value })
        assertEquals(2, tags.findByUserId(user).size)
    }

    @Test
    fun `an invalid tag name makes the item invalid and nothing is added`() {
        val result = service.addItems(listId, user, Lang.en, listOf(ItemInput(title = "Heat", year = 1995, tags = listOf("x".repeat(41)))))

        assertEquals("invalid", result.unresolved.single().reason)
        assertEquals(0, lists.findById(listId)!!.items.size)
        assertTrue(tags.findByUserId(user).isEmpty())
    }

    @Test
    fun `when the tag limit is reached the item is reported and not added`() {
        repeat(TagRepository.MAX_TAGS_PER_USER) { tags.save(Tag(null, user, TagName("tag $it"), null, 0)) }

        val result = service.addItems(listId, user, Lang.en, listOf(ItemInput(title = "Heat", year = 1995, tags = listOf("brand new"))))

        assertEquals("invalid", result.unresolved.single().reason)
        assertEquals(0, lists.findById(listId)!!.items.size)
    }

    @Test
    fun `items without tags leave tags untouched`() {
        service.addItems(listId, user, Lang.en, listOf(ItemInput(title = "Heat", year = 1995)))
        assertTrue(tags.findByUserId(user).isEmpty())
    }
}
