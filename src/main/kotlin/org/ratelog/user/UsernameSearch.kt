package org.ratelog.user

/**
 * Fork: the LIKE pattern for "username contains ..." (`UserDAO.findByUsernameContaining`, which also lower-cases both
 * sides). `!` is the query's ESCAPE character, so a typed `_` or `%` matches only itself instead of any character(s).
 * Found 2026-11-23: searching "Mari" missed "marisol", and "a_b" also matched "axb". See docs/bugs-and-issues.md.
 */
object UsernameSearch {
    fun likePattern(text: String): String =
        "%" + text.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
}
