package org.ratelog.backnav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.user.User

/** What each page is called in a back link, and the link a title page renders before JS (from the Referer). */
class BackNavPagesTest {

    private val me = User.Id(1)

    private class FakeNames(
        private val lists: Map<Long, BackNavList> = emptyMap(),
        private val users: Map<Long, String> = emptyMap(),
    ) : BackNavNames {
        var lookups = 0
        override fun list(listId: Long): BackNavList? = lists[listId].also { lookups++ }
        override fun username(userId: Long): String? = users[userId].also { lookups++ }
    }

    private val names = FakeNames(
        lists = mapOf(
            7L to BackNavList(me, isPublic = false, name = "Best Disney Movies"),
            8L to BackNavList(User.Id(2), isPublic = true, name = "Marisol's 2010s"),
            9L to BackNavList(User.Id(2), isPublic = false, name = "Secret plans"),
        ),
        users = mapOf(2L to "marisol"),
    )

    private fun link(referer: String?, host: String? = "ratelog.example") =
        BackNavPages.titleLink(referer, host, me, names, "Toy Story 3")

    // --- Page names by path ---

    @Test
    fun `the tab pages and Discover are named by their path`() {
        assertEquals(BackNavLabel.HOME, BackNavPages.fixedLabel("/", null))
        assertEquals(BackNavLabel.HOME, BackNavPages.fixedLabel("/", "q="))
        assertEquals(BackNavLabel.HOME, BackNavPages.fixedLabel("/", "q=+++"))
        assertEquals(BackNavLabel.SEARCH, BackNavPages.fixedLabel("/", "q=toy+story&mediaType=movie"))
        assertEquals(BackNavLabel.DISCOVER, BackNavPages.fixedLabel("/discover", "type=movie&genre=27"))
        assertEquals(BackNavLabel.DISCOVER, BackNavPages.fixedLabel("/browse", null))
        assertEquals(BackNavLabel.RATINGS, BackNavPages.fixedLabel("/movies", "genre=16"))
        assertEquals(BackNavLabel.RATINGS, BackNavPages.fixedLabel("/tvshows", null))
        assertEquals(BackNavLabel.WATCHLIST, BackNavPages.fixedLabel("/watchlist", "tab=seen"))
        assertEquals(BackNavLabel.LISTS, BackNavPages.fixedLabel("/lists", null))
        assertEquals(BackNavLabel.LISTS, BackNavPages.fixedLabel("/lists/", null))
        assertEquals(BackNavLabel.OFFICIAL_LISTS, BackNavPages.fixedLabel("/lists/official", null))
        assertEquals(BackNavLabel.COMMUNITY, BackNavPages.fixedLabel("/community", null))
        assertEquals(BackNavLabel.TAGS, BackNavPages.fixedLabel("/tags", null))
    }

    @Test
    fun `titles, settings and unknown pages have no name`() {
        listOf("/movie/862", "/tv/1396", "/settings/api", "/admin/configuration", "/login", "/list/7", "/discover/row").forEach {
            assertNull(BackNavPages.fixedLabel(it, null), it)
        }
        assertTrue(BackNavPages.isTitle("/movie/862"))
        assertTrue(BackNavPages.isTitle("/tv/1396/"))
        assertFalse(BackNavPages.isTitle("/movie/862/related"))
        assertFalse(BackNavPages.isTitle("/movies"))
    }

    @Test
    fun `person pages take part in the back links like a title`() {
        assertTrue(BackNavPages.isTitle("/person/31"))
        assertFalse(BackNavPages.isTitle("/person/31/credits"))
        val link = BackNavPages.titleLink("http://localhost/person/31", "localhost", me, names, "Forrest Gump")
        assertTrue(link.pending)
    }

