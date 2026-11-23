package org.ratelog.tag.detail

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.api.media.MediaImporter
import org.ratelog.movie.Movie
import org.ratelog.movie.rating.RatingRepository
import org.ratelog.tag.Tag
import org.ratelog.tag.TagRepository
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.rating.TvRatingRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

sealed interface GetTagDetailError {
    data object TagNotFound : GetTagDetailError
    data object NotOwner : GetTagDetailError
}

data class GetTagDetail(
    val userId: User.Id,
    val tagId: Tag.Id,
    val lang: Lang,
)

data class TaggedTitle(
    val mediaType: MediaType,
    val mediaId: Long,
    val tmdbId: Int,
    val title: String,
    val year: Int?,
    val posterPath: String?,
    /** The user's own score, if they rated it. */
    val score: Double?,
)

data class TagDetail(
    val tag: Tag,
    /** Sorted by title. */
    val titles: List<TaggedTitle>,
)

@Service
class GetTagDetailHandler(
    private val tagRepository: TagRepository,
    private val mediaImporter: MediaImporter,
    private val ratingRepository: RatingRepository,
    private val tvRatingRepository: TvRatingRepository,
) {
    @Transactional(readOnly = true)
    fun handle(query: GetTagDetail): Either<GetTagDetailError, TagDetail> = either {
        val tag = tagRepository.findById(query.tagId) ?: raise(GetTagDetailError.TagNotFound)
        ensure(tag.userId == query.userId) { GetTagDetailError.NotOwner }

        val titles = tagRepository.findAssignments(tag.id!!)
            .mapNotNull { assignment ->
                mediaImporter.findLocalById(assignment.mediaId, assignment.mediaType, query.lang)?.let { media ->
                    TaggedTitle(
                        mediaType = media.type,
                        mediaId = media.id,
                        tmdbId = media.tmdbId,
                        title = media.title,
                        year = media.year,
                        posterPath = media.posterPath,
                        score = userScore(query.userId, media.type, media.id),
                    )
                }
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

        TagDetail(tag, titles)
    }

    private fun userScore(userId: User.Id, type: MediaType, mediaId: Long): Double? = when (type) {
        MediaType.movie -> ratingRepository.findByMovieIdAndUserId(Movie.Id(mediaId), userId)?.score?.value
        MediaType.tvshow -> tvRatingRepository.findByTvShowIdAndUserId(TvShow.Id(mediaId), userId)?.score?.value
    }
}
