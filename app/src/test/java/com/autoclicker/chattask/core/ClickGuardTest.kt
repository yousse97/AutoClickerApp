package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickGuardTest {
    @Test
    fun `a stuck button is only clicked again after its cooldown`() {
        val guard = ClickGuard(cooldownMs = 2_000)
        assertTrue(guard.allow("button", 0))
        assertFalse(guard.allow("button", 1_999))
        assertTrue(guard.allow("button", 2_000))
    }

    @Test
    fun `different buttons do not share a cooldown`() {
        val guard = ClickGuard()
        assertTrue(guard.allow("first", 0))
        assertTrue(guard.allow("second", 1))
    }

    @Test
    fun `global limit bounds clicks in the window and then recovers`() {
        val guard = ClickGuard(maxClicks = 3, windowMs = 10_000)
        val allowed = (0 until 10).count { guard.allow("button-$it", it * 100L) }
        assertEquals(3, allowed)
        assertTrue(guard.allow("later", 10_000))
    }
}
