package org.ratelog.customlist.order.web

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.customlist.CustomList
import org.ratelog.customlist.getlistdetail.GetListDetailHandler
import org.ratelog.customlist.getlistdetail.GetListDetailQuery
import org.ratelog.customlist.getlistdetail.ListDetail
import org.ratelog.customlist.order.ListOrderFixture
import org.ratelog.customlist.order.sort.ListSort
import org.ratelog.customlist.progress.GetListProgress
import org.ratelog.customlist.progress.ListFilter
import org.ratelog.customlist.progress.web.ListProgressView
import org.ratelog.customlist.progress.web.toView
import org.ratelog.test.UserFactory
import org.ratelog.user.User
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.ModelAndView

/** ListOrderInterceptor on /list/{id}: `listOrder`, the order of `list.items`, and sort-keeping tab links. */
class ListOrderInterceptorTest {

    private val f = ListOrderFixture()
    private val owner = UserFactory.aUser(id = 1)
    private val other = UserFactory.aUser(id = 2)
    private val detailHandler = GetListDetailHandler(f.lists, f.p.movies, f.p.shows)
    private var viewer: User? = owner
    private val interceptor = ListOrderInterceptor(f.options, f.p.progressRepository) { viewer }

    private lateinit var list: CustomList

    private fun ranked(): CustomList {
        val (list, _) = f.list()
        val heat = f.p.movies.save(org.ratelog.test.MovieFactory.aMovie(originalTitle = "Heat", releaseYear = 1995))
        val alien = f.p.movies.save(org.ratelog.test.MovieFactory.aMovie(originalTitle = "Alien", releaseYear = 1979))
        val coco = f.p.movies.save(org.ratelog.test.MovieFactory.aMovie(originalTitle = "Coco", releaseYear = 2017))
        f.p.add(list, heat, alien, coco)
        f.p.rate(owner.id!!, coco, 9.0)
        f.p.rate(owner.id!!, heat, 7.0)
        this.list = list
        return list
    }

    private fun run(
        viewerUser: User = owner,
        params: Map<String, String> = emptyMap(),
        view: String = "list-detail",
        withProgress: Boolean = true,
    ): ModelAndView {
        viewer = viewerUser
        val detail = detailHandler.handle(GetListDetailQuery(list.id!!, viewerUser.id)).getOrNull()!!
        val mav = ModelAndView(view).apply {
            addObject("list", detail)
            addObject("isOwner", viewerUser.id == detail.userId)
            if (withProgress) {
                val progress = f.p.handler.handle(GetListProgress(list.id!!, viewerUser.id!!)).getOrNull()!!
                addObject("listProgress", progress.toView(ListFilter.parse(params["show"]), tick = params["mark"] == "1"))
            }
        }
        val request = MockHttpServletRequest("GET", "/list/${list.id!!.value}").apply { params.forEach { (k, v) -> setParameter(k, v) } }
        interceptor.postHandle(request, MockHttpServletResponse(), Any(), mav)
        return mav
    }

    private fun ModelAndView.order() = model["listOrder"] as ListOrderView
    private fun ModelAndView.titles() = (model["list"] as ListDetail).items.map { it.title }

    @Test
    fun `a list is numbered by default and shown in list order`() {
        ranked()
        val mav = run()

        assertTrue(mav.order().numbered)
        assertNull(mav.order().regionClass)
        assertEquals(ListSort.LIST, mav.order().sort)
        assertEquals(listOf("Heat", "Alien", "Coco"), mav.titles())
    }

    @Test
    fun `a plain list gets the class that hides ranks`() {
        ranked()
        f.options.setNumbered(list.id!!, false)

        val view = run().order()

        assertFalse(view.numbered)
        assertEquals("is-plain-list", view.regionClass)
    }

