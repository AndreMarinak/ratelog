package org.ratelog.movie.top

import org.ratelog.annotations.CurrentUser
import org.ratelog.ratings.scroll.RankingScroll
import org.ratelog.movie.premieres.MoviePremieresHandler
import org.ratelog.movie.premieres.MoviePremieresQuery
import org.ratelog.tag.Tag
import org.ratelog.tag.TagRepository
import org.ratelog.tag.toView
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

data class GetTopMoviesResponse(
    val rank: Int,
    val tmdbId: Int,
    val title: String,
    val releaseYear: Int?,
    val posterPath: String?,
    val averageScore: Double,
    val genreIds: List<Int>
)

@Controller
class TopMovieController(
    private val handler: TopMovieHandler,
    private val moviePremieresHandler: MoviePremieresHandler,
    private val tagRepository: TagRepository,
) {

    @GetMapping("/movies")
    fun topsPage(
        @CurrentUser user: User,
        @RequestParam("category", required = false) genreId: String?,
        @RequestParam("ratingCategory", required = false) ratingCategory: String?,
        @RequestParam("limit", required = false) requestedLimit: Int?,
        @RequestParam("name", required = false) name: String?,
        @RequestParam("tag", required = false) tagId: Long?,
        // Fork (ranking scroll): `offset` + `partial` answer the next page's cards only (static/ranking-scroll.js).
        @RequestParam("offset", required = false) requestedOffset: Int?,
        @RequestParam("partial", required = false) partial: Boolean?,
        model: Model
    ): String {
        val window = RankingScroll.window(requestedOffset, requestedLimit)
        val limit = window.limit
        val fetched = TopMovie(
            userId = user.id!!,
            genreId = genreId,
            ratingCategory = ratingCategory,
            limit = window.fetch,
            name = name,
            lang = user.metadataLang,
            tagId = tagId?.let(Tag::Id),
            offset = window.offset,
        ).let(handler::handle)
        val tops = fetched.take(limit)

        model.addAttribute("tops", tops.map { toResponse(it) })
        model.addAttribute("rankingPage", RankingScroll.page("/movies", window, fetched.size,
            listOf("category" to genreId, "tag" to tagId, "ratingCategory" to ratingCategory, "name" to name)))
        if (partial == true) return "movies :: ranking"
        model.addAttribute("selectedCategory", genreId)
        model.addAttribute("selectedRatingCategory", ratingCategory)
        model.addAttribute("selectedLimit", limit)
        model.addAttribute("selectedName", name)
        model.addAttribute("selectedTag", tagId)
        model.addAttribute("userTags", tagRepository.findByUserId(user.id).map { it.toView() })

        MoviePremieresQuery(user.id, user.metadataLang).let(moviePremieresHandler::handle)
            .fold(
                { },
                {
                    model.addAttribute("premieres", it.items)
                }
            )

        return "movies"
    }

    private fun toResponse(item: TopMovieItem) = GetTopMoviesResponse(
        rank = item.rank.value,
        tmdbId = item.movie.tmdbId.value,
        title = item.title,
        releaseYear = item.movie.releaseYear,
        posterPath = item.movie.posterPath?.value,
        averageScore = item.rating.score?.value ?: 0.0,
        genreIds = item.movie.genres.map { it.tmdbId }
    )
}
