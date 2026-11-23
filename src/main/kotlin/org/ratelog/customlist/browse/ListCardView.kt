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
)

fun ListSummary.toCard(showOwner: Boolean) = ListCardView(
    href = "/list/${id.value}",
    name = name,
    itemCount = itemCount,
    isPublic = isPublic,
    ownerUsername = ownerUsername.takeIf { showOwner },
    coverPosterUrls = coverPosterPaths.take(ListSummary.COVER_SIZE).map { "https://image.tmdb.org/t/p/w342$it" } +
        List((ListSummary.COVER_SIZE - coverPosterPaths.size).coerceAtLeast(0)) { null },
)
