package org.ratelog.extras

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TrailerSelectorTest {

    private fun video(
        key: String,
        type: String = "Trailer",
        official: Boolean? = true,
        site: String = "YouTube",
        language: String? = "en",
        size: Int? = 1080,
        publishedAt: String? = "2020-01-01T00:00:00.000Z",
    ) = TmdbVideo(key = key, site = site, type = type, official = official, name = "$type $key", language = language, size = size, publishedAt = publishedAt)

    @Test
    fun `prefers an official YouTube trailer over everything else`() {
        val videos = listOf(
            video("teaserOff01", type = "Teaser"),
            video("trailerUno1", official = false),
            video("vimeoTrail1", site = "Vimeo"),
            video("featurette1", type = "Featurette"),
            video("officialTr1"),
        )
        assertEquals("officialTr1", TrailerSelector.select(videos))
    }

    @Test
    fun `falls back to any YouTube trailer, then official teasers, then teasers`() {
        assertEquals("trailerUno1", TrailerSelector.select(listOf(video("teaserOff01", type = "Teaser"), video("trailerUno1", official = false))))
        assertEquals("teaserOff01", TrailerSelector.select(listOf(video("teaserUno01", type = "Teaser", official = false), video("teaserOff01", type = "Teaser"))))
        assertEquals("teaserUno01", TrailerSelector.select(listOf(video("teaserUno01", type = "Teaser", official = null), video("clip0000001", type = "Clip"))))
    }

    @Test
    fun `among equal trailers prefers English, then higher resolution, then the earliest upload`() {
        assertEquals("englishTr01", TrailerSelector.select(listOf(video("spanishTr01", language = "es"), video("englishTr01"))))
        assertEquals("hdTrailer01", TrailerSelector.select(listOf(video("sdTrailer01", size = 480), video("hdTrailer01", size = 1080))))
        assertEquals(
            "firstTrail1",
            TrailerSelector.select(
                listOf(
                    video("finalTrail1", publishedAt = "2021-05-01T00:00:00.000Z"),
                    video("firstTrail1", publishedAt = "2020-11-01T00:00:00.000Z"),
                )
            ),
        )
    }

    @Test
    fun `ignores non-YouTube videos and keys that are not safe YouTube ids`() {
        assertNull(TrailerSelector.select(listOf(video("abc\"><script>"), video("12345", site = "YouTube"), video("vimeo000001", site = "Vimeo"))))
        assertNull(TrailerSelector.select(emptyList()))
        assertEquals("site0case01", TrailerSelector.select(listOf(video("site0case01", site = "youtube", type = "trailer"))))
    }
}
