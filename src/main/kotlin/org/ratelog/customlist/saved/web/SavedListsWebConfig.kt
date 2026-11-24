package org.ratelog.customlist.saved.web

import org.ratelog.customlist.saved.browse.GetSavedListsHandler
import org.ratelog.customlist.saved.intitle.OfficialTitleListsRepository
import org.ratelog.user.UserDetailsService
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** Registers the saved-lists interceptors: title pages + the tile sheet (official lists with the title), and home. */
@Configuration
class SavedListsWebConfig(
    private val officialTitleLists: OfficialTitleListsRepository,
    private val savedListsHandler: GetSavedListsHandler,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(OfficialTitleListsInterceptor(officialTitleLists) { UserDetailsService.getCurrentUser() })
            .addPathPatterns("/movie/*", "/tv/*", "/tile/lists")
        registry.addInterceptor(SavedListsHomeInterceptor(savedListsHandler) { UserDetailsService.getCurrentUser() })
            .addPathPatterns("/")
    }
}
