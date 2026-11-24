package org.ratelog.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.ratelog.Role
import org.ratelog.config.getgeneralconfig.GetGeneralConfigHandler
import org.ratelog.config.updategeneralconfig.UpdateGeneralConfigHandler
import org.ratelog.test.InMemoryGeneralConfigRepository
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.UserFactory
import org.ratelog.user.listusers.ListUsersHandler
import org.springframework.ui.ExtendedModelMap
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap

/** Fork: the admin page never sends stored secrets to the browser, and its forms only set the TMDB and MDBList keys. */
class ConfigurationControllerTest {

    private val configs = InMemoryGeneralConfigRepository()
    private val users = InMemoryUserRepository()
    private val controller = ConfigurationController(ListUsersHandler(users), GetGeneralConfigHandler(configs), UpdateGeneralConfigHandler(configs))
    private val admin = users.save(UserFactory.aUser(role = Role.ADMIN))
    private val tmdbKey = "0123456789abcdef0123456789abcdef"

    private fun store(key: ConfigKey, value: String) = configs.save(GeneralConfig(null, key, value, 0))

    private fun page(): ExtendedModelMap = ExtendedModelMap().also { controller.dashboard(admin, it, RedirectAttributesModelMap()) }

    @Test
    fun `the page shows only the last 4 characters of the TMDB key and no other secret`() {
        store(ConfigKey.TMDB_API_KEY, tmdbKey)
        store(ConfigKey.REMEMBER_ME_KEY, "remember-secret-value")

        val model = page()

        assertEquals(true, model["tmdbApiKeyConfigured"])
        assertEquals("…cdef", model["tmdbApiKeyHint"])
        val everything = model.values.joinToString(" ") { "$it" }
        assertFalse(everything.contains(tmdbKey))
        assertFalse(everything.contains("remember-secret-value"))
    }

    @Test
    fun `a short or missing key shows no characters at all`() {
        assertNull(ConfigurationController.maskedHint("abc1234"))
        assertNull(ConfigurationController.maskedHint(""))
        assertEquals(false, page()["tmdbApiKeyConfigured"])
    }

    @Test
    fun `typing a new key replaces the stored one`() {
        store(ConfigKey.TMDB_API_KEY, tmdbKey)
        val flash = RedirectAttributesModelMap()

        controller.updateGeneral(admin, "tmdb_api_key", "  fedcba9876543210fedcba9876543210 ", flash)

        assertEquals("config.general.success.saved", flash.flashAttributes["success"])
        assertEquals("fedcba9876543210fedcba9876543210", configs.findByKey(ConfigKey.TMDB_API_KEY)!!.value)
    }

    @Test
    fun `saving with the field left empty keeps the current key`() {
        store(ConfigKey.TMDB_API_KEY, tmdbKey)
        val flash = RedirectAttributesModelMap()

        controller.updateGeneral(admin, "tmdb_api_key", "", flash)

        assertEquals("security.config.key.unchanged", flash.flashAttributes["success"])
        assertEquals(tmdbKey, configs.findByKey(ConfigKey.TMDB_API_KEY)!!.value)
    }

    @Test
    fun `the remember-me signing key cannot be written through the form`() {
        store(ConfigKey.REMEMBER_ME_KEY, "original")
        val flash = RedirectAttributesModelMap()

        controller.updateGeneral(admin, "remember_me_key", "changed-value", flash)

        assertEquals("config.general.error.invalid.key", flash.flashAttributes["error"])
        assertEquals("original", configs.findByKey(ConfigKey.REMEMBER_ME_KEY)!!.value)
    }

    @Test
    fun `the MDBList key is pasted the same way and only its last 4 characters come back`() {
        val mdbKey = "abcdefghijklmnopqrstuvwx1234"
        val flash = RedirectAttributesModelMap()

        controller.updateGeneral(admin, "mdblist_api_key", " $mdbKey ", flash)

        assertEquals("config.general.success.saved", flash.flashAttributes["success"])
        assertEquals(mdbKey, configs.findByKey(ConfigKey.MDBLIST_API_KEY)!!.value)
        val model = page()
        assertEquals(true, model["mdblistApiKeyConfigured"])
        assertEquals("…1234", model["mdblistApiKeyHint"])
        assertEquals(false, model["mdblistApiKeyFromEnv"])
        assertFalse(model.values.joinToString(" ") { "$it" }.contains(mdbKey))
    }

    @Test
    fun `a key that only comes from the environment is mentioned but never shown`() {
        val envKey = "env-mdblist-key-value-9999"
        val withEnv = ConfigurationController(ListUsersHandler(users), GetGeneralConfigHandler(configs), UpdateGeneralConfigHandler(configs), envKey)
        val model = ExtendedModelMap().also { withEnv.dashboard(admin, it, RedirectAttributesModelMap()) }

        assertEquals(false, model["mdblistApiKeyConfigured"])
        assertEquals(true, model["mdblistApiKeyFromEnv"])
        assertFalse(model.values.joinToString(" ") { "$it" }.contains(envKey))
    }

    @Test
    fun `managers and users cannot change settings`() {
        listOf(Role.USER, Role.MANAGER).forEach { role ->
            val flash = RedirectAttributesModelMap()
            controller.updateGeneral(UserFactory.aUser(role = role), "tmdb_api_key", tmdbKey, flash)
            assertEquals("admin.error.forbidden", flash.flashAttributes["error"])
        }
        assertNull(configs.findByKey(ConfigKey.TMDB_API_KEY))
    }
}
