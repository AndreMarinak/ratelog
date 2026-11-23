package org.ratelog.api.tags

import org.ratelog.annotations.CurrentUser
import org.ratelog.api.ApiException
import org.ratelog.api.apiName
import org.ratelog.api.badRequest
import org.ratelog.api.media.ImportError
import org.ratelog.api.media.ImportException
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.notFound
import org.ratelog.api.parseMediaType
import org.ratelog.api.posterUrl
import org.ratelog.tag.Tag
import org.ratelog.tag.TagColor
import org.ratelog.tag.TagName
import org.ratelog.tag.TagRepository
import org.ratelog.tag.create.CreateTagCommand
import org.ratelog.tag.create.CreateTagError
import org.ratelog.tag.create.CreateTagHandler
import org.ratelog.tag.delete.DeleteTagCommand
import org.ratelog.tag.delete.DeleteTagHandler
import org.ratelog.tag.detail.GetTagDetail
import org.ratelog.tag.detail.GetTagDetailHandler
import org.ratelog.tag.list.ListTags
import org.ratelog.tag.list.ListTagsHandler
import org.ratelog.tag.replace.ReplaceTitleTagsCommand
import org.ratelog.tag.replace.ReplaceTitleTagsError
import org.ratelog.tag.replace.ReplaceTitleTagsHandler
import org.ratelog.tag.update.UpdateTagCommand
import org.ratelog.tag.update.UpdateTagError
import org.ratelog.tag.update.UpdateTagHandler
import org.ratelog.user.User
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class TagDto(val id: Long, val name: String, val color: String?, val titleCount: Int, val url: String)

data class TaggedTitleDto(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val year: Int?,
    val posterUrl: String?,
    /** The user's own score, if rated. */
    val score: Double?,
)

data class TagTitlesDto(val tag: TagDto, val titles: List<TaggedTitleDto>)

data class TitleTagsDto(
    val tmdbId: Int,
    val type: String,
    /** null when Ratelog has never seen the title (it then has no tags). */
    val title: String?,
    val tags: List<TagDto>,
    /** Names of tags this request created (PUT only). */
    val created: List<String> = emptyList(),
)

data class CreateTagRequest(val name: String? = null, val color: String? = null)

/** Omitted fields are unchanged; `"color": ""` or `"none"` removes the color. */
data class UpdateTagRequest(val name: String? = null, val color: String? = null)

data class ReplaceTagsRequest(val tags: List<String>? = null)

