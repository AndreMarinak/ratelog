package org.ratelog.extras.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.MediaType
import org.ratelog.extras.ExternalScores
import org.ratelog.extras.gettitleinfo.TitleInfo

class TitleInfoViewTest {

    private fun info(
        type: MediaType = MediaType.movie,
        title: String? = "Crouching Tiger, Hidden Dragon",
        scores: ExternalScores? = ExternalScores(imdbRating = 7.9, imdbVotes = 290_123, rtCritics = 97, metacritic = 45, letterboxd = 3.96),
    ) = TitleInfo(
        type = type, tmdbId = 146, title = title, tmdbVoteAverage = 7.43, tmdbVoteCount = 3_456,
        runtimeMinutes = 120, numberOfSeasons = null, trailerYoutubeKey = "abcDEF_1234", imdbId = "tt0190332",
        scores = scores, scoresFetchedAt = null,
    )

    @Test
    fun `formats scores and vote counts compactly`() {
        val view = TitleInfoView(info())

        assertEquals("7.4", view.tmdbScore)
        assertEquals("3.5K", view.tmdbVotes)
        assertEquals("7.9", view.imdbRating)
        assertEquals("290K", view.imdbVotes)
        assertEquals("4.0", view.letterboxd)
        assertEquals("mixed", view.metacriticTone)
    }

    @Test
    fun `compact counts`() {
        assertEquals("950", TitleInfoView.compactCount(950))
        assertEquals("1K", TitleInfoView.compactCount(1_000))
        assertEquals("25K", TitleInfoView.compactCount(25_400))
        assertEquals("1M", TitleInfoView.compactCount(1_000_000))
        assertEquals("2.1M", TitleInfoView.compactCount(2_134_567))
    }

    @Test
    fun `builds the external links`() {
        val view = TitleInfoView(info())

        assertEquals("https://www.imdb.com/title/tt0190332/", view.imdbUrl)
        assertEquals("https://www.rottentomatoes.com/search?search=Crouching+Tiger%2C+Hidden+Dragon", view.rtSearchUrl)
        assertEquals("https://letterboxd.com/tmdb/146/", view.letterboxdUrl)
        assertEquals("https://www.youtube-nocookie.com/embed/abcDEF_1234?autoplay=1&rel=0", view.trailerEmbedUrl)
        assertEquals("https://i.ytimg.com/vi/abcDEF_1234/hqdefault.jpg", view.trailerThumbnailUrl)
        assertEquals("https://i.ytimg.com/vi/abcDEF_1234/maxresdefault.jpg", view.trailerThumbnailHdUrl)
        assertNull(TitleInfoView(info(type = MediaType.tvshow)).letterboxdUrl)
        assertNull(TitleInfoView(info(title = null)).rtSearchUrl)
    }

    @Test
    fun `knows which sections have something to show`() {
        val withScores = TitleInfoView(info())
        assertTrue(withScores.hasScores)
        assertTrue(withScores.hasRt)
        assertTrue(withScores.hasFacts)

        val withoutScores = TitleInfoView(info(scores = null))
        assertFalse(withoutScores.hasScores)
        assertFalse(withoutScores.hasRt)
        assertNull(withoutScores.imdbRating)
    }
}
