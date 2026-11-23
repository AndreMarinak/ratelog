package org.ratelog.customlist.order

import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListItem
import org.ratelog.customlist.order.move.MoveListItemHandler
import org.ratelog.customlist.order.numbered.SetListNumberedHandler
import org.ratelog.customlist.order.setorder.SetListOrderHandler
import org.ratelog.customlist.progress.ListProgressFixture
import org.ratelog.test.InMemoryListOptionsRepository
import org.ratelog.test.InMemoryListOrderRepository
import org.ratelog.user.User

/** Lists, titles, order and options in memory, wired like the app. */
class ListOrderFixture {
    val p = ListProgressFixture()
    val lists = p.lists
    val order = InMemoryListOrderRepository(lists)
    val options = InMemoryListOptionsRepository(lists)
    val move = MoveListItemHandler(lists, order)
    val setOrder = SetListOrderHandler(lists, order)
    val setNumbered = SetListNumberedHandler(lists, options)

    val owner = User.Id(1)
    val other = User.Id(2)

    /** A list of movies named [titles], in that order (positions 1..n). */
    fun list(vararg titles: String, owner: User.Id = this.owner): Pair<CustomList, List<CustomListItem>> {
        val list = p.list(owner, name = "Ranked")
        val items = p.add(list, *titles.map { p.movie(it) }.toTypedArray())
        return list to items
    }

    /** The list's titles in list order (position, then id). */
    fun titles(list: CustomList): List<String> {
        val items = lists.findById(list.id!!)!!.items.sortedWith(ListOrder.COMPARATOR)
        return items.map { item -> p.movies.findById(org.ratelog.movie.Movie.Id(item.mediaId))!!.originalTitle.value }
    }

    fun positions(list: CustomList): List<Int> = lists.findById(list.id!!)!!.items.sortedWith(ListOrder.COMPARATOR).map { it.position }
}
