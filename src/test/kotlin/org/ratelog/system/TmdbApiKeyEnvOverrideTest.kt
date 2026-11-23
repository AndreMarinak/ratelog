package org.ratelog.system

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.test.InMemoryGeneralConfigRepository

class TmdbApiKeyEnvOverrideTest {

    private val repo = InMemoryGeneralConfigRepository()
    private val realKey = "0123456789abcdef0123456789abcdef"

    private fun stored() = repo.findByKey(ConfigKey.TMDB_API_KEY)?.value

    private fun storeKey(value: String) =
        repo.save(GeneralConfig(id = null, key = ConfigKey.TMDB_API_KEY, value = value, updatedAtEpochMs = 0L))

    @Test
    fun `env key replaces a different stored value`() {
        storeKey("<your TMDB \"API Key\">")
        TmdbApiKeyEnvOverride(repo, realKey).apply()
        assertEquals(realKey, stored())
    }

    @Test
    fun `env key is saved when nothing is stored`() {
        TmdbApiKeyEnvOverride(repo, realKey).apply()
        assertEquals(realKey, stored())
    }

    @Test
    fun `surrounding whitespace and quotes are stripped`() {
        TmdbApiKeyEnvOverride(repo, "  \"$realKey\"  ").apply()
        assertEquals(realKey, stored())
    }

    @Test
    fun `blank env leaves the stored value alone`() {
        storeKey("set-in-admin-ui")
        TmdbApiKeyEnvOverride(repo, "   ").apply()
        assertEquals("set-in-admin-ui", stored())
    }

    @Test
    fun `placeholders are refused`() {
        storeKey("set-in-admin-ui")
        listOf("<your TMDB \"API Key\">", "REPLACE_ME_with_the_32_character_TMDB_API_Key", "my api key").forEach {
            TmdbApiKeyEnvOverride(repo, it).apply()
        }
        assertEquals("set-in-admin-ui", stored())
    }

    @Test
    fun `placeholder with nothing stored saves nothing`() {
        TmdbApiKeyEnvOverride(repo, "<key>").apply()
        assertNull(stored())
    }
}
