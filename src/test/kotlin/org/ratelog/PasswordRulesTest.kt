package org.ratelog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fork: rules for new and changed passwords (signing in never parses a password). */
class PasswordRulesTest {

    private fun errorOf(value: String) = Password.parse(value).fold({ it }, { null })

    @Test
    fun `a new password needs at least 8 characters`() {
        assertEquals(ParseError.InvalidPassword, errorOf("   "))
        assertEquals(ParseError.PasswordTooShort, errorOf("a"))
        assertEquals(ParseError.PasswordTooShort, errorOf("1234567"))
        assertTrue(Password.parse("12345678").isRight())
    }

    @Test
    fun `a new password can be at most 72 bytes, the most BCrypt uses`() {
        assertTrue(Password.parse("x".repeat(72)).isRight())
        assertEquals(ParseError.PasswordTooLong, errorOf("x".repeat(73)))
        assertEquals(ParseError.PasswordTooLong, errorOf("é".repeat(37)), "counted in UTF-8 bytes")
    }

    @Test
    fun `length errors get their own message, others keep the form's`() {
        assertEquals("security.password.too.short", passwordErrorKey(ParseError.PasswordTooShort, "fallback"))
        assertEquals("security.password.too.long", passwordErrorKey(ParseError.PasswordTooLong, "fallback"))
        assertEquals("fallback", passwordErrorKey(ParseError.InvalidPassword, "fallback"))
    }
}
