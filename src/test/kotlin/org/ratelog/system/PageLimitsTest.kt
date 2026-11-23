package org.ratelog.system

import arrow.core.left
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.ratelog.test.UserFactory
import org.ratelog.user.profile.GetProfile
import org.ratelog.user.profile.ProfileController
import org.ratelog.user.profile.ProfileHandler
import org.ratelog.user.profile.ProfileHandlerError
import org.springframework.ui.ExtendedModelMap

class PageLimitsTest {

    @Test
    fun `limits are clamped to 1 to 1000`() {
        assertEquals(1, PageLimits.clamp(-1))
        assertEquals(1, PageLimits.clamp(0))
        assertEquals(10, PageLimits.clamp(10))
        assertEquals(1000, PageLimits.clamp(1000))
        assertEquals(1000, PageLimits.clamp(Int.MAX_VALUE))
    }

    @Test
    fun `the profile page passes a clamped limit to the query and the load-more link`() {
        val handler: ProfileHandler = mock()
        whenever(handler.handle(any())).thenReturn(ProfileHandlerError.UserNotFound.left())
        val controller = ProfileController(handler)

        controller.profilePage(UserFactory.aUser(id = 1), 1, -5, ExtendedModelMap())
        controller.profilePage(UserFactory.aUser(id = 1), 1, Int.MAX_VALUE, ExtendedModelMap())

        val captor = argumentCaptor<GetProfile>()
        verify(handler, org.mockito.kotlin.times(2)).handle(captor.capture())
        assertEquals(listOf(1, 1000), captor.allValues.map { it.limit })
    }
}
