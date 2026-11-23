package org.ratelog.customlist.official

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.ratelog.customlist.delete.DeleteListCommand
import org.ratelog.customlist.delete.DeleteListHandler

/** Deleting an official list removes its mark (the FK cascades) and it disappears from every official listing. */
class OfficialListDeleteTest {

    private val f = OfficialListsFixture()

    @Test
    fun `the owner deletes an official list and it is gone everywhere`() {
        val keep = f.list(f.admin, "Keep")
        val gone = f.list(f.admin, "Gone")
        f.markOfficial(keep)
        f.markOfficial(gone)

        val result = DeleteListHandler(f.lists).handle(DeleteListCommand(gone.id!!, f.admin.id!!))

        assertTrue(result.isRight())
        assertNull(f.reload(gone))
        assertFalse(f.official.isOfficial(gone.id!!))
        assertEquals(setOf(keep.id), f.official.markedIds())
        assertEquals(listOf("Keep"), f.getOfficial.handle(f.andre.id!!).map { it.list.name })
        assertEquals(listOf("Keep"), f.listsPage.handle(org.ratelog.customlist.browse.GetListsPage(f.andre.id!!)).official.map { it.name })
    }

    @Test
    fun `someone else can't delete an official list`() {
        val list = f.list(f.admin, "AFI 100")
        f.markOfficial(list)

        DeleteListHandler(f.lists).handle(DeleteListCommand(list.id!!, f.andre.id!!))

        assertTrue(f.official.isOfficial(list.id!!))
    }

    @Test
    fun `the migration declares the cascade and one row per list`() {
        val sql = javaClass.getResource("/db/migration/V1120__create_official_lists.sql")!!.readText()
            .lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")

        assertTrue(Regex("""list_id\s+BIGINT\s+PRIMARY\s+KEY\s+REFERENCES\s+custom_lists\s*\(\s*id\s*\)\s+ON\s+DELETE\s+CASCADE""", RegexOption.IGNORE_CASE)
            .containsMatchIn(sql), sql)
        assertTrue(sql.contains("CREATE TABLE official_lists"))
    }
}
