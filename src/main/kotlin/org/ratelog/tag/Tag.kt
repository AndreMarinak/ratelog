package org.ratelog.tag

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import org.ratelog.MediaType
import org.ratelog.user.User
import java.util.Locale

/** A user's own label for titles ("comfort watch", "mind-benders"). Private to that user. */
data class Tag(
    val id: Id?,
    val userId: User.Id,
    val name: TagName,
    val color: TagColor?,
    val createdAtEpochMs: Long,
) {
    data class Id(val value: Long)
}

/** 1-40 characters after trimming, inner whitespace collapsed. Unique per user ignoring case. */
data class TagName(val value: String) {
    /** Names are compared ignoring case. */
    val key: String get() = value.lowercase(Locale.ROOT)

    companion object {
        const val MAX_LENGTH = 40

        fun parse(raw: String?): Either<TagParseError.InvalidName, TagName> = either {
            val normalized = raw.orEmpty().trim().replace(Regex("\\s+"), " ")
            ensure(normalized.length in 1..MAX_LENGTH && normalized.none { it.isISOControl() }) {
                TagParseError.InvalidName
            }
            TagName(normalized)
        }
    }
}

/** Small fixed palette, tuned for the dark theme. */
enum class TagColor(val hex: String) {
    clay("#d9805f"),
    amber("#d6a648"),
    green("#86ad69"),
    teal("#4fae9f"),
    blue("#6b9fd6"),
    violet("#a68ad8"),
    pink("#d67ba2"),
    gray("#9b948d");

    companion object {
        /** Blank or "none" means no color. */
        fun parse(raw: String?): Either<TagParseError.InvalidColor, TagColor?> = either {
            val value = raw?.trim()?.lowercase(Locale.ROOT)
            if (value.isNullOrEmpty() || value == "none") null
            else entries.find { it.name == value } ?: raise(TagParseError.InvalidColor)
        }
    }
}

sealed interface TagParseError {
    data object InvalidName : TagParseError
    data object InvalidColor : TagParseError
}

data class TagAssignment(
    val tagId: Tag.Id,
    val mediaType: MediaType,
    val mediaId: Long,
    val createdAtEpochMs: Long,
)

interface TagRepository {
    fun findById(id: Tag.Id): Tag?
    /** Sorted by name, ignoring case. */
    fun findByUserId(userId: User.Id): List<Tag>
    /** Case-insensitive lookup. */
    fun findByUserIdAndName(userId: User.Id, name: TagName): Tag?
    fun save(tag: Tag): Tag
    /** Also removes the tag from every title. */
    fun delete(id: Tag.Id)
    /** Number of titles per tag; tags without titles are absent. */
    fun countTitlesByUserId(userId: User.Id): Map<Tag.Id, Int>
    fun findForTitle(userId: User.Id, mediaType: MediaType, mediaId: Long): List<Tag>
    /** Newest first. */
    fun findAssignments(tagId: Tag.Id): List<TagAssignment>
    /** No-op when the title already has the tag. */
    fun assign(tagId: Tag.Id, mediaType: MediaType, mediaId: Long, createdAtEpochMs: Long)
    fun unassign(tagId: Tag.Id, mediaType: MediaType, mediaId: Long)

    companion object {
        const val MAX_TAGS_PER_USER = 200
    }
}

/** What the templates render: a chip with an optional palette color. */
data class TagView(
    val id: Long,
    val name: String,
    val color: String?,
    val style: String?,
    val titleCount: Int,
)

fun Tag.toView(titleCount: Int = 0) = TagView(
    id = id!!.value,
    name = name.value,
    color = color?.name,
    style = color?.let { "--tag-color: ${it.hex}" },
    titleCount = titleCount,
)

/** Detail page of a title in the web UI. */
fun titlePath(mediaType: MediaType, tmdbId: Int): String = when (mediaType) {
    MediaType.movie -> "/movie/$tmdbId"
    MediaType.tvshow -> "/tv/$tmdbId"
}
