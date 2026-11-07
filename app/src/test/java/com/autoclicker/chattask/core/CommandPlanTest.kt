package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPlanTest {
    @Test
    fun `work and rest plans alternate without drifting`() {
        val cycles = WorkRestScheduler(workMs = 1_000, restMs = 500, intervalMs = 100, firstDelayMs = 10)
        val work = cycles.workPlan(now = 50)
        assertEquals(1_050, work.endMs)
        assertEquals(60, work.nextFireMs)

        val rest = cycles.restPlan(work)
        assertEquals(1_550, rest.endMs)
        assertEquals(rest.endMs, rest.nextFireMs)
        assertTrue(cycles.loops)
        assertFalse(WorkRestScheduler(1_000, 0, 100, 10).loops)
    }
}