@RestController
@RequestMapping("/api/v1")
class ApiTagController(
    private val tagRepository: TagRepository,
    private val listTagsHandler: ListTagsHandler,
    private val createTagHandler: CreateTagHandler,
    private val updateTagHandler: UpdateTagHandler,
    private val deleteTagHandler: DeleteTagHandler,
    private val getTagDetailHandler: GetTagDetailHandler,
    private val replaceTitleTagsHandler: ReplaceTitleTagsHandler,
    private val mediaImporter: MediaImporter,
) {
    @GetMapping("/tags")
    fun tags(@CurrentUser user: User): List<TagDto> =
        listTagsHandler.handle(ListTags(user.id!!)).map { it.tag.toDto(it.titleCount) }

    @PostMapping("/tags")
    fun create(@CurrentUser user: User, @RequestBody body: CreateTagRequest): ResponseEntity<TagDto> {
        val tag = createTagHandler.handle(CreateTagCommand(user.id!!, body.name.orEmpty(), body.color)).fold(
            { error ->
                throw when (error) {
                    CreateTagError.InvalidName -> invalidName()
                    CreateTagError.InvalidColor -> invalidColor()
                    is CreateTagError.AlreadyExists -> tagExists(error.existing)
                    CreateTagError.TagLimitExceeded -> tagLimit()
                }
            },
            { it }
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(tag.toDto(0))
    }

    @PatchMapping("/tags/{id}")
    fun update(@CurrentUser user: User, @PathVariable id: Long, @RequestBody body: UpdateTagRequest): TagDto {
        val tag = updateTagHandler.handle(UpdateTagCommand(user.id!!, Tag.Id(id), body.name, body.color)).fold(
            { error ->
                throw when (error) {
                    UpdateTagError.TagNotFound, UpdateTagError.NotOwner -> tagNotFound(id)
                    UpdateTagError.InvalidName -> invalidName()
                    UpdateTagError.InvalidColor -> invalidColor()
                    UpdateTagError.NameTaken -> ApiException(HttpStatus.CONFLICT, "tag_exists", "You already have a tag with that name")
                }
            },
            { it }
        )
        return tag.toDto(tagRepository.countTitlesByUserId(user.id)[tag.id] ?: 0)
    }

    @DeleteMapping("/tags/{id}")
    fun delete(@CurrentUser user: User, @PathVariable id: Long): ResponseEntity<Unit> {
        deleteTagHandler.handle(DeleteTagCommand(user.id!!, Tag.Id(id))).onLeft { throw tagNotFound(id) }
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/tags/{id}/titles")
    fun titles(@CurrentUser user: User, @PathVariable id: Long): TagTitlesDto {
        val detail = getTagDetailHandler.handle(GetTagDetail(user.id!!, Tag.Id(id), user.metadataLang))
            .fold({ throw tagNotFound(id) }, { it })
        return TagTitlesDto(
            tag = detail.tag.toDto(detail.titles.size),
            titles = detail.titles.map {
                TaggedTitleDto(it.tmdbId, it.mediaType.apiName(), it.title, it.year, it.posterPath?.let(::posterUrl), it.score)
            },
        )
    }

    /** A title's tags. Read-only: a title Ratelog has never seen simply has none. */
    @GetMapping("/titles/{type}/{tmdbId}/tags")
    fun titleTags(@CurrentUser user: User, @PathVariable type: String, @PathVariable tmdbId: Int): TitleTagsDto {
        val mediaType = parseMediaType(type)!!
        val local = mediaImporter.findLocal(tmdbId, mediaType, user.metadataLang)
            ?: return TitleTagsDto(tmdbId, mediaType.apiName(), null, emptyList())
        val counts = tagRepository.countTitlesByUserId(user.id!!)
        val tags = tagRepository.findForTitle(user.id, mediaType, local.id).map { it.toDto(counts[it.id] ?: 0) }
        return TitleTagsDto(tmdbId, mediaType.apiName(), local.title, tags)
    }

    /** Replaces the title's tags with exactly `tags` (creating missing ones); `[]` clears them. */
    @PutMapping("/titles/{type}/{tmdbId}/tags")
    fun replaceTitleTags(
        @CurrentUser user: User,
        @PathVariable type: String,
        @PathVariable tmdbId: Int,
        @RequestBody body: ReplaceTagsRequest,
    ): TitleTagsDto {
        val mediaType = parseMediaType(type)!!
        val names = body.tags ?: throw badRequest("invalid_parameter", "tags is required (send [] to remove all tags)")
        if (names.size > MAX_TAGS_PER_TITLE) throw badRequest("invalid_parameter", "Send at most $MAX_TAGS_PER_TITLE tags per title")
        if (tmdbId <= 0) throw badRequest("invalid_parameter", "tmdbId must be positive")
        // Validate before importing anything from TMDB.
        if (!names.all { TagName.parse(it).isRight() }) throw invalidName()

        val media = mediaImporter.ensure(tmdbId, mediaType, user.metadataLang).getOrElse { e ->
            throw when ((e as? ImportException)?.error) {
                ImportError.TmdbNotConfigured ->
                    ApiException(HttpStatus.SERVICE_UNAVAILABLE, "tmdb_not_configured", "TMDB API key is not configured (Admin > Configuration)")
                else -> notFound("TMDB has no ${mediaType.apiName()} with id $tmdbId")
            }
        }
        val result = replaceTitleTagsHandler.handle(ReplaceTitleTagsCommand(user.id!!, mediaType, media.id, names)).fold(
            { error ->
                throw when (error) {
                    ReplaceTitleTagsError.InvalidName -> invalidName()
                    ReplaceTitleTagsError.TitleNotFound -> notFound("TMDB has no ${mediaType.apiName()} with id $tmdbId")
                    ReplaceTitleTagsError.TagLimitExceeded -> tagLimit()
                }
            },
            { it }
        )
        val counts = tagRepository.countTitlesByUserId(user.id)
        return TitleTagsDto(
            tmdbId = media.tmdbId,
            type = mediaType.apiName(),
            title = media.title,
            tags = result.tags.sortedBy { it.name.key }.map { it.toDto(counts[it.id] ?: 0) },
            created = result.created.map { it.name.value },
        )
    }

    private fun Tag.toDto(titleCount: Int) = TagDto(id!!.value, name.value, color?.name, titleCount, "/tags/${id.value}")

    private fun tagNotFound(id: Long) = notFound("Tag $id not found")
    private fun invalidName() = badRequest("invalid_name", "Tag names must be 1-${TagName.MAX_LENGTH} characters")
    private fun invalidColor() = badRequest(
        "invalid_color",
        "color must be one of ${TagColor.entries.joinToString(", ") { it.name }} (or \"none\")",
    )
    private fun tagExists(existing: Tag) =
        ApiException(HttpStatus.CONFLICT, "tag_exists", "Tag \"${existing.name.value}\" already exists (id ${existing.id!!.value})")
    private fun tagLimit() = badRequest("tag_limit", "Tag limit reached (${TagRepository.MAX_TAGS_PER_USER} per user)")

    companion object {
        const val MAX_TAGS_PER_TITLE = 50
    }
}
