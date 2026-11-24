package org.ratelog.watchlist.entry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.Score
import org.ratelog.SeasonNumber
import org.ratelog.movie.rating.add.AddRating
import org.ratelog.movie.rating.add.AddRatingHandler
import org.ratelog.test.InMemoryMediaStatusRepository
import org.ratelog.test.InMemoryMovieDescriptionRepository
import org.ratelog.test.InMemoryMovieRepository
import org.ratelog.test.InMemoryRatingRepository
import org.ratelog.test.InMemoryTvDescriptionRepository
import org.ratelog.test.InMemoryTvRatingRepository
import org.ratelog.test.InMemoryTvShowRepository
import org.ratelog.test.MovieFactory
import org.ratelog.test.MutableClock
import org.ratelog.test.RatingFactory
import org.ratelog.test.TvShowFactory
import org.ratelog.test.WatchlistHandlers
import org.ratelog.tvshow.rating.addseason.AddSeasonRating
import org.ratelog.tvshow.rating.addseason.AddSeasonRatingHandler
import org.ratelog.user.User
import org.ratelog.watchlist.MediaStatus
import org.ratelog.watchlist.WatchStatus
import org.ratelog.watchlist.autowatched.AutoWatchedRatingRepository
import org.ratelog.watchlist.autowatched.AutoWatchedTvRatingRepository
import org.ratelog.watchlist.autowatched.MarkWatchedOnRatingHandler
import org.ratelog.watchlist.getwatchlist.GetWatchlist
import org.ratelog.watchlist.getwatchlist.GetWatchlistHandler
import org.ratelog.watchlist.getwatchlist.WatchlistController
import org.ratelog.watchlist.getwatchlist.WatchlistItemResponse
import org.ratelog.watchlist.move.MoveWatchlistItem
import org.ratelog.watchlist.move.MoveWatchlistItemHandler
import org.ratelog.watchlist.move.WatchlistMove
import org.ratelog.watchlist.seen.SeenStateReader
import org.ratelog.watchlist.seen.ToggleSeen
import org.ratelog.watchlist.seen.ToggleSeenHandler
import org.ratelog.watchlist.seen.ToggleSeenOutcome
import org.ratelog.watchlist.setstatus.SetWatchStatus
import org.springframework.ui.ExtendedModelMap
import java.time.LocalDate

/**
 * Fork (2026-11-24), user: "on movies I've already seen I should still be able to add it to the general watchlist".
 * Seen and the watchlist are independent: a seen (Watched or rated) title can be on the watchlist and stays seen.
 */
class RewatchTest {
    private val statuses = InMemoryMediaStatusRepository()
    private val movies = InMemoryMovieRepository()
    private val shows = InMemoryTvShowRepository()
    private val baseRatings = InMemoryRatingRepository()
    private val baseTvRatings = InMemoryTvRatingRepository()
    private val clock = MutableClock() // 2026-11-22
    private val markWatched = MarkWatchedOnRatingHandler(statuses, shows, clock)
    private val ratings = AutoWatchedRatingRepository(baseRatings, markWatched)
    private val tvRatings = AutoWatchedTvRatingRepository(baseTvRatings, markWatched)
    private val reader = SeenStateReader(statuses, baseRatings, baseTvRatings)
    private val watchlist = SetWatchlistHandler(statuses, movies, shows, reader, clock)
    private val setStatus = WatchlistHandlers.setStatus(statuses, movies, shows, baseRatings, baseTvRatings)
    private val toggleSeen = ToggleSeenHandler(statuses, movies, shows, reader, clock)
    private val move = MoveWatchlistItemHandler(setStatus, WatchlistHandlers.setWatchlist(statuses, movies, shows, baseRatings, baseTvRatings), statuses)
    private val getWatchlist = GetWatchlistHandler(
        statuses, movies, InMemoryMovieDescriptionRepository(), baseRatings, shows, InMemoryTvDescriptionRepository(), baseTvRatings,
    )

    private val user = User.Id(1)
    private val heat = movies.save(MovieFactory.aMovie(tmdbId = 949, originalTitle = "Heat", releaseDate = LocalDate.parse("1995-12-15"))).id!!.value
    private val up = movies.save(MovieFactory.aMovie(tmdbId = 14160, originalTitle = "Up", releaseDate = LocalDate.parse("2009-05-29"))).id!!.value
    private val severance = shows.save(
        TvShowFactory.aTvShow(tmdbId = 95396, originalName = "Severance", firstAirDate = LocalDate.parse("2022-02-18"),
            lastSeasonNumber = 2, lastSeasonAirDate = LocalDate.parse("2025-01-17"), nextSeasonAirDate = null),
    ).id!!.value

