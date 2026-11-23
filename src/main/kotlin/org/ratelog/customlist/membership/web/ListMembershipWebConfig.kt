package org.ratelog.customlist.membership.web

import org.ratelog.customlist.membership.ListMembershipRepository
import org.ratelog.user.UserDetailsService
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** Registers [ListMembershipInterceptor] on the title pages (the add-to-list sheet and its button). */
@Configuration
class ListMembershipWebConfig(
    private val repository: ListMembershipRepository,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(ListMembershipInterceptor(repository) { UserDetailsService.getCurrentUser() })
            .addPathPatterns("/movie/*", "/tv/*")
    }
}
