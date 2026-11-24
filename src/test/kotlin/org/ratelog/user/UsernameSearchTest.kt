package org.ratelog.user

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.Username
import org.springframework.data.jdbc.repository.query.Query

/** User search ("Search users" on Community): case-insensitive, and `_` / `%` are literal characters. */
class UsernameSearchTest {

    @Test
    fun `the LIKE pattern wraps the text and escapes the wildcards and the escape character`() {
        assertEquals("%mari%", UsernameSearch.likePattern("mari"))
        assertEquals("%a!_b%", UsernameSearch.likePattern("a_b"))
        assertEquals("%50!%!!%", UsernameSearch.likePattern("50%!"))
    }

    @Test
    fun `the query lower-cases both sides and declares the escape character`() {
        val sql = UserDAO::class.java.getMethod("findByUsernameContaining", String::class.java)
            .getAnnotation(Query::class.java).value

        assertTrue(sql.contains("LOWER(username) LIKE LOWER(:pattern) ESCAPE '!'"), sql)
    }

    @Test
    fun `the repository sends the escaped pattern, for both search variants`() {
        val dao: UserDAO = mock()
        whenever(dao.findByUsernameContaining("%Mari!_s%")).thenReturn(
            listOf(UserEntity(7, "marisol_s", "m@example.com", "x", 0, "en", "en", "USER")),
        )
        val repository = UserRepositoryImpl(dao, mock())

        assertEquals("marisol_s", repository.findByUsernameContaining(Username("Mari_s")).single().username.value)
        assertEquals(1, repository.findByUsernameContaining(Username("Mari_s"), User.Id(1)).size)
        verify(dao, times(2)).findByUsernameContaining("%Mari!_s%")
    }
}
