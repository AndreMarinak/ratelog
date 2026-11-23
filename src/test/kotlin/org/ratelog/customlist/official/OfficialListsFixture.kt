package org.ratelog.customlist.official

import org.ratelog.Role
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.CustomListRepository
import org.ratelog.customlist.browse.GetListsPageHandler
import org.ratelog.customlist.official.browse.GetOfficialListsHandler
import org.ratelog.customlist.official.mark.SetListOfficial
import org.ratelog.customlist.official.mark.SetListOfficialHandler
import org.ratelog.customlist.progress.ListProgressFixture
import org.ratelog.test.InMemoryListSummaryRepository
import org.ratelog.test.InMemoryOfficialListRepository
import org.ratelog.test.InMemoryUserRepository
import org.ratelog.test.MutableClock
import org.ratelog.test.UserFactory
import org.ratelog.user.User

/** Lists, users, progress and official marks wired the way the app wires them (decorated list repository included). */
class OfficialListsFixture {
    val p = ListProgressFixture()
    val users = InMemoryUserRepository()
    val official = InMemoryOfficialListRepository(p.lists)
    /** What every injection point gets in the app: official lists stay public on save. */
    val lists: CustomListRepository = OfficialListsStayPublicRepository(p.lists, official)
    val summaries = InMemoryListSummaryRepository(p.lists, users, p.movies, p.shows, official)
    val clock = MutableClock()
    val setOfficial = SetListOfficialHandler(lists, official, users, clock)
    val getOfficial = GetOfficialListsHandler(summaries, p.handler)
    val listsPage = GetListsPageHandler(summaries, users)

    val admin: User = users.save(UserFactory.aUser(username = "admin", role = Role.SUPERADMIN))
    val andre: User = users.save(UserFactory.aUser(username = "andre"))

    /** A list with movies in it; `createdAt` orders the Lists tab (newest activity first). */
    fun list(owner: User, name: String, isPublic: Boolean = false, titles: Int = 2, createdAt: Long = 1_000L): CustomList {
        val list = p.lists.save(CustomList(null, owner.id!!, org.ratelog.ListName(name), isPublic, createdAtEpochMs = createdAt))
        if (titles > 0) p.add(list, *(1..titles).map { p.movie("$name $it") }.toTypedArray())
        return p.lists.findById(list.id!!)!!
    }

    fun markOfficial(list: CustomList, by: User = admin) =
        setOfficial.handle(SetListOfficial(list.id!!, by.id!!, official = true)).getOrNull()!!

    fun reload(list: CustomList): CustomList? = p.lists.findById(list.id!!)
}
