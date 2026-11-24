package org.ratelog.system

import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Fork: switches for features that stay in the code but can be hidden in the web UI.
 * `ratelog.features.tags` (env `RATELOG_FEATURES_TAGS`, default false): tags are hidden from the nav, title pages,
 * Top-page filters and the profile while the user decides whether they want them. The /tags pages and the API keep working.
 * `ratelog.features.people` (env `RATELOG_FEATURES_PEOPLE`, default false): cast and crew names on title pages link to
 * `/person/{id}`. Off until person pages exist, so no dead links ship; the names carry `data-person-id` either way.
 */
@ControllerAdvice
class FeatureFlagsAdvice(
    @Value("\${ratelog.features.tags:false}") private val tagsEnabled: Boolean,
    @Value("\${ratelog.features.people:false}") private val peopleEnabled: Boolean = false,
) {
    @ModelAttribute("tagsEnabled")
    fun tagsEnabled(): Boolean = tagsEnabled

    @ModelAttribute("peopleEnabled")
    fun peopleEnabled(): Boolean = peopleEnabled
}