    @Test
    fun `a list is named after itself only when the viewer can open it`() {
        assertEquals(BackNavLabel.list("Best Disney Movies"), BackNavPages.label("/list/7", null, me, names))
        assertEquals(BackNavLabel.list("Marisol's 2010s"), BackNavPages.label("/list/8", "show=unseen", me, names))
        assertNull(BackNavPages.label("/list/9", null, me, names), "someone else's private list stays unnamed")
        assertNull(BackNavPages.label("/list/404", null, me, names))
    }

    @Test
    fun `profiles read as yours or as the owner's`() {
        assertEquals(BackNavLabel.OWN_PROFILE, BackNavPages.label("/profile/1", null, me, names))
        assertEquals(BackNavLabel.OWN_PROFILE, BackNavPages.label("/profile", null, me, names))
        assertEquals(BackNavLabel.profile("marisol"), BackNavPages.label("/profile/2", null, me, names))
        assertNull(BackNavPages.label("/profile/99", null, me, names))
    }

    // --- The title page's server-rendered link ---

    @Test
    fun `opened from Discover, the link goes back to the same filtered URL`() {
        val back = link("https://ratelog.example/discover?type=movie&genre=27&sort=popular")

        assertEquals("/discover?type=movie&genre=27&sort=popular", back.href)
        assertEquals(BackNavLabel.DISCOVER, back.label)
        assertEquals("Toy Story 3", back.titleName)
        assertFalse(back.pending)
    }

    @Test
    fun `opened from search results, a list or a profile`() {
        assertEquals(BackNavLabel.SEARCH, link("http://ratelog.example/?q=toy").label)
        assertEquals("/?q=toy", link("http://ratelog.example/?q=toy").href)
        assertEquals(BackNavLabel.list("Best Disney Movies"), link("https://ratelog.example/list/7?show=unseen").label)
        assertEquals("/list/7?show=unseen", link("https://ratelog.example/list/7?show=unseen").href)
        assertEquals(BackNavLabel.profile("marisol"), link("https://ratelog.example/profile/2").label)
        assertEquals(BackNavLabel.HOME, link("https://RATELOG.example/").label, "host names are case-insensitive")
    }

    @Test
    fun `opened directly, from another site or from a page with no name, the link is Home`() {
        listOf(
            null,
            "",
            "https://www.google.com/",
            "https://evil.example/discover?genre=27",
            "not a url",
            "javascript:alert(1)",
            "https://ratelog.example/settings/api",
            "https://ratelog.example/list/9",
            "https://ratelog.example//evil.example/x",
        ).forEach { referer ->
            val back = link(referer)
            assertEquals("/", back.href, "$referer")
            assertEquals(BackNavLabel.HOME, back.label, "$referer")
            assertFalse(back.pending, "$referer")
        }
    }

    @Test
    fun `opened from another title, the server can't know the origin so the link waits for the script`() {
        val back = link("https://ratelog.example/movie/862")

        assertEquals("/", back.href)
        assertEquals(BackNavLabel.HOME, back.label)
        assertTrue(back.pending)
        assertTrue(link("https://ratelog.example/tv/1396").pending)
    }

    @Test
    fun `no names are looked up for path-only pages or other sites`() {
        val counting = FakeNames()
        BackNavPages.titleLink("https://ratelog.example/discover", "ratelog.example", me, counting, null)
        BackNavPages.titleLink("https://elsewhere.example/list/7", "ratelog.example", me, counting, null)
        assertEquals(0, counting.lookups)
    }

    @Test
    fun `only the Referer's path and query are kept`() {
        assertEquals("/discover?genre=27", BackNavPages.sameHostPath("https://ratelog.example/discover?genre=27#row", "ratelog.example"))
        assertEquals("/lists", BackNavPages.sameHostPath("http://ratelog.example:8190/lists", "ratelog.example"))
        assertNull(BackNavPages.sameHostPath("ftp://ratelog.example/lists", "ratelog.example"))
        assertNull(BackNavPages.sameHostPath("/lists", "ratelog.example"), "a relative Referer is not a URL")
        assertNull(BackNavPages.sameHostPath("https://ratelog.example/lists", null))
    }
}
