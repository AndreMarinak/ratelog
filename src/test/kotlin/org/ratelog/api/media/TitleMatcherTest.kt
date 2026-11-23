package org.ratelog.api.media

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.ratelog.MediaType

class TitleMatcherTest {

    private fun movie(id: Int, title: String, year: Int?, original: String = title) =
        Candidate(id, MediaType.movie, title, original, year, null, null, null)

    private fun show(id: Int, title: String, year: Int?) =
        Candidate(id, MediaType.tvshow, title, title, year, null, null, null)

    private val dune = listOf(
        movie(438631, "Dune", 2021),
        movie(841, "Dune", 1984),
        show(90228, "Dune: Prophecy", 2024),
        movie(693134, "Dune: Part Two", 2024),
    )

    @Test
    fun `picks the exact title and year`() {
        val result = TitleMatcher.match("Dune", 1984, dune)
        assertEquals(841, (result as MatchResult.Matched).candidate.tmdbId)
    }

    @Test
    fun `same title without a year is ambiguous`() {
        val result = TitleMatcher.match("Dune", null, dune)
        assertEquals(setOf(438631, 841), (result as MatchResult.Ambiguous).candidates.map { it.tmdbId }.toSet())
    }

    @Test
    fun `unique title without a year matches`() {
        val result = TitleMatcher.match("Dune: Part Two", null, dune)
        assertEquals(693134, (result as MatchResult.Matched).candidate.tmdbId)
    }

    @Test
    fun `allows one year of slack for festival vs wide release`() {
        val result = TitleMatcher.match("Dune", 2020, dune)
        assertEquals(438631, (result as MatchResult.Matched).candidate.tmdbId)
    }

    @Test
    fun `year too far off is not found and suggests candidates`() {
        val result = TitleMatcher.match("Dune", 2000, dune)
        assertInstanceOf(MatchResult.NotFound::class.java, result)
        assertEquals(true, (result as MatchResult.NotFound).candidates.isNotEmpty())
    }

    @Test
    fun `ignores case, accents, punctuation and leading articles`() {
        val candidates = listOf(
            movie(194, "Amélie", 2001, original = "Le Fabuleux Destin d'Amélie Poulain"),
            movie(324857, "Spider-Man: Into the Spider-Verse", 2018),
            movie(9802, "The Rock", 1996),
        )
        assertEquals(194, (TitleMatcher.match("amelie", 2001, candidates) as MatchResult.Matched).candidate.tmdbId)
        assertEquals(194, (TitleMatcher.match("Le fabuleux destin d’Amelie Poulain", 2001, candidates) as MatchResult.Matched).candidate.tmdbId)
        assertEquals(324857, (TitleMatcher.match("Spider Man Into the Spider Verse", 2018, candidates) as MatchResult.Matched).candidate.tmdbId)
        assertEquals(9802, (TitleMatcher.match("Rock", 1996, candidates) as MatchResult.Matched).candidate.tmdbId)
    }

    @Test
    fun `movie and show with the same title and year are ambiguous`() {
        val candidates = listOf(movie(1, "Shogun", 1980), show(2, "Shogun", 1980))
        assertInstanceOf(MatchResult.Ambiguous::class.java, TitleMatcher.match("Shogun", 1980, candidates))
    }

    @Test
    fun `a partial title is not treated as a match`() {
        val result = TitleMatcher.match("Dune Part", 2024, dune)
        assertInstanceOf(MatchResult.NotFound::class.java, result)
    }

    @Test
    fun `ampersand and 'and' are equivalent`() {
        val candidates = listOf(movie(1, "Fast & Furious", 2009))
        assertEquals(1, (TitleMatcher.match("Fast and Furious", 2009, candidates) as MatchResult.Matched).candidate.tmdbId)
    }
}
