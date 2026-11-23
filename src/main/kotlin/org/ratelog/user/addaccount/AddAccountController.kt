package org.ratelog.user.addaccount

import arrow.core.getOrElse
import jakarta.servlet.http.HttpServletRequest
import org.ratelog.Email
import org.ratelog.Password
import org.ratelog.Role
import org.ratelog.Username
import org.ratelog.annotations.CurrentUser
import org.ratelog.passwordErrorKey
import org.ratelog.user.User
import org.ratelog.user.UserDetailsService
import org.ratelog.user.create.CreateUserCommand
import org.ratelog.user.create.CreateUserHandler
import org.ratelog.user.create.CreateUserHandlerError
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

/**
 * Fork: "Add account" for managers (requested 2026-11-23). The new account is always a plain USER, whatever the form
 * sends; the same username, email and password rules as the admin's create form apply (CreateUserHandler).
 * Admins can use it too, but they keep the admin page for everything else.
 */
@Controller
class AddAccountController(
    private val handler: CreateUserHandler,
) {

    @GetMapping(PATH)
    fun page(@CurrentUser user: User): String {
        if (!user.role.canCreateAccounts) return "redirect:/profile"
        return "account-add"
    }

    @PostMapping(PATH)
    fun create(
        @CurrentUser user: User,
        @RequestParam("username") username: String,
        @RequestParam("email") email: String,
        @RequestParam("password") password: String,
        request: HttpServletRequest,
        redirectAttributes: RedirectAttributes,
    ): String {
        val back = "redirect:$PATH"
        val parsedUsername = Username.parse(username.trim()).getOrElse {
            return fail(redirectAttributes, "admin.error.invalid.username", back)
        }
        val parsedEmail = Email.parse(email.trim()).getOrElse {
            return fail(redirectAttributes, "admin.error.invalid.email", back)
        }
        val parsedPassword = Password.parse(password).getOrElse { error ->
            return fail(redirectAttributes, passwordErrorKey(error, "admin.error.invalid.password"), back)
        }

        return CreateUserCommand(
            currentUser = user,
            username = parsedUsername,
            email = parsedEmail,
            password = parsedPassword,
            lang = UserDetailsService.resolve(request),
            role = Role.USER,
        ).let(handler::handle)
            .fold(
                { error -> fail(redirectAttributes, errorKey(error), back) },
                {
                    redirectAttributes.addFlashAttribute("createdUsername", parsedUsername.value)
                    back
                },
            )
    }

    private fun fail(redirectAttributes: RedirectAttributes, key: String, back: String): String {
        redirectAttributes.addFlashAttribute("error", key)
        return back
    }

    private fun errorKey(error: CreateUserHandlerError): String = when (error) {
        CreateUserHandlerError.UsernameAlreadyExists -> "admin.error.username.exists"
        CreateUserHandlerError.EmailAlreadyExists -> "admin.error.email.exists"
        CreateUserHandlerError.Forbidden,
        CreateUserHandlerError.CannotPromoteToSuperadmin,
        CreateUserHandlerError.CannotCreateAdmin,
        CreateUserHandlerError.ManagerCanOnlyCreateUsers -> "admin.error.forbidden"
    }

    companion object {
        const val PATH = "/accounts/new"
    }
}
