package org.ratelog.customlist.order.sort

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.getlistdetail.ListDetailItem

class ListSortTest {

    private fun item(id: Long, position: Int, title: String, year: Int?) = ListDetailItem(
        id = CustomListItem.Id(id), mediaId = id, tmdbId = id.toInt(), mediaType = "movie", position = position,
        title = title, posterPath = null, year = year, score = null, isFollowed = false,
    )

    // List order: Heat, The Godfather, alien, Zodiac, Amélie (ids 1, 2, 3 by insertion; 4 and 5 added later and moved up).
    private val heat = item(1, 1, "Heat", 1995)
    private val godfather = item(2, 2, "The Godfather", 1972)
    private val alien = item(3, 3, "alien", 1979)
    private val zodiac = item(5, 4, "Zodiac", null)
    private val amelie = item(4, 5, "Amélie", 2001)
    private val shuffled = listOf(zodiac, heat, amelie, alien, godfather)

    private fun titles(sort: ListSort, scores: Map<Long, Double> = emptyMap()) = sort.arrange(shuffled, scores).map { it.title }

    @Test
    fun `list order is position then id, and reverse flips it`() {
        assertEquals(listOf("Heat", "The Godfather", "alien", "Zodiac", "Amélie"), titles(ListSort.LIST))
        assertEquals(listOf("Amélie", "Zodiac", "alien", "The Godfather", "Heat"), titles(ListSort.REVERSE))
        val tie = item(9, 1, "Tie", 2000)
        assertEquals(listOf("Heat", "Tie"), ListSort.LIST.arrange(listOf(tie, heat)).map { it.title })
    }

    @Test
    fun `title ignores case, accents and a leading article`() {
        assertEquals(listOf("alien", "Amélie", "The Godfather", "Heat", "Zodiac"), titles(ListSort.TITLE))
    }

    @Test
    fun `release year newest and oldest put unknown years last`() {
        assertEquals(listOf("Amélie", "Heat", "alien", "The Godfather", "Zodiac"), titles(ListSort.NEWEST))
        assertEquals(listOf("The Godfather", "alien", "Heat", "Amélie", "Zodiac"), titles(ListSort.OLDEST))
    }

    @Test
    fun `your rating puts the highest first, unrated last in list order`() {
        assertEquals(listOf("alien", "Heat", "The Godfather", "Zodiac", "Amélie"), titles(ListSort.RATING, mapOf(3L to 9.5, 1L to 8.0)))
    }

    @Test
    fun `recently added is newest item first, whatever its place`() {
        assertEquals(listOf("Zodiac", "Amélie", "alien", "The Godfather", "Heat"), titles(ListSort.ADDED))
    }

    @Test
    fun `parse knows the params and a few aliases, anything else is list order`() {
        ListSort.entries.forEach { assertEquals(it, ListSort.parse(it.param)) }
        assertEquals(ListSort.TITLE, ListSort.parse(" Name "))
        assertEquals(ListSort.NEWEST, ListSort.parse("release-desc"))
        assertEquals(ListSort.RATING, ListSort.parse("your_rating"))
        assertEquals(ListSort.ADDED, ListSort.parse("recent"))
        assertEquals(ListSort.LIST, ListSort.parse("popularity"))
        assertEquals(ListSort.LIST, ListSort.parse(""))
        assertEquals(ListSort.LIST, ListSort.parse(null))
    }
}
