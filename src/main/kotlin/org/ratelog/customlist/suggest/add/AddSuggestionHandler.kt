package org.ratelog.customlist.suggest.add

import arrow.core.Either
import arrow.core.raise.either
import org.ratelog.Lang
import org.ratelog.MediaType
import org.ratelog.api.media.ImportError
import org.ratelog.api.media.ImportException
import org.ratelog.api.media.MediaImporter
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListRepository
import org.ratelog.customlist.membership.ListMembershipRepository
import org.ratelog.customlist.membership.SetListMembership
import org.ratelog.customlist.membership.SetListMembershipError
import org.ratelog.customlist.membership.SetListMembershipHandler
import org.ratelog.user.User
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** "+ Add" on a suggestion: puts a TMDB title at the end of one of the viewer's lists. */
data class AddSuggestion(
    val listId: CustomList.Id,
    val userId: User.Id,
    val type: MediaType,
    val tmdbId: Int,
    /** Metadata language, for the title's name in the answer. */
    val lang: Lang,
)

data class AddSuggestionResult(
    val listName: String,
    val title: String,
    /** The list item, so the page can point at it. */
    val itemId: Long,
    /** The title's place in the list; null on a plain (unnumbered) list. */
    val rank: Int?,
    /** False when it was in the list already (nothing changed). */
    val added: Boolean,
)

sealed interface AddSuggestionError {
    data object ListNotFound : AddSuggestionError
    data object NotOwner : AddSuggestionError

    /** TMDB has no such title. */
    data object TitleNotFound : AddSuggestionError
    data object TmdbNotConfigured : AddSuggestionError
    data object TmdbUnavailable : AddSuggestionError
}

/**
 * Checks the list and its owner first (so nobody else can make the server call TMDB), saves the title locally if
 * Ratelog hasn't seen it yet (the same import as opening its page), then adds it through [SetListMembershipHandler]:
 * appended like "Add to list", and a title already in the list is left where it is.
 */
@Service
class AddSuggestionHandler(
    private val customLists: CustomListRepository,
    private val mediaImporter: MediaImporter,
    private val membershipHandler: SetListMembershipHandler,
    private val membershipRepository: ListMembershipRepository,
) {
    private val logger = LoggerFactory.getLogger(AddSuggestionHandler::class.java)

    fun handle(command: AddSuggestion): Either<AddSuggestionError, AddSuggestionResult> = either {
        val list = customLists.findById(command.listId) ?: raise(AddSuggestionError.ListNotFound)
        if (list.userId != command.userId) raise(AddSuggestionError.NotOwner)

        val media = mediaImporter.ensure(command.tmdbId, command.type, command.lang).getOrElse { e ->
            raise(
                when ((e as? ImportException)?.error) {
                    ImportError.NotFoundOnTmdb -> AddSuggestionError.TitleNotFound
                    ImportError.TmdbNotConfigured -> AddSuggestionError.TmdbNotConfigured
                    null -> {
                        // Never log e.message: TMDB errors can carry the request URL (and its api_key).
                        logger.warn("Saving suggestion {} {} failed: {}", command.type, command.tmdbId, e.javaClass.simpleName)
                        AddSuggestionError.TmdbUnavailable
                    }
                }
            )
        }

        val result = membershipHandler.handle(SetListMembership(command.listId, command.userId, command.type, media.id, inList = true))
            .mapLeft { error ->
                when (error) {
                    SetListMembershipError.ListNotFound -> AddSuggestionError.ListNotFound
                    SetListMembershipError.NotOwner -> AddSuggestionError.NotOwner
                }
            }
            .bind()

        // Read back what is stored: the item and its place, as the list page will show it.
        val here = membershipRepository.findListsContaining(command.userId, command.type, media.id)
            .firstOrNull { it.listId == command.listId }
            ?: raise(AddSuggestionError.ListNotFound)

        AddSuggestionResult(
            listName = result.listName.value,
            title = media.title,
            itemId = here.itemId.value,
            rank = here.rank,
            added = result.changed,
        )
    }
}
