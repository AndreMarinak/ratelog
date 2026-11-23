package org.ratelog.test

import org.ratelog.MediaType
import org.ratelog.tag.Tag
import org.ratelog.tag.TagAssignment
import org.ratelog.tag.TagName
import org.ratelog.tag.TagRepository
import org.ratelog.user.User
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

class InMemoryTagRepository : TagRepository {
    private val tags = ConcurrentHashMap<Tag.Id, Tag>()
    private val assignments = CopyOnWriteArrayList<TagAssignment>()
    private val idGenerator = AtomicLong(1)

    override fun findById(id: Tag.Id): Tag? = tags[id]

    override fun findByUserId(userId: User.Id): List<Tag> =
        tags.values.filter { it.userId == userId }.sortedBy { it.name.key }

    override fun findByUserIdAndName(userId: User.Id, name: TagName): Tag? =
        tags.values.find { it.userId == userId && it.name.key == name.key }

    override fun save(tag: Tag): Tag {
        // Mirrors the unique (user_id, LOWER(name)) index.
        val clash = findByUserIdAndName(tag.userId, tag.name)
        check(clash == null || clash.id == tag.id) { "duplicate tag name ${tag.name.value}" }
        val saved = if (tag.id == null) tag.copy(id = Tag.Id(idGenerator.getAndIncrement())) else tag
        tags[saved.id!!] = saved
        return saved
    }

    override fun delete(id: Tag.Id) {
        tags.remove(id)
        assignments.removeIf { it.tagId == id }
    }

    override fun countTitlesByUserId(userId: User.Id): Map<Tag.Id, Int> =
        assignments.filter { tags[it.tagId]?.userId == userId }.groupingBy { it.tagId }.eachCount()

    override fun findForTitle(userId: User.Id, mediaType: MediaType, mediaId: Long): List<Tag> =
        assignments.filter { it.mediaType == mediaType && it.mediaId == mediaId }
            .mapNotNull { tags[it.tagId] }
            .filter { it.userId == userId }
            .sortedBy { it.name.key }

    override fun findAssignments(tagId: Tag.Id): List<TagAssignment> =
        assignments.filter { it.tagId == tagId }.reversed()

    override fun assign(tagId: Tag.Id, mediaType: MediaType, mediaId: Long, createdAtEpochMs: Long) {
        if (!isAssigned(tagId, mediaType, mediaId)) {
            assignments += TagAssignment(tagId, mediaType, mediaId, createdAtEpochMs)
        }
    }

    override fun unassign(tagId: Tag.Id, mediaType: MediaType, mediaId: Long) {
        assignments.removeIf { it.tagId == tagId && it.mediaType == mediaType && it.mediaId == mediaId }
    }

    fun isAssigned(tagId: Tag.Id, mediaType: MediaType, mediaId: Long): Boolean =
        assignments.any { it.tagId == tagId && it.mediaType == mediaType && it.mediaId == mediaId }

    /** Same rule as the ranking queries: the tag must be the user's own. */
    fun isTaggedForUser(tagId: Tag.Id, userId: User.Id, mediaType: MediaType, mediaId: Long): Boolean =
        tags[tagId]?.userId == userId && isAssigned(tagId, mediaType, mediaId)
}