    @Test
    fun `sort arranges the items, and the tabs and tick-off links keep it`() {
        ranked()
        val mav = run(params = mapOf("sort" to "title", "show" to "unseen"))

        assertEquals(listOf("Alien", "Coco", "Heat"), mav.titles())
        val progress = mav.model["listProgress"] as ListProgressView
        val id = list.id!!.value
        assertEquals(listOf("/list/$id?sort=title", "/list/$id?show=unseen&sort=title", "/list/$id?show=seen&sort=title"), progress.tabs.map { it.href })
        assertEquals("/list/$id?show=unseen&mark=1&sort=title", progress.tickHref)
        assertEquals("/list/$id?show=unseen&sort=title", progress.selfHref)
        val links = mav.order().sortLinks.associateBy { it.labelKey }
        assertEquals("/list/$id?show=unseen", links.getValue("list.sort.list").href, "sort links keep the tab")
        assertEquals("/list/$id?show=unseen&sort=newest", links.getValue("list.sort.newest").href)
        assertTrue(links.getValue("list.sort.title").active)
    }

    @Test
    fun `your rating sorts by the viewer's own scores`() {
        ranked()
        assertEquals(listOf("Coco", "Heat", "Alien"), run(params = mapOf("sort" to "rating")).titles())
        assertEquals(listOf("Heat", "Alien", "Coco"), run(viewerUser = other, params = mapOf("sort" to "rating")).titles(), "nothing rated: list order")
    }

    @Test
    fun `list order leaves the progress links alone, and an unknown sort is list order`() {
        ranked()
        val before = run(params = mapOf("sort" to "nonsense"))

        assertEquals(ListSort.LIST, before.order().sort)
        assertEquals("/list/${list.id!!.value}?show=unseen", (before.model["listProgress"] as ListProgressView).tabs[1].href)
    }

    @Test
    fun `reorder mode is the owner's, ignores the sort and marks the region`() {
        ranked()
        val owned = run(params = mapOf("reorder" to "1", "sort" to "title"))
        assertTrue(owned.order().reordering)
        assertEquals("is-reordering", owned.order().regionClass)
        assertEquals(ListSort.LIST, owned.order().sort)
        assertEquals(listOf("Heat", "Alien", "Coco"), owned.titles())

        f.lists.save(f.lists.findById(list.id!!)!!.copy(isPublic = true))
        val notOwner = run(viewerUser = other, params = mapOf("reorder" to "1"))
        assertFalse(notOwner.order().reordering)
        assertFalse(notOwner.order().canReorder, "no Edit order button for anyone else, admins included")
    }

    @Test
    fun `a single title can't be reordered`() {
        val (one, _) = f.list("Only")
        list = one
        val view = run(params = mapOf("reorder" to "1")).order()
        assertFalse(view.canReorder)
        assertFalse(view.reordering)
    }

    @Test
    fun `other views and error pages are left alone`() {
        ranked()
        val other = run(view = "profile")
        assertNull(other.model["listOrder"])

        val error = ModelAndView("list-detail").apply { addObject("error", "list.not.found") }
        interceptor.postHandle(MockHttpServletRequest(), MockHttpServletResponse(), Any(), error)
        assertNull(error.model["listOrder"])
    }

    @Test
    fun `without progress (no viewer marks) it still sorts`() {
        ranked()
        val mav = run(params = mapOf("sort" to "reverse"), withProgress = false)
        assertEquals(listOf("Coco", "Alien", "Heat"), mav.titles())
        assertSame(null, mav.model["listProgress"])
    }

    @Test
    fun `withSort and href build the links`() {
        assertEquals("/list/1?sort=title", ListOrderView.withSort("/list/1", ListSort.TITLE))
        assertEquals("/list/1?show=seen&sort=title", ListOrderView.withSort("/list/1?show=seen", ListSort.TITLE))
        assertEquals("/list/1?show=seen", ListOrderView.withSort("/list/1?show=seen", ListSort.LIST))
        assertEquals("/list/1?sort=added", ListOrderView.withSort("/list/1?sort=added", ListSort.TITLE), "never twice")
        assertEquals("/list/1", ListOrderView.href("/list/1", null, false, ListSort.LIST))
        assertEquals("/list/1?show=unseen&mark=1&sort=oldest", ListOrderView.href("/list/1", "unseen", true, ListSort.OLDEST))
        assertEquals("/list/1/item/9/move", ListOrderView.of(1, true, true, false, ListSort.LIST, 3).moveHref(9))
        assertEquals("/list/1?reorder=1", ListOrderView.of(1, true, true, false, ListSort.LIST, 3).reorderHref)
    }
}
