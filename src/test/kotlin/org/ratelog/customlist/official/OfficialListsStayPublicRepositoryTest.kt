package org.ratelog.customlist.official

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.customlist.official.mark.SetListOfficial
import org.ratelog.customlist.update.UpdateListCommand
import org.ratelog.customlist.update.UpdateListHandler

/** "Official implies public" through upstream's edit form handler, which knows nothing about official lists. */
class OfficialListsStayPublicRepositoryTest {

    private val f = OfficialListsFixture()
    private val editForm = UpdateListHandler(f.lists)

    @Test
    fun `the owner's edit form can rename an official list but not make it private`() {
        val list = f.list(f.admin, "AFI 100")
        f.markOfficial(list)

        val result = editForm.handle(UpdateListCommand(list.id!!, f.admin.id!!, "AFI's 100 Years", isPublic = false))

        assertTrue(result.isRight())
        val saved = f.reload(list)!!
        assertEquals("AFI's 100 Years", saved.name.value)
        assertTrue(saved.isPublic, "still public while official")
        assertTrue(f.official.isOfficial(list.id!!))
    }

    @Test
    fun `after un-marking, the owner can make it private again`() {
        val list = f.list(f.admin, "AFI 100")
        f.markOfficial(list)
        f.setOfficial.handle(SetListOfficial(list.id!!, f.admin.id!!, official = false))

        editForm.handle(UpdateListCommand(list.id!!, f.admin.id!!, "AFI 100", isPublic = false))

        assertFalse(f.reload(list)!!.isPublic)
    }

    @Test
    fun `ordinary lists are saved exactly as given`() {
        val list = f.list(f.andre, "Mine", isPublic = true)

        editForm.handle(UpdateListCommand(list.id!!, f.andre.id!!, "Mine", isPublic = false))

        assertFalse(f.reload(list)!!.isPublic)
    }
}