    private fun watched(type: MediaType, id: Long) = statuses.save(MediaStatus(user, type, id, WatchStatus.WATCHED, 1L))
    private fun rate(id: Long, score: Double = 8.0) =
        AddRatingHandler(ratings).handle(AddRating(org.ratelog.movie.Movie.Id(id), user, score, score, score, score, score, null, null))
    private fun rateSeason(season: Int) = AddSeasonRatingHandler(tvRatings).handle(
        AddSeasonRating(org.ratelog.tvshow.TvShow.Id(severance), SeasonNumber(season), user, Score(8.0), Score(8.0), Score(8.0), Score(8.0), Score(8.0), null, null),
    )
    private fun add(type: MediaType, id: Long) = watchlist.handle(SetWatchlist(user, type, id, on = true)).getOrNull()!!
    private fun remove(type: MediaType, id: Long) = watchlist.handle(SetWatchlist(user, type, id, on = false)).getOrNull()!!
    private fun entry(type: MediaType, id: Long) = statuses.findWatchlistEntry(user, type, id)
    private fun status(type: MediaType, id: Long) = statuses.find(user, type, id)?.status

    // --- Rule 1-2: a seen title goes on the watchlist and stays seen ---

    @Test
    fun `a Watched movie goes on the watchlist, stays Watched and seen, and comes off still seen`() {
        watched(MediaType.movie, heat)

        val added = add(MediaType.movie, heat)
        assertTrue(added.changed)
        assertTrue(added.after.onWatchlist)
        assertTrue(added.after.seen)
        assertEquals(0, added.seenDelta, "never unseen")
        assertEquals(WatchStatus.WATCHED, status(MediaType.movie, heat))
        assertEquals(true, entry(MediaType.movie, heat)?.rewatch)

        val removed = remove(MediaType.movie, heat)
        assertFalse(removed.after.onWatchlist)
        assertTrue(removed.after.seen)
        assertEquals(WatchStatus.WATCHED, status(MediaType.movie, heat), "removing a rewatch leaves it seen")
    }

    @Test
    fun `a rated movie with no status goes on the watchlist and stays seen through its rating`() {
        baseRatings.save(RatingFactory.aQuickRating(movieId = org.ratelog.movie.Movie.Id(heat), userId = user, score = 9.0))

        val added = add(MediaType.movie, heat)
        assertTrue(added.after.onWatchlist)
        assertTrue(added.after.seen)
        assertTrue(added.after.seenLocked)
        assertEquals(true, entry(MediaType.movie, heat)?.rewatch)
        assertNull(status(MediaType.movie, heat))
    }

    @Test
    fun `adding twice changes nothing and keeps the date`() {
        add(MediaType.movie, up)
        val first = entry(MediaType.movie, up)!!
        clock.advance(java.time.Duration.ofDays(1))
        assertFalse(add(MediaType.movie, up).changed)
        assertEquals(first, entry(MediaType.movie, up))
    }

    // --- Rule 4: what takes a title off the watchlist ---

    @Test
    fun `marking an unseen watchlist title seen takes it off, as before`() {
        add(MediaType.movie, up)

        val result = toggleSeen.handle(ToggleSeen(user, MediaType.movie, up)).getOrNull()!!

        assertEquals(ToggleSeenOutcome.MARKED_SEEN, result.outcome)
        assertFalse(result.state.onWatchlist)
        assertNull(entry(MediaType.movie, up))
        assertEquals(WatchStatus.WATCHED, status(MediaType.movie, up))
    }

    @Test
    fun `rating an unseen watchlist title takes it off, rating a rewatch doesn't`() {
        add(MediaType.movie, up)
        rate(up)
        assertNull(entry(MediaType.movie, up), "added while unseen: seen now, off the watchlist")
        assertEquals(WatchStatus.WATCHED, status(MediaType.movie, up))

        watched(MediaType.movie, heat)
        add(MediaType.movie, heat)
        rate(heat, 9.0)
        assertEquals(true, entry(MediaType.movie, heat)?.rewatch, "a rewatch stays on the watchlist when rated")
        assertEquals(WatchStatus.WATCHED, status(MediaType.movie, heat))
    }

    @Test
    fun `a rated movie with no status put on the watchlist stays there when rated again`() {
        baseRatings.save(RatingFactory.aQuickRating(movieId = org.ratelog.movie.Movie.Id(heat), userId = user, score = 7.0))
        add(MediaType.movie, heat)

        markWatched.handle(org.ratelog.watchlist.autowatched.MarkWatchedOnRating(user, MediaType.movie, heat))

        assertEquals(WatchStatus.WATCHED, status(MediaType.movie, heat))
        assertTrue(entry(MediaType.movie, heat) != null)
    }

