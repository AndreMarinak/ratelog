package org.ratelog.watchlist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WatchStatusTest {

    @Test
    fun `parses api names, enum names and separators`() {
        assertEquals(WatchStatus.WANT_TO_WATCH, WatchStatus.parse("want_to_watch"))
        assertEquals(WatchStatus.WANT_TO_WATCH, WatchStatus.parse("WANT_TO_WATCH"))
        assertEquals(WatchStatus.WANT_TO_WATCH, WatchStatus.parse(" Want to watch "))
        assertEquals(WatchStatus.WANT_TO_WATCH, WatchStatus.parse("want-to-watch"))
        assertEquals(WatchStatus.WATCHING, WatchStatus.parse("watching"))
        assertEquals(WatchStatus.WATCHED, WatchStatus.parse("Watched"))
        assertEquals(WatchStatus.DROPPED, WatchStatus.parse("dropped"))
    }

    @Test
    fun `parses aliases`() {
        assertEquals(WatchStatus.WANT_TO_WATCH, WatchStatus.parse("watchlist"))
        assertEquals(WatchStatus.WANT_TO_WATCH, WatchStatus.parse("plan_to_watch"))
        assertEquals(WatchStatus.WATCHED, WatchStatus.parse("seen"))
    }

    @Test
    fun `unknown or empty is null`() {
        assertNull(WatchStatus.parse("liked"))
        assertNull(WatchStatus.parse(""))
        assertNull(WatchStatus.parse(null))
    }
}
