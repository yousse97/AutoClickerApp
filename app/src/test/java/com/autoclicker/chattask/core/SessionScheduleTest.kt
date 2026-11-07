package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class SessionScheduleTest {
    private val interval = 610_000L

    private fun plan() = SessionSchedule.create(0, 3_000, interval)

    @Test
    fun `jitter stays within the configured band on both sides`() {
        val policy = JitterPolicy(2, 4)
        val random = Random(1)
        val samples = List(10_000) { policy.nextMs(random) }
        assertTrue(samples.all { abs(it) in 2_000..4_000 })
        assertTrue(samples.any { it > 0 })
        assertTrue(samples.any { it < 0 })
    }

    @Test
    fun `jitter does not accumulate across a full session`() {
        val policy = JitterPolicy(2, 4)
        val random = Random(7)
        var schedule = plan()
        repeat(129) {
            val now = schedule.nextFireMs
            schedule = schedule.advance(now, policy.nextMs(random))
        }
        assertEquals(3_000 + 129 * interval, schedule.anchorMs)
        assertTrue(abs(schedule.nextFireMs - schedule.anchorMs) <= 4_000)
    }

    @Test
    fun `slots missed during sleep are skipped rather than burst`() {
        val slept = 3 * interval + 100_000
        val next = plan().advance(slept, 0)
        assertTrue(next.nextFireMs > slept)
        assertTrue(next.nextFireMs - slept <= interval)
        assertFalse(next.isDue(slept))
    }

    @Test
    fun `next send is never scheduled in the past`() {
        val schedule = plan()
        val next = schedule.advance(schedule.anchorMs + interval - 1, -4_000)
        assertTrue(next.nextFireMs >= schedule.anchorMs + interval - 1 + SessionSchedule.MIN_GAP_MS)
    }

    @Test
    fun `shifting keeps the time until the next send`() {
        val schedule = plan()
        val shifted = schedule.shiftedBy(60_000)
        assertEquals(schedule.nextFireMs - 0, shifted.nextFireMs - 60_000)
    }
}
