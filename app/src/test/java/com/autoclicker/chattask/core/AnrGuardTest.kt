package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnrGuardTest {
    private val guard = AnrGuard(waitMs = 30_000, maxAttempts = 3)

    @Test
    fun `only a dialog naming WOLF counts, in either language`() {
        assertTrue(AnrGuard.namesWolf("WOLF isn't responding", "WOLF"))
        assertTrue(AnrGuard.namesWolf("WOLF لا يستجيب", "WOLF"))
        assertFalse(AnrGuard.namesWolf("Chrome isn't responding", "WOLF"))
        assertFalse(AnrGuard.namesWolf("WOLF keeps stopping", "WOLF"))
        assertFalse(AnrGuard.namesWolf(null, "WOLF"))
    }

    @Test
    fun `it waits 30 seconds before closing`() {
        assertEquals(AnrAction.SEEN, guard.observe(true, 0))
        assertEquals(AnrAction.NONE, guard.observe(true, 29_000))
        assertEquals(AnrAction.CLOSE, guard.observe(true, 30_000))
    }

    @Test
    fun `a dialog that goes away by itself closes nothing`() {
        guard.observe(true, 0)
        assertEquals(AnrAction.NONE, guard.observe(false, 10_000))
        assertEquals(AnrAction.SEEN, guard.observe(true, 11_000))
    }

    @Test
    fun `it gives up on the fourth dialog in a row`() {
        repeat(3) { round ->
            val start = round * 100_000L
            assertEquals(AnrAction.SEEN, guard.observe(true, start))
            assertEquals(AnrAction.CLOSE, guard.observe(true, start + 30_000))
            guard.observe(false, start + 31_000)
        }
        assertEquals(AnrAction.GIVE_UP, guard.observe(true, 400_000))
    }

    @Test
    fun `a good send clears the count`() {
        repeat(3) { round ->
            val start = round * 100_000L
            guard.observe(true, start)
            guard.observe(true, start + 30_000)
            guard.observe(false, start + 31_000)
        }
        guard.onRecovered()
        assertEquals(AnrAction.SEEN, guard.observe(true, 400_000))
    }
}
