package org.ratelog.apitoken

import org.springframework.stereotype.Component

/**
 * The "API guide for AI assistants" shown on /settings/api and served at /settings/api/guide.md.
 * The text lives in `src/main/resources/api-guide.md` (keep it in step with docs/api.md); `{{BASE_URL}}` is replaced
 * with the address the site was reached at. It never contains a token.
 */
@Component
class ApiGuide {
    private val template: String = ApiGuide::class.java.getResourceAsStream("/api-guide.md")!!
        .use { it.readBytes().toString(Charsets.UTF_8) }.replace("\r\n", "\n")

    fun render(baseUrl: String): String = template.replace("{{BASE_URL}}", baseUrl.trimEnd('/'))
}
