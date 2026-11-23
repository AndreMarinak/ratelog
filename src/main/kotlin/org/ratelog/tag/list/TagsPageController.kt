package org.ratelog.tag.list

import org.ratelog.annotations.CurrentUser
import org.ratelog.tag.TagColor
import org.ratelog.tag.toView
import org.ratelog.user.User
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

data class TagColorOption(val name: String, val style: String)

@Controller
class TagsPageController(
    private val handler: ListTagsHandler,
) {
    @GetMapping("/tags")
    fun page(@CurrentUser user: User, model: Model): String {
        model.addAttribute("tags", handler.handle(ListTags(user.id!!)).map { it.tag.toView(it.titleCount) })
        model.addAttribute("colors", TagColor.entries.map { TagColorOption(it.name, "--tag-color: ${it.hex}") })
        return "tags"
    }
}
