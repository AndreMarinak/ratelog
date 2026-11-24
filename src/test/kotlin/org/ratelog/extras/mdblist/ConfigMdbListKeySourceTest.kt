package org.ratelog.extras.mdblist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.test.InMemoryGeneralConfigRepository

/** The key pasted on Admin → Configuration wins; the MDBLIST_API_KEY variable is the fallback. */
class ConfigMdbListKeySourceTest {

    private val configs = InMemoryGeneralConfigRepository()

    private fun save(value: String) {
        val existing = configs.findByKey(ConfigKey.MDBLIST_API_KEY)
        configs.save(GeneralConfig(existing?.id, ConfigKey.MDBLIST_API_KEY, value, 0))
    }

    @Test
    fun `nothing saved and no variable means not configured`() {
        val source = ConfigMdbListKeySource(configs, "")
        assertEquals("", source.current())
        assertFalse(source.fromEnvironmentOnly())
    }

    @Test
    fun `the variable is used while nothing is saved`() {
        val source = ConfigMdbListKeySource(configs, " env-key ")
        assertEquals("env-key", source.current())
        assertTrue(source.fromEnvironmentOnly())
    }

    @Test
    fun `a saved key wins over the variable and applies at once`() {
        val source = ConfigMdbListKeySource(configs, "env-key")
        save(" pasted-key ")
        assertEquals("pasted-key", source.current())
        assertFalse(source.fromEnvironmentOnly())
        save("replaced-key")
        assertEquals("replaced-key", source.current())
    }
}
