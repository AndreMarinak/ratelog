package org.ratelog.tag.assign

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.MediaType
import org.ratelog.movie.Movie
import org.ratelog.movie.MovieRepository
import org.ratelog.tag.Tag
import org.ratelog.tag.TagName
import org.ratelog.tag.TagRepository
import org.ratelog.tvshow.TvShow
import org.ratelog.tvshow.TvShowRepository
import org.ratelog.user.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

sealed interface AssignTagsError {
    data object InvalidName : AssignTagsError
    data object TitleNotFound : AssignTagsError
    data object TagLimitExceeded : AssignTagsError
}

/** Adds tags to a title by name. Names the user has no tag for yet create a new (uncolored) tag. */
data class AssignTagsCommand(
    val userId: User.Id,
    val mediaType: MediaType,
    val mediaId: Long,
    val names: List<String>,
)

data class AssignedTags(
    /** The tags named in the command, in order, without duplicates. */
    val tags: List<Tag>,
    /** The subset that did not exist before. */
    val created: List<Tag>,
)

@Service
class AssignTagsHandler(
    private val tagRepository: TagRepository,
    private val movieRepository: MovieRepository,
    private val tvShowRepository: TvShowRepository,
) {
    @Transactional
    fun handle(command: AssignTagsCommand): Either<AssignTagsError, AssignedTags> = either {
        val names = command.names
            .map { TagName.parse(it).mapLeft { AssignTagsError.InvalidName }.bind() }
            .distinctBy { it.key }

        val exists = when (command.mediaType) {
            MediaType.movie -> movieRepository.findById(Movie.Id(command.mediaId)) != null
            MediaType.tvshow -> tvShowRepository.findById(TvShow.Id(command.mediaId)) != null
        }
        ensure(exists) { AssignTagsError.TitleNotFound }

        val found = names.map { it to tagRepository.findByUserIdAndName(command.userId, it) }
        val missing = found.count { it.second == null }
        if (missing > 0) {
            ensure(tagRepository.findByUserId(command.userId).size + missing <= TagRepository.MAX_TAGS_PER_USER) {
                AssignTagsError.TagLimitExceeded
            }
        }

        val now = System.currentTimeMillis()
        val created = mutableListOf<Tag>()
        val tags = found.map { (name, tag) ->
            tag ?: tagRepository.save(Tag(null, command.userId, name, null, now)).also(created::add)
        }
        tags.forEach { tagRepository.assign(it.id!!, command.mediaType, command.mediaId, now) }

        AssignedTags(tags, created)
    }
}
