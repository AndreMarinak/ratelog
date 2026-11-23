package org.ratelog.system

import org.ratelog.Username
import org.ratelog.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component

/**
 * Migration V20 seeds an `admin` / `admin` superadmin. On a public deployment that is an open door,
 * so if `ADMIN_PASSWORD` is set and the admin still has the default password, replace it at startup.
 * A password changed later in the UI is never overwritten (only the default is replaced).
 */
@Component
class AdminPasswordBootstrap(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    @Value("\${ADMIN_PASSWORD:}") private val adminPassword: String,
) {
    private val logger = LoggerFactory.getLogger(AdminPasswordBootstrap::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun run() {
        val admin = userRepository.findByUsername(Username(ADMIN_USERNAME)) ?: return
        if (!passwordEncoder.matches(DEFAULT_PASSWORD, admin.passwordHash)) return

        if (adminPassword.isBlank()) {
            logger.warn("The '$ADMIN_USERNAME' account still uses the default password. Set ADMIN_PASSWORD or change it in the UI.")
            return
        }
        if (adminPassword.length < MIN_LENGTH) {
            logger.warn("ADMIN_PASSWORD is shorter than $MIN_LENGTH characters; ignoring it. The default admin password is still active.")
            return
        }
        userRepository.updateCredentials(admin.id!!, admin.username, admin.email, passwordEncoder.encode(adminPassword))
        logger.info("Default '$ADMIN_USERNAME' password replaced from ADMIN_PASSWORD")
    }

    companion object {
        const val ADMIN_USERNAME = "admin"
        const val DEFAULT_PASSWORD = "admin"
        const val MIN_LENGTH = 12
    }
}
