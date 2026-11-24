package org.ratelog.stats

import arrow.core.right
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.MediaType
import org.ratelog.TmdbId
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.config.istmdbconfigured.IsTmdbConfiguredHandler
import org.ratelog.extras.ExtrasTaskRunner
import org.ratelog.extras.TitleExtrasRefresher
import org.ratelog.extras.TmdbExtras
import org.ratelog.extras.TmdbExtrasClient
import org.ratelog.extras.mdblist.MdbListClient
import org.ratelog.test.InMemoryGeneralConfigRepository
import org.ratelog.test.InMemoryTitleExtrasRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StatsRuntimeFillerTest {
    private val config = InMemoryGeneralConfigRepository()
    private val extras = InMemoryTitleExtrasRepository()
    private val tmdb: TmdbExtrasClient = mock()
    private val mdbList: MdbListClient = mock()
    private val refresher = TitleExtrasRefresher(extras, tmdb, mdbList, Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC))
    // Runs each lookup right away, so the test sees the cache it fills.
    private val filler = StatsRuntimeFiller(ExtrasTaskRunner { it.run() }, refresher, IsTmdbConfiguredHandler(config))

    private fun configure() = config.save(GeneralConfig(null, ConfigKey.TMDB_API_KEY, "key", 1L))

    @Test
    fun `without a TMDB key nothing is looked up`() {
        assertEquals(0, filler.fill(listOf(603)))
        verify(tmdb, never()).fetch(any(), any())
    }

    @Test
    fun `looks up the TMDB half only (no MDBList call) and fills the runtime into the cache`() {
        configure()
        whenever(tmdb.fetch(MediaType.movie, TmdbId(603))).thenReturn(TmdbExtras("The Matrix", null, 8.2, 100, 136, null, null, "tt0133093").right())

        assertEquals(1, filler.fill(listOf(603, 603)))

        assertEquals(136, extras.find(MediaType.movie, TmdbId(603))?.tmdb?.runtimeMinutes)
        verify(tmdb, times(1)).fetch(MediaType.movie, TmdbId(603))
        verify(mdbList, never()).fetch(any(), any())
        assertNull(extras.find(MediaType.movie, TmdbId(603))?.scoresNextRefreshAt)
    }

    @Test
    fun `at most one batch per view`() {
        configure()
        whenever(tmdb.fetch(any(), any())).thenReturn(TmdbExtras(null, null, null, null, 100, null, null, null).right())

        assertEquals(StatsRuntimeFiller.BATCH, filler.fill((1..100).toList()))
        verify(tmdb, times(StatsRuntimeFiller.BATCH)).fetch(any(), any())
    }
}
