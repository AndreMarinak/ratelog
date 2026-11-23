package org.ratelog.config

import arrow.core.getOrElse
import org.ratelog.annotations.CurrentUser
import org.ratelog.config.getgeneralconfig.GetGeneralConfigHandler
import org.ratelog.config.getgeneralconfig.GetGeneralConfigQuery
import org.ratelog.config.updategeneralconfig.UpdateGeneralConfigCommand
import org.ratelog.config.updategeneralconfig.UpdateGeneralConfigHandler
import org.ratelog.config.updategeneralconfig.UpdateGeneralConfigHandlerError
import org.ratelog.user.User
import org.ratelog.user.listusers.ListUsersHandler
import org.ratelog.user.listusers.ListUsersQuery
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class ConfigurationController(
    private val listUsersHandler: ListUsersHandler,
    private val getGeneralConfigHandler: GetGeneralConfigHandler,
    private val updateGeneralConfigHandler: UpdateGeneralConfigHandler,
) {

    @GetMapping("/admin/configuration")
    fun dashboard(
        @CurrentUser currentUser: User,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        val users = listUsersHandler.handle(ListUsersQuery(currentUser))
            .mapLeft { "admin.error.forbidden" }
            .fold(
                { msg ->
                    redirectAttributes.addFlashAttribute("error", msg)
                    emptyList()
                },
                { it }
            )
        val generalConfigs = getGeneralConfigHandler.handle(GetGeneralConfigQuery(currentUser))
            .getOrElse { emptyList() }
        val tmdbApiKeyValue = generalConfigs
            .firstOrNull { it.key == ConfigKey.TMDB_API_KEY }
            ?.value
            .orEmpty()
        model.addAttribute("users", users)
        model.addAttribute("currentUserId", currentUser.id!!.value)
        model.addAttribute("currentUserRole", currentUser.role.name)
        // Fork: secrets never go back to the browser; the page only shows whether a key is set and its last characters.
        model.addAttribute("tmdbApiKeyConfigured", tmdbApiKeyValue.isNotEmpty())
        model.addAttribute("tmdbApiKeyHint", maskedHint(tmdbApiKeyValue))
        return "admin/configuration"
    }

    @PostMapping("/admin/configuration/general")
    fun updateGeneral(
        @CurrentUser currentUser: User,
        @RequestParam("key") keyValue: String,
        @RequestParam("value") value: String,
        redirectAttributes: RedirectAttributes,
    ): String {
        if (!currentUser.role.isAdminLike) {
            redirectAttributes.addFlashAttribute("error", "admin.error.forbidden")
            return "redirect:/admin/configuration"
        }
        // Fork: this form only sets the TMDB key; other settings (the remember-me signing key) are not editable here.
        val key = ConfigKey.parse(keyValue).getOrNull()?.takeIf { it in FORM_EDITABLE_KEYS }
        if (key == null) {
            redirectAttributes.addFlashAttribute("error", "config.general.error.invalid.key")
            return "redirect:/admin/configuration"
        }
        // Fork: the field starts empty (the stored key is never sent to the page), so empty means "keep the current key".
        if (value.isBlank()) {
            redirectAttributes.addFlashAttribute("success", "security.config.key.unchanged")
            return "redirect:/admin/configuration"
        }
        val result = UpdateGeneralConfigCommand(key = key, value = value.trim())
            .let(updateGeneralConfigHandler::handle)
            .mapLeft(::mapError)
        return result.fold(
            { msg ->
                redirectAttributes.addFlashAttribute("error", msg)
                "redirect:/admin/configuration"
            },
            {
                redirectAttributes.addFlashAttribute("success", "config.general.success.saved")
                "redirect:/admin/configuration"
            }
        )
    }

    companion object {
        val FORM_EDITABLE_KEYS = setOf(ConfigKey.TMDB_API_KEY)
        private const val VISIBLE_SUFFIX = 4

        /** "…abcd" for a stored secret: only the last 4 characters, and nothing at all for a value too short to hide. */
        fun maskedHint(secret: String): String? =
            secret.trim().takeIf { it.length >= VISIBLE_SUFFIX * 2 }?.let { "…" + it.takeLast(VISIBLE_SUFFIX) }
    }

    private fun mapError(error: UpdateGeneralConfigHandlerError): String = when (error) {
        UpdateGeneralConfigHandlerError.EmptyValue -> "config.general.error.empty.value"
        UpdateGeneralConfigHandlerError.ValueTooLong -> "config.general.error.value.too.long"
    }
}
