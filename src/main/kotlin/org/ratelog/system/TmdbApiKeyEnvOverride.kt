package org.ratelog.system

import org.ratelog.config.ConfigKey
import org.ratelog.config.GeneralConfig
import org.ratelog.config.GeneralConfigRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Upstream's TmdbApiKeySeeder only copies TMDB_API_KEY into the database on the very first start.
 * In this fork the env var wins whenever it is set, so fixing the key in Railway is enough.
 * Obvious placeholders (`<...>`, `REPLACE_ME...`, text with spaces) are refused with a warning.
 * To manage the key from Admin > Configuration instead, remove the env var.
 */
@Component
class TmdbApiKeyEnvOverride(
    private val generalConfigRepository: GeneralConfigRepository,
    @Value("\${TMDB_API_KEY:}") private val envApiKey: String,
) {
    private val logger = LoggerFactory.getLogger(TmdbApiKeyEnvOverride::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun apply() {
        val key = clean(envApiKey) ?: return
        if (looksLikePlaceholder(key)) {
            logger.warn("TMDB_API_KEY looks like a placeholder, not a real key; ignoring it. Set the real 32-character TMDB v3 API Key.")
            return
        }
        val existing = generalConfigRepository.findByKey(ConfigKey.TMDB_API_KEY)
        if (existing?.value == key) return

        generalConfigRepository.save(
            GeneralConfig(
                id = existing?.id,
                key = ConfigKey.TMDB_API_KEY,
                value = key,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
        )
        logger.info("TMDB API key updated from the TMDB_API_KEY env var")
    }

    companion object {
        /** Trims whitespace and one pair of surrounding quotes; null when nothing is left. */
        fun clean(raw: String): String? {
            var v = raw.trim()
            if (v.length >= 2 && (v.first() == '"' && v.last() == '"' || v.first() == '\'' && v.last() == '\'')) {
                v = v.substring(1, v.length - 1).trim()
            }
            return v.ifEmpty { null }
        }

        fun looksLikePlaceholder(key: String): Boolean =
            key.contains('<') || key.contains('>') || key.any(Char::isWhitespace) ||
                key.contains("REPLACE_ME", ignoreCase = true)
    }
}
