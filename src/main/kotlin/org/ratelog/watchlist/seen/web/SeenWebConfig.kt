package org.ratelog.watchlist.seen.web

import org.ratelog.home.HomeRepository
import org.ratelog.movie.MovieRepository
import org.ratelog.rewatch.RewatchRepository
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.user.UserDetailsService
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.time.Clock

/** Registers the model interceptors of the Seen / Upcoming feature on the pages they decorate. */
@Configuration
class SeenWebConfig(
    private val movieRepository: MovieRepository,
    private val tvShowRepository: TvShowRepository,
    private val homeRepository: HomeRepository,
    private val rewatchRepository: RewatchRepository,
) : WebMvcConfigurer {
    private val clock: Clock = Clock.systemDefaultZone()

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(TitleActionsInterceptor(movieRepository, tvShowRepository, clock, rewatchRepository) { UserDetailsService.getCurrentUser() })
            .addPathPatterns("/movie/*", "/tv/*")
        registry.addInterceptor(SearchTileActionsInterceptor(homeRepository, { UserDetailsService.getCurrentUser() }, clock))
            .addPathPatterns("/")
    }
}
