package org.ratelog.tag

import org.ratelog.MediaType
import org.ratelog.user.User
import org.springframework.stereotype.Repository
import kotlin.jvm.optionals.getOrNull

@Repository
class TagRepositoryImpl(
    private val tagDAO: TagDAO,
    private val assignmentDAO: TagAssignmentDAO,
) : TagRepository {

    override fun findById(id: Tag.Id): Tag? =
        tagDAO.findById(id.value).getOrNull()?.toDomain()

    override fun findByUserId(userId: User.Id): List<Tag> =
        tagDAO.findByUserId(userId.value).map { it.toDomain() }

    override fun findByUserIdAndName(userId: User.Id, name: TagName): Tag? =
        tagDAO.findByUserIdAndNameIgnoreCase(userId.value, name.value).getOrNull()?.toDomain()

    override fun save(tag: Tag): Tag =
        tagDAO.save(tag.toEntity()).toDomain()

    override fun delete(id: Tag.Id) {
        assignmentDAO.deleteByTagId(id.value)
        tagDAO.deleteById(id.value)
    }

    override fun countTitlesByUserId(userId: User.Id): Map<Tag.Id, Int> =
        assignmentDAO.countByUserId(userId.value).associate { Tag.Id(it.tagId) to it.titleCount.toInt() }

    override fun findForTitle(userId: User.Id, mediaType: MediaType, mediaId: Long): List<Tag> =
        tagDAO.findForTitle(userId.value, mediaType.name, mediaId).map { it.toDomain() }

    override fun findAssignments(tagId: Tag.Id): List<TagAssignment> =
        assignmentDAO.findByTagId(tagId.value).map {
            TagAssignment(Tag.Id(it.tagId), MediaType.valueOf(it.mediaType), it.mediaId, it.createdAtEpochMs)
        }

    override fun assign(tagId: Tag.Id, mediaType: MediaType, mediaId: Long, createdAtEpochMs: Long) =
        assignmentDAO.insertIfAbsent(tagId.value, mediaType.name, mediaId, createdAtEpochMs)

    override fun unassign(tagId: Tag.Id, mediaType: MediaType, mediaId: Long) =
        assignmentDAO.deleteOne(tagId.value, mediaType.name, mediaId)

    private fun TagEntity.toDomain() = Tag(
        id = id?.let(Tag::Id),
        userId = User.Id(userId),
        name = TagName(name),
        color = color?.let { stored -> TagColor.entries.find { it.name == stored } },
        createdAtEpochMs = createdAtEpochMs,
    )

    private fun Tag.toEntity() = TagEntity(
        id = id?.value,
        userId = userId.value,
        name = name.value,
        color = color?.name,
        createdAtEpochMs = createdAtEpochMs,
    )
}
