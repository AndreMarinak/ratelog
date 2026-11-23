package org.ratelog.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.authentication.AuthenticationFailureHandler
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler
import org.springframework.web.filter.OncePerRequestFilter

/** The client address for throttling: the proxy's client-IP header (Railway's edge sets `X-Real-IP`), else the socket peer. */
class ClientIpResolver(private val header: String = DEFAULT_HEADER) {
    fun resolve(request: HttpServletRequest): String? =
        header.takeIf { it.isNotBlank() }
            ?.let { request.getHeader(it) }
            ?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr

    companion object {
        const val DEFAULT_HEADER = "X-Real-IP"
    }
}

/**
 * In front of the form login (`POST /login/process`): a locked username or IP gets the "too many attempts" page
 * without the password being checked. Not a Spring bean on purpose (it belongs to the browser security chain only).
 */
class LoginThrottleFilter(
    private val throttle: LoginThrottle,
    private val clientIp: ClientIpResolver,
    private val loginProcessingUrl: String,
    private val lockedUrl: String,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method != "POST" || SessionRevalidationFilter.pathOf(request) != loginProcessingUrl

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (throttle.isLocked(request.getParameter(USERNAME_PARAMETER), clientIp.resolve(request))) {
            response.sendRedirect(request.contextPath + lockedUrl)
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val USERNAME_PARAMETER = "username"
    }
}

/** Counts a failed sign-in, then shows the usual error (or the lock notice if this failure triggered a lock). */
class ThrottlingFailureHandler(
    private val throttle: LoginThrottle,
    private val clientIp: ClientIpResolver,
    failureUrl: String,
    private val lockedUrl: String,
) : AuthenticationFailureHandler {
    private val failure = SimpleUrlAuthenticationFailureHandler(failureUrl)
    private val locked = SimpleUrlAuthenticationFailureHandler(lockedUrl)

    override fun onAuthenticationFailure(request: HttpServletRequest, response: HttpServletResponse, exception: AuthenticationException) {
        val username = request.getParameter(LoginThrottleFilter.USERNAME_PARAMETER)
        val ip = clientIp.resolve(request)
        throttle.recordFailure(username, ip)
        (if (throttle.isLocked(username, ip)) locked else failure).onAuthenticationFailure(request, response, exception)
    }
}

/** Clears the username's failure count after a correct password, then goes to [targetUrl] as before. */
class ThrottlingSuccessHandler(
    private val throttle: LoginThrottle,
    targetUrl: String,
) : AuthenticationSuccessHandler {
    private val delegate = SimpleUrlAuthenticationSuccessHandler(targetUrl).apply { setAlwaysUseDefaultTargetUrl(true) }

    override fun onAuthenticationSuccess(request: HttpServletRequest, response: HttpServletResponse, authentication: Authentication) {
        throttle.recordSuccess(request.getParameter(LoginThrottleFilter.USERNAME_PARAMETER))
        delegate.onAuthenticationSuccess(request, response, authentication)
    }
}
