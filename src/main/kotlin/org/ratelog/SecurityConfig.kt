package org.ratelog

import org.ratelog.security.ClientIpResolver
import org.ratelog.security.LoginThrottle
import org.ratelog.security.LoginThrottleFilter
import org.ratelog.security.RateLimitFilter
import org.ratelog.security.RequestRateLimiter
import org.ratelog.security.SecurityHeaders
import org.ratelog.security.SessionRevalidationFilter
import org.ratelog.security.ThrottlingFailureHandler
import org.ratelog.security.ThrottlingSuccessHandler
import org.ratelog.system.RememberMeKeyProvider
import org.ratelog.user.UserRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.MessageSource
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter
import org.springframework.security.web.context.DelegatingSecurityContextRepository
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val rememberMeKeyProvider: RememberMeKeyProvider,
    private val userRepository: UserRepository,
    // No Kotlin default values here: a @Configuration class is subclassed by CGLIB, and the extra constructor Kotlin
    // generates for defaults stops Spring from finding the primary one.
    @Value("\${ratelog.security.client-ip-header:X-Real-IP}") private val clientIpHeader: String,
    @Value("\${ratelog.security.csp-report-only:false}") private val cspReportOnly: Boolean,
    @Value("\${ratelog.rate-limit.enabled:true}") private val rateLimitEnabled: Boolean,
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return BCryptPasswordEncoder()
    }

    @Bean
    fun authenticationManager(authenticationConfiguration: AuthenticationConfiguration): AuthenticationManager {
        return authenticationConfiguration.authenticationManager
    }

    @Bean
    fun loginThrottle(): LoginThrottle = LoginThrottle()

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        loginThrottle: LoginThrottle,
        rateLimiter: RequestRateLimiter,
        messageSource: MessageSource,
    ): SecurityFilterChain {
        // Fork: the same repository the chain uses, so the revalidation filter can store refreshed details.
        val securityContextRepository = DelegatingSecurityContextRepository(
            RequestAttributeSecurityContextRepository(),
            HttpSessionSecurityContextRepository(),
        )
        val clientIp = ClientIpResolver(clientIpHeader)
        http.securityContext { it.securityContextRepository(securityContextRepository) }

        http
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/login", "/styles.css", "/lucide.min.js", "/img/**", "/manifest.webmanifest").permitAll()
                    .requestMatchers("/*.css", "/*.js").permitAll()
                    .requestMatchers("/api/health").permitAll()
                    .requestMatchers("/admin/**").hasAnyRole("ADMIN", "SUPERADMIN")
                    // Fork: managers (and admins) can add plain USER accounts.
                    .requestMatchers("/accounts/**").hasAnyRole("MANAGER", "ADMIN", "SUPERADMIN")
                    .anyRequest().authenticated()
            }
            .formLogin { form ->
                form
                    .loginPage("/login")
                    .loginProcessingUrl(LOGIN_PROCESSING_URL)
                    // Fork: count failed sign-ins (LoginThrottle); same targets as before ("/" and "/login?error=true").
                    .successHandler(ThrottlingSuccessHandler(loginThrottle, "/"))
                    .failureHandler(ThrottlingFailureHandler(loginThrottle, clientIp, "/login?error=true", LOCKED_URL))
                    .permitAll()
            }

        http.rememberMe { remember ->
            remember
                .key(rememberMeKeyProvider.key())
                .tokenValiditySeconds(86400 * 30)
                .rememberMeParameter("remember-me")
        }

        // Fork: refuse sign-ins for a locked username/IP before the password is checked.
        http.addFilterBefore(LoginThrottleFilter(loginThrottle, clientIp, LOGIN_PROCESSING_URL, LOCKED_URL), UsernamePasswordAuthenticationFilter::class.java)
        // Fork: role changes, deletions and password changes apply to open sessions (and remember-me logins) at once.
        http.addFilterAfter(SessionRevalidationFilter(userRepository, securityContextRepository), RememberMeAuthenticationFilter::class.java)
        // Fork: general "too many requests" limit per account (anonymous: per IP), once the user is known.
        if (rateLimitEnabled) {
            http.addFilterAfter(RateLimitFilter(rateLimiter, clientIp, messageSource), AnonymousAuthenticationFilter::class.java)
        }

        http.headers { headers ->
            headers.contentSecurityPolicy { csp ->
                csp.policyDirectives(SecurityHeaders.CONTENT_SECURITY_POLICY)
                if (cspReportOnly) csp.reportOnly()
            }
            headers.referrerPolicy { it.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN) }
            headers.permissionsPolicyHeader { it.policy(SecurityHeaders.PERMISSIONS_POLICY) }
            headers.frameOptions { it.deny() }
        }

        http
            .logout { logout ->
                logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout=true")
                    .invalidateHttpSession(true)
                    .deleteCookies("JSESSIONID")
                    .permitAll()
            }

        http
            .exceptionHandling { eh ->
                // Fork: a relative Location ("/login"), never built from the request's (forwarded) host.
                eh.authenticationEntryPoint(LoginUrlAuthenticationEntryPoint("/login").apply { setFavorRelativeUris(true) })
                eh.accessDeniedHandler { _, response, _ ->
                    response.sendRedirect("/profile")
                }
            }

        return http.build()
    }

    companion object {
        const val LOGIN_PROCESSING_URL = "/login/process"
        const val LOCKED_URL = "/login?error=locked"
    }
}
