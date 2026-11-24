package org.ratelog.recommendations.foryou.web

import org.ratelog.annotations.CurrentUser
import org.ratelog.browse.BrowseParamParser
import org.ratelog.browse.BrowseParams
import org.ratelog.browse.discover.DiscoverController
import org.ratelog.recommendations.foryou.ForYouSeedRepository
import org.ratelog.user.User
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.RequestParam
import org.thymeleaf.context.LazyContextVariable
import java.time.Clock
import java.time.LocalDate

/*
 * "Recommended for you" on the Discover landing, right after "Popular now" (fragments/discover-rows.html), in the
 * page's Movies / TV mode. Added without touching DiscoverController. The value is lazy: only the landing renders the
 * row, so the poster grid and the /discover/row fetches cost no query. Viewers who haven't rated a title of that type
 * get no row at all (not a skeleton that then disappears).
 */
@ControllerAdvice(assignableTypes = [DiscoverController::class])
class DiscoverForYouAdvice(
    private val seeds: ForYouSeedRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @ModelAttribute
    fun addForYouRow(@CurrentUser user: User?, @RequestParam("type", required = false) type: String?, model: Model) {
        val viewerId = user?.id ?: return
        val mediaType = BrowseParamParser.parse(BrowseParams(type = type), LocalDate.now(clock)).filters.type
        model.addAttribute(MODEL_KEY, object : LazyContextVariable<ForYouPlaceholderView?>() {
            override fun loadValue(): ForYouPlaceholderView? =
                if (seeds.hasRatings(viewerId, mediaType)) ForYouViews.placeholder(mediaType) else null
        })
    }

    companion object {
        const val MODEL_KEY = "forYouRow"
    }
}
