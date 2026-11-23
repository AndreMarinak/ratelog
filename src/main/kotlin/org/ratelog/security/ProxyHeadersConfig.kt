package org.ratelog.security

import jakarta.servlet.DispatcherType
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.boot.web.servlet.server.CookieSameSiteSupplier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.filter.ForwardedHeaderFilter
import java.util.Collections
import java.util.Enumeration

/**
 * Proxy and cookie settings for running behind Railway's TLS edge.
 *
 * - Replaces Spring Boot's `ForwardedHeaderFilter` (`server.forward-headers-strategy: framework`) with one that only
 *   trusts the headers Railway's edge sets: `X-Forwarded-Proto`, `X-Forwarded-Host` and `X-Forwarded-For`. The others
 *   (`Forwarded`, `X-Forwarded-Prefix`, `X-Forwarded-Port`, `X-Forwarded-Ssl`) are dropped, since only a client could
 *   have sent them. Redirects are relative (`Location: /path`), so a forwarded host never ends up in a redirect.
 * - The remember-me cookie gets `SameSite=Lax` like the session cookie (that one is set in application.yml).
 */
@Configuration
class ProxyHeadersConfig {

    @Bean
    @ConditionalOnProperty(value = ["server.forward-headers-strategy"], havingValue = "framework")
    fun trustedForwardedHeaderFilter(): FilterRegistrationBean<ForwardedHeaderFilter> =
        FilterRegistrationBean<ForwardedHeaderFilter>(TrustedForwardedHeaderFilter()).apply {
            setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR)
            order = Ordered.HIGHEST_PRECEDENCE
        }

    @Bean
    fun rememberMeCookieSameSite(): CookieSameSiteSupplier = CookieSameSiteSupplier.ofLax().whenHasName(REMEMBER_ME_COOKIE)

    companion object {
        const val REMEMBER_ME_COOKIE = "remember-me"
    }
}

class TrustedForwardedHeaderFilter : ForwardedHeaderFilter() {
    init {
        setRelativeRedirects(true)
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        super.doFilterInternal(UntrustedForwardedHeadersRemoved(request), response, filterChain)
    }

    companion object {
        val UNTRUSTED: Set<String> = setOf("forwarded", "x-forwarded-prefix", "x-forwarded-port", "x-forwarded-ssl")
    }
}

private class UntrustedForwardedHeadersRemoved(request: HttpServletRequest) : HttpServletRequestWrapper(request) {
    private fun untrusted(name: String) = name.lowercase() in TrustedForwardedHeaderFilter.UNTRUSTED

    override fun getHeader(name: String): String? = if (untrusted(name)) null else super.getHeader(name)

    override fun getHeaders(name: String): Enumeration<String> =
        if (untrusted(name)) Collections.emptyEnumeration() else super.getHeaders(name)

    override fun getHeaderNames(): Enumeration<String> =
        Collections.enumeration(Collections.list(super.getHeaderNames()).filterNot(::untrusted))
}
