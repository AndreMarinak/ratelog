package org.ratelog.customlist.saved.web

import org.ratelog.customlist.saved.intitle.OfficialTitleListsRepository
import org.ratelog.user.UserDetailsService
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Registers the saved-lists interceptor: title pages + the tile sheet (official lists with the title). The home "Your lists"
 * row no longer adds saved lists (2026-11-24: your lists are the ones you made; saved ones lead the Official rows).
 */
@Configuration
class SavedListsWebConfig(
    private val officialTitleLists: OfficialTitleListsRepository,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(OfficialTitleListsInterceptor(officialTitleLists) { UserDetailsService.getCurrentUser() })
            .addPathPatterns("/movie/*", "/tv/*", "/tile/lists")
    }
}
