package org.ratelog.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.user.AppUserDetails
import org.ratelog.user.User
import org.ratelog.user.UserRepository
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Re-checks the signed-in user against the database on every browser request (one primary-key lookup), so account
 * changes apply to open sessions at once instead of at the next login:
 * - the user was deleted, or their password changed since this session signed in: the session ends (it falls through
 *   as anonymous, so protected pages redirect to the login page);
 * - role, username, email or languages changed: the session's details and authorities are refreshed in place.
 *
 * Runs after the remember-me filter, so cookie logins follow the same rules, and before authorization, so the admin URL rule
 * and every handler's `currentUser.role` check see the current role. Not a Spring bean on purpose (a @Component filter
 * would also be registered outside the security chain).
 */
class SessionRevalidationFilter(
    private val userRepository: UserRepository,
    private val securityContextRepository: SecurityContextRepository,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = isStaticAsset(pathOf(request))

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val authentication = SecurityContextHolder.getContext().authentication
        val principal = authentication?.principal as? AppUserDetails
        if (principal != null) {
            val current = userRepository.findById(principal.id)
            when {
                current == null || current.passwordHash != principal.password -> endSession(request)
                !principal.matches(current) -> refresh(current, authentication.credentials, request, response)
            }
        }
        chain.doFilter(request, response)
    }

    private fun endSession(request: HttpServletRequest) {
        SecurityContextHolder.clearContext()
        request.getSession(false)?.invalidate()
    }

    private fun refresh(user: User, credentials: Any?, request: HttpServletRequest, response: HttpServletResponse) {
        val details = AppUserDetails.from(user)
        val context = SecurityContextHolder.createEmptyContext().apply {
            authentication = UsernamePasswordAuthenticationToken(details, credentials, details.authorities)
        }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)
    }

    private fun AppUserDetails.matches(user: User): Boolean =
        username == user.username.value &&
            email == user.email.value &&
            role == user.role &&
            lang == user.lang &&
            metadataLang == user.metadataLang

    companion object {
        private val STATIC_FILE = Regex("^/[^/]+\\.(css|js|webmanifest|png|svg|ico|woff2)$")

        /** The decoded, normalized path (servlet path + path info), as Spring Security's matchers see it. */
        fun pathOf(request: HttpServletRequest): String =
            (request.servletPath.orEmpty() + request.pathInfo.orEmpty())
                .ifEmpty { request.requestURI.orEmpty().removePrefix(request.contextPath.orEmpty()) }

        /** Public files that never use the signed-in user; skipping them saves a lookup per asset. Never matches `/admin/`. */
        fun isStaticAsset(path: String): Boolean =
            STATIC_FILE.matches(path) || path.startsWith("/img/") || path.startsWith("/fonts/")
    }
}
