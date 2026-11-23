package org.ratelog.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.ratelog.apitoken.ApiTokenRepository
import org.ratelog.apitoken.ApiTokens
import org.ratelog.security.ClientIpResolver
import org.ratelog.security.RateLimitFilter
import org.ratelog.security.RequestRateLimiter
import org.ratelog.user.AppUserDetails
import org.ratelog.user.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Separate, stateless security chain for the JSON API under /api/v1/.
 * It runs before the app's form-login chain (which has no matcher and default order),
 * so the browser UI is untouched. Auth is `Authorization: Bearer <token>` or `X-API-Key: <token>`.
 */
@Configuration
class ApiSecurityConfig(
    private val apiTokenRepository: ApiTokenRepository,
    private val userRepository: UserRepository,
    private val rateLimiter: RequestRateLimiter,
    private val messageSource: MessageSource,
    @Value("\${ratelog.rate-limit.enabled:true}") private val rateLimitEnabled: Boolean,
    @Value("\${ratelog.security.client-ip-header:X-Real-IP}") private val clientIpHeader: String,
) {
    @Bean
    @Order(1)
    fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        // Fork: per-token "too many requests" limit (see security/RateLimitFilter).
        if (rateLimitEnabled) {
            http.addFilterAfter(RateLimitFilter(rateLimiter, ClientIpResolver(clientIpHeader), messageSource), AnonymousAuthenticationFilter::class.java)
        }
        http
            .securityMatcher("/api/v1/**")
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .exceptionHandling { eh ->
                eh.authenticationEntryPoint { _, response, _ ->
                    writeJsonError(response, HttpStatus.UNAUTHORIZED, "unauthorized", "Missing or invalid API token")
                }
                eh.accessDeniedHandler { _, response, _ ->
                    writeJsonError(response, HttpStatus.FORBIDDEN, "forbidden", "Not allowed")
                }
            }
            // Not a Spring bean on purpose: a @Component filter would also be registered globally.
            .addFilterBefore(ApiTokenAuthenticationFilter(apiTokenRepository, userRepository), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}

class ApiTokenAuthenticationFilter(
    private val apiTokenRepository: ApiTokenRepository,
    private val userRepository: UserRepository,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val raw = request.getHeader("Authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()
            ?: request.getHeader("X-API-Key")?.trim()

        if (!raw.isNullOrBlank()) {
            val token = apiTokenRepository.findByTokenHash(ApiTokens.hash(raw))
            val user = token?.let { userRepository.findById(it.userId) }
            if (token != null && user != null) {
                val principal = AppUserDetails(
                    id = user.id!!,
                    username = user.username.value,
                    email = user.email.value,
                    password = user.passwordHash,
                    lang = user.lang,
                    metadataLang = user.metadataLang,
                    role = user.role,
                )
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
                request.setAttribute(RateLimitFilter.API_TOKEN_ID_ATTRIBUTE, token.id!!.value)
                touchIfStale(token.id!!, token.lastUsedAtEpochMs)
            }
        }
        chain.doFilter(request, response)
    }

    /** Record usage at most once a minute to avoid a DB write on every request. */
    private fun touchIfStale(id: org.ratelog.apitoken.ApiToken.Id, lastUsed: Long?) {
        val now = System.currentTimeMillis()
        if (lastUsed == null || now - lastUsed > 60_000) {
            runCatching { apiTokenRepository.touch(id, now) }
        }
    }
}

internal fun writeJsonError(response: HttpServletResponse, status: HttpStatus, code: String, message: String) {
    response.status = status.value()
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.characterEncoding = "UTF-8"
    val escaped = message.replace("\\", "\\\\").replace("\"", "\\\"")
    response.writer.write("""{"error":"$code","message":"$escaped"}""")
}
