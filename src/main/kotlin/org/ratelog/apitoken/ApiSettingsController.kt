package org.ratelog.apitoken

import org.ratelog.annotations.CurrentUser
import org.ratelog.apitoken.create.CreateApiTokenCommand
import org.ratelog.apitoken.create.CreateApiTokenError
import org.ratelog.apitoken.create.CreateApiTokenHandler
import org.ratelog.apitoken.revoke.RevokeApiTokenCommand
import org.ratelog.apitoken.revoke.RevokeApiTokenHandler
import org.ratelog.user.User
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.servlet.support.ServletUriComponentsBuilder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class ApiTokenView(
    val id: Long,
    val name: String,
    val prefix: String,
    val createdAt: String,
    val lastUsedAt: String?,
)

@Controller
class ApiSettingsController(
    private val apiTokenRepository: ApiTokenRepository,
    private val createHandler: CreateApiTokenHandler,
    private val revokeHandler: RevokeApiTokenHandler,
    private val apiGuide: ApiGuide,
) {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

    @GetMapping("/settings/api")
    fun page(@CurrentUser user: User, model: Model, request: HttpServletRequest): String {
        val tokens = apiTokenRepository.findByUserId(user.id!!).map {
            ApiTokenView(
                id = it.id!!.value,
                name = it.name,
                prefix = it.tokenPrefix,
                createdAt = dateFormat.format(Instant.ofEpochMilli(it.createdAtEpochMs)),
                lastUsedAt = it.lastUsedAtEpochMs?.let { ms -> dateFormat.format(Instant.ofEpochMilli(ms)) },
            )
        }
        model.addAttribute("tokens", tokens)
        model.addAttribute("apiGuide", apiGuide.render(baseUrl(request)))
        return "api-settings"
    }

    /** The same guide as a Markdown download. */
    @GetMapping("/settings/api/guide.md", produces = ["text/markdown; charset=utf-8"])
    fun guide(request: HttpServletRequest): ResponseEntity<String> =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/markdown; charset=utf-8"))
            .body(apiGuide.render(baseUrl(request)))

    private fun baseUrl(request: HttpServletRequest): String =
        ServletUriComponentsBuilder.fromContextPath(request).build().toUriString()

    @PostMapping("/settings/api/tokens")
    fun create(
        @CurrentUser user: User,
        @RequestParam name: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        createHandler.handle(CreateApiTokenCommand(user.id!!, name)).fold(
            { error ->
                redirectAttributes.addFlashAttribute(
                    "error",
                    when (error) {
                        CreateApiTokenError.InvalidName -> "api.token.error.name"
                        CreateApiTokenError.TokenLimitExceeded -> "api.token.error.limit"
                    }
                )
            },
            { created ->
                redirectAttributes.addFlashAttribute("newToken", created.plainToken)
                redirectAttributes.addFlashAttribute("newTokenName", created.token.name)
            }
        )
        return "redirect:/settings/api"
    }

    @PostMapping("/settings/api/tokens/{id}/revoke")
    fun revoke(
        @CurrentUser user: User,
        @PathVariable id: Long,
        redirectAttributes: RedirectAttributes,
    ): String {
        revokeHandler.handle(RevokeApiTokenCommand(user.id!!, ApiToken.Id(id))).fold(
            { redirectAttributes.addFlashAttribute("error", "api.token.error.not.found") },
            { redirectAttributes.addFlashAttribute("success", "api.token.revoked") }
        )
        return "redirect:/settings/api"
    }
}