    @Test
    fun `setting Watched keeps a rewatch and takes an unseen entry off, Watching and Dropped take it off`() {
        watched(MediaType.movie, heat)
        add(MediaType.movie, heat)
        val kept = setStatus.handle(SetWatchStatus(user, MediaType.movie, heat, WatchStatus.WATCHED)).getOrNull()!!
        assertTrue(kept.onWatchlist)
        assertFalse(kept.changed)

        add(MediaType.movie, up)
        val seen = setStatus.handle(SetWatchStatus(user, MediaType.movie, up, WatchStatus.WATCHED)).getOrNull()!!
        assertEquals(WatchStatus.WANT_TO_WATCH, seen.previous)
        assertEquals(WatchStatus.WATCHED, seen.current)
        assertFalse(seen.onWatchlist)

        add(MediaType.tvshow, severance)
        setStatus.handle(SetWatchStatus(user, MediaType.tvshow, severance, WatchStatus.WATCHING))
        assertNull(entry(MediaType.tvshow, severance), "To watch -> Watching, as before")
        assertEquals(WatchStatus.WATCHING, status(MediaType.tvshow, severance))
    }

    @Test
    fun `want_to_watch through the status keeps Watched, replaces Watching and Dropped, and null clears both`() {
        watched(MediaType.movie, heat)
        val rewatch = setStatus.handle(SetWatchStatus(user, MediaType.movie, heat, WatchStatus.WANT_TO_WATCH)).getOrNull()!!
        assertEquals(WatchStatus.WATCHED, rewatch.previous)
        assertEquals(WatchStatus.WATCHED, rewatch.current, "still watched")
        assertTrue(rewatch.onWatchlist)
        assertTrue(rewatch.changed)

        statuses.save(MediaStatus(user, MediaType.tvshow, severance, WatchStatus.DROPPED, 1L))
        val back = setStatus.handle(SetWatchStatus(user, MediaType.tvshow, severance, WatchStatus.WANT_TO_WATCH)).getOrNull()!!
        assertEquals(WatchStatus.DROPPED, back.previous)
        assertEquals(WatchStatus.WANT_TO_WATCH, back.current)
        assertNull(status(MediaType.tvshow, severance))

        val cleared = setStatus.handle(SetWatchStatus(user, MediaType.movie, heat, null)).getOrNull()!!
        assertNull(cleared.current)
        assertFalse(cleared.onWatchlist)
        assertNull(status(MediaType.movie, heat))
    }

    @Test
    fun `un-seeing a rewatch keeps it on the watchlist as unseen, so seeing it again takes it off`() {
        watched(MediaType.movie, heat)
        add(MediaType.movie, heat)

        val unseen = toggleSeen.handle(ToggleSeen(user, MediaType.movie, heat)).getOrNull()!!
        assertEquals(ToggleSeenOutcome.UNMARKED, unseen.outcome)
        assertTrue(unseen.state.onWatchlist)
        assertEquals(false, entry(MediaType.movie, heat)?.rewatch)

        rate(heat)
        assertNull(entry(MediaType.movie, heat))
    }

    // --- Rule 6: TV ---

    @Test
    fun `a Watched show and a show rated as a whole go on the watchlist and stay seen`() {
        watched(MediaType.tvshow, severance)
        val added = add(MediaType.tvshow, severance)
        assertTrue(added.after.seen)
        assertTrue(added.after.onWatchlist)
        assertEquals(WatchStatus.WATCHED, status(MediaType.tvshow, severance))

        // Rating another season of a Watched show changes nothing, and it stays on the watchlist.
        rateSeason(1)
        assertEquals(WatchStatus.WATCHED, status(MediaType.tvshow, severance))
        assertTrue(entry(MediaType.tvshow, severance) != null)
    }

    @Test
    fun `an unseen show on the watchlist leaves it for Watching when a season is rated, as before`() {
        add(MediaType.tvshow, severance)
        rateSeason(1)
        assertEquals(WatchStatus.WATCHING, status(MediaType.tvshow, severance))
        assertNull(entry(MediaType.tvshow, severance))
    }

    @Test
    fun `the poster bookmark doesn't touch a Watching show`() {
        statuses.save(MediaStatus(user, MediaType.tvshow, severance, WatchStatus.WATCHING, 1L))
        val controller = org.ratelog.watchlist.quickadd.QuickWatchlistController(org.mockito.kotlin.mock(), statuses, watchlist)
        assertEquals(org.ratelog.watchlist.quickadd.QuickWatchlistOutcome.UNCHANGED, controller.toggle(user, MediaType.tvshow, severance))
        assertNull(entry(MediaType.tvshow, severance))
    }

    // --- Rule 3: the Watchlist page ---

