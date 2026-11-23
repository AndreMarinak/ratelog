package org.ratelog.import.letterboxd

import org.ratelog.annotations.CurrentUser
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.servlet.mvc.support.RedirectAttributes

@Controller
class LetterboxdImportController(
    private val runner: LetterboxdImportRunner,
) {

    @PostMapping("/import/letterboxd")
    fun importCsv(
        @CurrentUser user: User,
        @RequestParam("file") file: MultipartFile,
        redirectAttributes: RedirectAttributes,
    ): String {
        // Fork: straight to the profile page; "/profile" redirects again, which dropped these flash messages.
        val profile = "redirect:/profile/${user.id!!.value}"
        if (file.isEmpty) {
            redirectAttributes.addFlashAttribute("error", "error.import.empty")
            return profile
        }
        if (file.size > LetterboxdImportLimits.MAX_FILE_BYTES) {
            redirectAttributes.addFlashAttribute("error", "error.import.too.large")
            return profile
        }

        // Fork: parsed now, imported in the background (LetterboxdImportRunner).
        runner.start(user.id!!, file.bytes).fold(
            { error -> redirectAttributes.addFlashAttribute("error", errorKey(error)) },
            { redirectAttributes.addFlashAttribute("success", "import.letterboxd.started") },
        )
        return profile
    }

    private fun errorKey(error: LetterboxdImportError): String = when (error) {
        LetterboxdImportError.ParseError -> "error.import.parse"
        LetterboxdImportError.TooManyRows -> "error.import.too.many.rows"
        LetterboxdImportError.AlreadyRunning -> "error.import.running"
        LetterboxdImportError.Busy -> "error.import.busy"
    }
}
