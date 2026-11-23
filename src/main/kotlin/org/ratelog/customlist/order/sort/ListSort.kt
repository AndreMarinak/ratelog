package org.ratelog.customlist.order.sort

import org.ratelog.customlist.getlistdetail.ListDetailItem
import java.text.Collator
import java.util.Locale

/**
 * The list page's "Sort" menu (`?sort=`): how *this viewer* sees the list. It never changes the saved order, and
 * rank badges keep the list's own numbers. Only data the page already has locally (no TMDB calls).
 * Unknown or missing values mean [LIST].
 */
enum class ListSort(val param: String?, val labelKey: String) {
    LIST(null, "list.sort.list"),
    REVERSE("reverse", "list.sort.reverse"),
    TITLE("title", "list.sort.title"),
    NEWEST("newest", "list.sort.newest"),
    OLDEST("oldest", "list.sort.oldest"),
    RATING("rating", "list.sort.rating"),
    ADDED("added", "list.sort.added");

    /**
     * The items in this order. [items] may come in any order; list order (position, then id) is the base and breaks
     * every tie. Titles without a year, and titles you haven't rated, go last.
     * @param yourScores the viewer's score by item id, only needed for [RATING].
     */
    fun arrange(items: List<ListDetailItem>, yourScores: Map<Long, Double> = emptyMap()): List<ListDetailItem> {
        val listOrder = items.sortedWith(compareBy<ListDetailItem> { it.position }.thenBy { it.id.value })
        return when (this) {
            LIST -> listOrder
            REVERSE -> listOrder.reversed()
            TITLE -> listOrder.sortedWith(compareBy(titleCollator) { titleKey(it.title) })
            NEWEST -> listOrder.sortedWith(compareBy(nullsLast(reverseOrder<Int>())) { it.year })
            OLDEST -> listOrder.sortedWith(compareBy(nullsLast(naturalOrder<Int>())) { it.year })
            RATING -> listOrder.sortedWith(compareBy(nullsLast(reverseOrder<Double>())) { yourScores[it.id.value] })
            // Item ids grow as titles are added, and stay put when the list is reordered.
            ADDED -> listOrder.sortedByDescending { it.id.value }
        }
    }

    companion object {
        private val ALIASES = mapOf(
            "list_order" to LIST,
            "position" to LIST,
            "rank" to LIST,
            "reversed" to REVERSE,
            "name" to TITLE,
            "az" to TITLE,
            "a_z" to TITLE,
            "release_desc" to NEWEST,
            "year_desc" to NEWEST,
            "release_asc" to OLDEST,
            "year_asc" to OLDEST,
            "your_rating" to RATING,
            "score" to RATING,
            "recent" to ADDED,
            "recently_added" to ADDED,
        )

        fun parse(value: String?): ListSort {
            val key = value?.trim()?.lowercase()?.replace('-', '_')
            if (key.isNullOrEmpty()) return LIST
            return entries.find { it.param == key } ?: ALIASES[key] ?: LIST
        }

        private val titleCollator: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
        private val LEADING_ARTICLE = Regex("^(the|a|an)\\s+", RegexOption.IGNORE_CASE)

        /** "The Godfather" files under G, like on Letterboxd. */
        fun titleKey(title: String): String = title.trim().replace(LEADING_ARTICLE, "")
    }
}
