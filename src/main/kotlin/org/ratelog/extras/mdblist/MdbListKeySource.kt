package org.ratelog.extras.mdblist

import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfigRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Where [MdbListClient] gets its API key. Blank = not configured. */
fun interface MdbListKeySource {
    fun current(): String
}

/**
 * Fork (user request, 2026-11-23: "make it the same where I paste it in"): the key saved on Admin → Configuration wins, so the
 * admin can paste or replace it without touching the host; the `MDBLIST_API_KEY` environment variable is the fallback. Read per
 * call (one indexed single-row lookup), so a new key applies at once.
 */
@Component
class ConfigMdbListKeySource(
    private val generalConfigRepository: GeneralConfigRepository,
    @Value("\${MDBLIST_API_KEY:}") envKey: String,
) : MdbListKeySource {
    private val envKey = envKey.trim()

    override fun current(): String =
        generalConfigRepository.findByKey(ConfigKey.MDBLIST_API_KEY)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: envKey

    /** True when only the environment variable provides the key (shown as a hint on the admin page). */
    fun fromEnvironmentOnly(): Boolean =
        envKey.isNotEmpty() && generalConfigRepository.findByKey(ConfigKey.MDBLIST_API_KEY)?.value.isNullOrBlank()
}
