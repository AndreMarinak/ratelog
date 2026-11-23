package org.ratelog.api.lists

import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.api.CandidateDto
import org.ratelog.api.ItemInput
import org.ratelog.api.TitleDto
import org.ratelog.api.media.ImportError
import org.ratelog.api.media.ImportException
import org.ratelog.api.media.MatchResult
import org.ratelog.api.media.MediaImporter
import org.ratelog.api.media.TitleResolver
import org.ratelog.api.parseMediaType
import org.ratelog.api.toDto
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.additem.AddToListCommand
import org.ratelog.customlist.additem.AddToListError
import org.ratelog.customlist.additem.AddToListHandler
import org.ratelog.tag.TagName
import org.ratelog.tag.TagRepository
import org.ratelog.tag.assign.AssignTagsCommand
import org.ratelog.tag.assign.AssignTagsError
import org.ratelog.tag.assign.AssignTagsHandler
import org.ratelog.tmdb.TmdbError
import org.ratelog.user.User
import org.springframework.stereotype.Service

data class UnresolvedItem(
    val input: ItemInput,
    /** not_found | ambiguous | invalid | tmdb_error */
    val reason: String,
    val message: String,
    val candidates: List<CandidateDto> = emptyList(),
)

data class AddItemsResult(
    val added: List<TitleDto>,
    val alreadyInList: List<TitleDto>,
    val unresolved: List<UnresolvedItem>,
) {
    val summary: String
        get() = "${added.size} added, ${alreadyInList.size} already in list, ${unresolved.size} unresolved"
}

/**
 * Resolves each input to a TMDB title, saves it locally if needed, and appends it to the list
 * in the given order. One bad item never fails the batch; it is reported in [AddItemsResult.unresolved].
 * Items may carry `tags`, which are added to the title (whether it was just added or already in the list).
 */
@Service
class AddListItemsService(
    private val titleResolver: TitleResolver,
    private val mediaImporter: MediaImporter,
    private val addToListHandler: AddToListHandler,
    private val assignTagsHandler: AssignTagsHandler,
) {
    fun addItems(listId: CustomList.Id, userId: User.Id, lang: Lang, items: List<ItemInput>): AddItemsResult {
        val added = mutableListOf<TitleDto>()
        val already = mutableListOf<TitleDto>()
        val unresolved = mutableListOf<UnresolvedItem>()

        for (input in items) {
            val target = try {
                resolveTarget(input)
            } catch (e: IllegalArgumentException) {
                unresolved += UnresolvedItem(input, "invalid", e.message ?: "Invalid item")
                continue
            }
            when (target) {
                is Target.Unresolved -> { unresolved += target.item; continue }
                is Target.Found -> {
                    val media = mediaImporter.ensure(target.tmdbId, target.type, lang).getOrElse { e ->
                        val reason = ((e as? ImportException)?.error)
                        unresolved += UnresolvedItem(
                            input,
                            if (reason == ImportError.TmdbNotConfigured) "tmdb_error" else "not_found",
                            if (reason == ImportError.TmdbNotConfigured) "TMDB API key is not configured"
                            else "TMDB has no ${target.type.name} with id ${target.tmdbId}",
                        )
                        continue
                    }
                    // Tag before adding, so an item whose tags can't be applied is reported instead of half-done.
                    tagFailure(userId, media.type, media.id, input.tags.orEmpty())?.let { message ->
                        unresolved += UnresolvedItem(input, "invalid", message)
                        continue
                    }
                    addToListHandler.handle(AddToListCommand(listId, userId, media.id, media.type.name)).fold(
                        { error ->
                            when (error) {
                                AddToListError.ItemAlreadyInList -> already += media.toDto()
                                // The caller checks ownership first, so these mean the list vanished mid-request.
                                AddToListError.ListNotFound, AddToListError.NotOwner ->
                                    unresolved += UnresolvedItem(input, "invalid", "List is no longer available")
                            }
                        },
                        { added += media.toDto() }
                    )
                }
            }
        }
        return AddItemsResult(added, already, unresolved)
    }

    /** Adds the tags to the title; returns why that failed, or null on success. */
    private fun tagFailure(userId: User.Id, type: MediaType, mediaId: Long, tags: List<String>): String? {
        if (tags.isEmpty()) return null
        return assignTagsHandler.handle(AssignTagsCommand(userId, type, mediaId, tags)).fold(
            { error ->
                when (error) {
                    AssignTagsError.InvalidName -> TAG_NAME_MESSAGE
                    AssignTagsError.TitleNotFound -> "Title is no longer available"
                    AssignTagsError.TagLimitExceeded -> "Tag limit reached (${TagRepository.MAX_TAGS_PER_USER} per user)"
                }
            },
            { null }
        )
    }

    private sealed interface Target {
        data class Found(val tmdbId: Int, val type: MediaType) : Target
        data class Unresolved(val item: UnresolvedItem) : Target
    }

    private fun resolveTarget(input: ItemInput): Target {
        val type = try {
            parseMediaType(input.type)
        } catch (e: Exception) {
            throw IllegalArgumentException(e.message)
        }
        // Checked up front so a bad tag name doesn't cost a TMDB lookup.
        require(input.tags.orEmpty().all { TagName.parse(it).isRight() }) { TAG_NAME_MESSAGE }

        if (input.tmdbId != null) {
            // Movie and TV ids are separate namespaces on TMDB, so the id alone is ambiguous.
            require(type != null) { "type is required when tmdbId is given (\"movie\" or \"tv\")" }
            require(input.tmdbId > 0) { "tmdbId must be positive" }
            return Target.Found(input.tmdbId, type)
        }

        val title = input.title?.trim()
        require(!title.isNullOrEmpty()) { "Each item needs either tmdbId + type, or a title" }

        return titleResolver.resolve(title, input.year, type).fold(
            { error ->
                Target.Unresolved(
                    UnresolvedItem(
                        input, "tmdb_error",
                        if (error == TmdbError.ApiKeyMissing) "TMDB API key is not configured" else "TMDB lookup failed"
                    )
                )
            },
            { match ->
                when (match) {
                    is MatchResult.Matched -> Target.Found(match.candidate.tmdbId, match.candidate.type)
                    is MatchResult.Ambiguous -> Target.Unresolved(
                        UnresolvedItem(
                            input, "ambiguous",
                            "Several titles match; retry with year, type, or tmdbId from candidates",
                            match.candidates.map { it.toDto() },
                        )
                    )
                    is MatchResult.NotFound -> Target.Unresolved(
                        UnresolvedItem(
                            input, "not_found",
                            "No exact title match; retry with tmdbId from candidates or a corrected title",
                            match.candidates.map { it.toDto() },
                        )
                    )
                }
            }
        )
    }

    companion object {
        private const val TAG_NAME_MESSAGE = "tag names must be 1-${TagName.MAX_LENGTH} characters"
    }
}