    @Test
    fun `To watch lists rewatches with the Seen tab still counting them, and tags them`() {
        watched(MediaType.movie, heat)
        add(MediaType.movie, heat)
        clock.advance(java.time.Duration.ofDays(1))
        add(MediaType.movie, up)

        val toWatch = getWatchlist.handle(GetWatchlist(user, WatchStatus.WANT_TO_WATCH, MediaType.movie, Lang.en))
        assertEquals(listOf("Up", "Heat"), toWatch.items.map { it.title }, "newest added first")
        assertEquals(listOf(false, true), toWatch.items.map { it.rewatch })
        assertEquals(listOf(WatchStatus.WANT_TO_WATCH, WatchStatus.WATCHED), toWatch.items.map { it.status })
        assertEquals(2, toWatch.counts[WatchStatus.WANT_TO_WATCH])
        assertEquals(1, toWatch.counts[WatchStatus.WATCHED], "still counted as seen")

        val seenTab = getWatchlist.handle(GetWatchlist(user, WatchStatus.WATCHED, MediaType.movie, Lang.en))
        assertEquals(listOf("Heat"), seenTab.items.map { it.title })
        assertTrue(seenTab.items.single().onWatchlist)

        val all = getWatchlist.handle(GetWatchlist(user, null, null, Lang.en))
        assertEquals(2, all.items.size, "once per title")

        val page = WatchlistController(getWatchlist, clock)
        val model = ExtendedModelMap()
        page.watchlistPage(org.ratelog.test.UserFactory.aUser(id = 1), "want_to_watch", "movie", model)
        @Suppress("UNCHECKED_CAST")
        val items = (model["items"] as List<WatchlistItemResponse>).associateBy { it.title }
        val heatCard = items.getValue("Heat")
        assertTrue(heatCard.rewatch)
        assertEquals(WatchlistController.NOTE_REWATCH, heatCard.noteKey)
        assertEquals(listOf("unlist"), heatCard.actions.map { it.to }, "already seen: only Remove")
        assertEquals(2, heatCard.noteSpan)
        assertTrue(heatCard.tileActions!!.seen, "the poster keeps its eye")
        assertTrue(heatCard.tileActions!!.onWatchlist)
        assertEquals(listOf("seen", "unlist"), items.getValue("Up").actions.map { it.to })
        assertFalse(items.getValue("Up").rewatch)
    }

    @Test
    fun `Remove on To watch keeps a rewatch seen, Undo puts it back, and Seen from To watch undoes to unseen`() {
        watched(MediaType.movie, heat)
        add(MediaType.movie, heat)

        val unlisted = move.handle(MoveWatchlistItem(user, MediaType.movie, heat, WatchlistMove.UNLIST)).getOrNull()!!
        assertTrue(unlisted.changed)
        assertEquals(WatchStatus.WANT_TO_WATCH, unlisted.previous, "leaves To watch")
        assertNull(unlisted.current, "stays in Seen, so no tab gains it")
        assertEquals(WatchlistMove.RELIST, unlisted.undo)
        assertEquals(WatchStatus.WATCHED, status(MediaType.movie, heat))
        assertNull(entry(MediaType.movie, heat))

        move.handle(MoveWatchlistItem(user, MediaType.movie, heat, WatchlistMove.RELIST))
        assertEquals(true, entry(MediaType.movie, heat)?.rewatch)
        assertEquals(WatchStatus.WATCHED, status(MediaType.movie, heat))

        add(MediaType.movie, up)
        val seen = move.handle(MoveWatchlistItem(user, MediaType.movie, up, WatchlistMove.SEEN)).getOrNull()!!
        assertEquals(WatchStatus.WANT_TO_WATCH, seen.previous)
        assertEquals(WatchStatus.WATCHED, seen.current)
        assertEquals(WatchlistMove.TO_WATCH, seen.undo)
        move.handle(MoveWatchlistItem(user, MediaType.movie, up, WatchlistMove.TO_WATCH))
        assertNull(status(MediaType.movie, up), "back to unseen")
        assertEquals(false, entry(MediaType.movie, up)?.rewatch)

        // Remove on the Seen tab un-sees a rewatch but leaves it on the watchlist; Undo (Seen) keeps it there.
        val unsee = move.handle(MoveWatchlistItem(user, MediaType.movie, heat, WatchlistMove.REMOVE)).getOrNull()!!
        assertEquals(WatchStatus.WATCHED, unsee.previous)
        assertNull(unsee.current)
        assertEquals(WatchlistMove.SEEN, unsee.undo)
        assertTrue(entry(MediaType.movie, heat) != null)
        move.handle(MoveWatchlistItem(user, MediaType.movie, heat, WatchlistMove.SEEN))
        assertEquals(WatchStatus.WATCHED, status(MediaType.movie, heat))
        assertTrue(entry(MediaType.movie, heat) != null)
    }
}
