package org.ratelog.system

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FeatureFlagsAdviceTest {
    @Test
    fun `exposes the tags switch to templates`() {
        assertFalse(FeatureFlagsAdvice(tagsEnabled = false).tagsEnabled())
        assertTrue(FeatureFlagsAdvice(tagsEnabled = true).tagsEnabled())
    }

    @Test
    fun `exposes the people switch to templates, off by default`() {
        assertFalse(FeatureFlagsAdvice(tagsEnabled = false).peopleEnabled())
        assertTrue(FeatureFlagsAdvice(tagsEnabled = false, peopleEnabled = true).peopleEnabled())
    }
}
