package org.ratelog.customlist.browse

import org.ratelog.customlist.ListSummary

/** What templates/fragments/list-card.html renders. `coverPosterUrls` is always [ListSummary.COVER_SIZE] long (null = empty slot). */
data class ListCardView(
    val href: String,
    val name: String,
    val itemCount: Int,
    val isPublic: Boolean,
    /** Shown for other people's lists (as text: the whole card is already a link). */
    val ownerUsername: String?,
    val coverPosterUrls: List<String?>,
    /** How many of its titles the viewer has seen ("9/25 seen"); null where it isn't shown (home row). */
    val seenCount: Int? = null,
    /** Shows the "Official" badge in place of the owner / visibility (fork, official lists). */
    val isOfficial: Boolean = false,
    /**
     * Fork, saved lists (2026-11-24): the card's Save toggle (fragments/saved-lists.html :: card). Null = no toggle,
     * true = "Saved", false = "Save". Only official cards have one.
     */
    val saved: Boolean? = null,
    /** Its place in its section before saved lists moved up: where saved-lists.js puts it back when it is un-saved. */
    val baseOrder: Int = 0,
) {
    /** POST target of the Save toggle (SetListSavedController). */
    val saveHref: String get() = "$href/saved"

    /** A private official list: only its owner sees it, badged "Official · Hidden" (only public official lists are shown to everyone). */
    val isHiddenOfficial: Boolean get() = isOfficial && !isPublic
}

fun ListSummary.toCard(showOwner: Boolean, seenCount: Int? = null) = ListCardView(
    href = "/list/${id.value}",
    name = name,
    itemCount = itemCount,
    isPublic = isPublic,
    ownerUsername = ownerUsername.takeIf { showOwner },
    coverPosterUrls = coverPosterPaths.take(ListSummary.COVER_SIZE).map { "https://image.tmdb.org/t/p/w342$it" } +
        List((ListSummary.COVER_SIZE - coverPosterPaths.size).coerceAtLeast(0)) { null },
    seenCount = seenCount,
    isOfficial = isOfficial,
)
