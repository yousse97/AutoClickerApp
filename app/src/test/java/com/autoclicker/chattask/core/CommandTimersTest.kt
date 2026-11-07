package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CommandTimersTest {
    private fun day(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 3, 10, hour, minute)

    private val allDay = CommandEntry("a", intervalSec = 60)
    private val daytime = CommandEntry("b", 120, allDay = false, startMinute = 9 * 60, endMinute = 17 * 60)
    private val overnight = CommandEntry("c", 120, allDay = false, startMinute = 22 * 60, endMinute = 6 * 60)

    private fun timers(vararg commands: CommandEntry) = CommandTimers.of(commands.toList(), firstDelayMs = 1_000)

    @Test
    fun `each command keeps its own interval`() {
        val fast = allDay
        val slow = CommandEntry("slow", intervalSec = 150)
        val list = timers(fast, slow)
        list.refresh(0, day(12))
        assertEquals(listOf(0, 1), list.dueOrder(1_000))

        list.markDone(0, 1_000, 0)
        list.markDone(1, 1_000, 0)
        assertEquals(emptyList<Int>(), list.dueOrder(60_000))
        assertEquals(listOf(0), list.dueOrder(61_000))
        assertEquals(listOf(0, 1), list.dueOrder(151_000))
    }

    @Test
    fun `jitter applies to each command separately`() {
        val list = timers(allDay, CommandEntry("two", intervalSec = 60))
        list.refresh(0, day(12))
        list.markDone(0, 1_000, 3_000)
        list.markDone(1, 1_000, -2_000)
        assertEquals(64_000L, list.nextInMs(0, 0))
        assertEquals(59_000L, list.nextInMs(1, 0))
    }

    @Test
    fun `two due commands go out earliest first`() {
        val list = timers(allDay, CommandEntry("two", intervalSec = 60))
        list.refresh(0, day(12))
        list.markDone(0, 1_000, 2_000)
        list.markDone(1, 1_000, -2_000)
        assertEquals(listOf(1, 0), list.dueOrder(100_000))
    }

    @Test
    fun `a daily window that crosses midnight stays active past it`() {
        assertTrue(overnight.isActive(day(23, 59)))
        assertTrue(overnight.isActive(day(0, 0)))
        assertTrue(overnight.isActive(day(5, 59)))
        assertFalse(overnight.isActive(day(6, 0)))
        assertFalse(overnight.isActive(day(21, 59)))
    }

    @Test
    fun `a command outside its hours stays idle while others continue`() {
        val list = timers(allDay, daytime)
        list.refresh(0, day(20))
        assertEquals(CommandState.RUNNING, list.stateOf(0))
        assertEquals(CommandState.IDLE, list.stateOf(1))
        assertNull(list.nextInMs(1, 0))
        assertFalse(list.allIdle)
        assertEquals(listOf(0), list.dueOrder(5_000))
    }

    @Test
    fun `idle only when every command is outside its hours`() {
        val list = timers(daytime, overnight)
        list.refresh(0, day(20))
        assertTrue(list.allIdle)
        list.refresh(1, day(22))
        assertFalse(list.allIdle)
    }

    @Test
    fun `entering its hours gives a command a fresh first send`() {
        val list = timers(daytime)
        list.refresh(0, day(8))
        assertTrue(list.allIdle)
        list.refresh(500_000, day(9))
        assertEquals(1_000L, list.nextInMs(0, 500_000))
    }

    @Test
    fun `a held command stays pending once and sends once`() {
        val list = timers(allDay)
        list.refresh(0, day(12))
        repeat(50) { list.markHeld(list.dueOrder(1_000L + it * 60_000)) }
        assertEquals(CommandState.PENDING, list.stateOf(0))
        assertEquals(listOf(0), list.dueOrder(400_000))

        list.markDone(0, 400_000, 0)
        assertEquals(CommandState.RUNNING, list.stateOf(0))
        assertEquals(emptyList<Int>(), list.dueOrder(400_001))
        assertEquals(60_000L, list.nextInMs(0, 400_000))
    }

    @Test
    fun `a pending send is skipped when its hours end`() {
        val list = timers(daytime)
        list.refresh(0, day(16, 59))
        list.markHeld(list.dueOrder(1_000))
        assertEquals(listOf(0), list.refresh(2_000, day(17)))
        assertEquals(CommandState.IDLE, list.stateOf(0))
        assertEquals(emptyList<Int>(), list.dueOrder(3_000))
        assertEquals(emptyList<Int>(), list.refresh(4_000, day(17, 1)))
    }

    @Test
    fun `leaving its hours with nothing pending skips nothing`() {
        val list = timers(daytime)
        list.refresh(0, day(16))
        assertEquals(emptyList<Int>(), list.refresh(1, day(17)))
    }

    @Test
    fun `timers round trip onto a different clock base`() {
        val list = timers(allDay, daytime)
        list.refresh(0, day(12))
        list.markDone(0, 1_000, 0)
        list.markHeld(listOf(1))

        val restored = timers(allDay, daytime)
        restored.restore(list.offsets(1_000), 9_001_000)
        assertEquals(list.nextInMs(0, 1_000), restored.nextInMs(0, 9_001_000))
        assertEquals(CommandState.PENDING, restored.stateOf(1))
    }

    @Test
    fun `saved timers for a different command list are ignored`() {
        val restored = timers(allDay)
        restored.restore(listOf(null, null), 0)
        assertTrue(restored.allIdle)
    }

    @Test
    fun `pausing shifts every timer`() {
        val list = timers(allDay)
        list.refresh(0, day(12))
        list.shiftBy(50_000)
        assertEquals(51_000L, list.nextInMs(0, 0))
    }

    @Test
    fun `the next command is the one firing soonest`() {
        val list = timers(allDay, CommandEntry("two", intervalSec = 30))
        list.refresh(0, day(12))
        list.markDone(0, 1_000, 0)
        list.markDone(1, 1_000, 0)
        assertEquals(1, list.nextIndex())
    }

    @Test
    fun `a daily command without times never starts and has no timer`() {
        val wall = java.time.LocalDateTime.of(2026, 1, 1, 12, 0)
        val timers = CommandTimers.of(listOf(CommandEntry("a", 610, allDay = false)), 3_000)
        assertEquals(emptyList<Int>(), timers.refresh(0, wall))
        assertEquals(true, timers.allIdle)
        assertEquals(emptyList<Int>(), timers.dueOrder(1_000_000))
    }

    @Test
    fun `edits keep timers by text then by row, and a new row starts fresh`() {
        val a = CommandEntry("a", 610)
        val b = CommandEntry("b", 610)
        val offsetA = TimerOffset(0, 100_000, false)
        val offsetB = TimerOffset(0, 200_000, true)
        val old = listOf(a, b)
        val offsets = listOf(offsetA, offsetB)
        assertEquals(listOf(offsetB, offsetA), CommandTimers.carryOffsets(old, offsets, listOf(b, a)))
        assertEquals(offsets, CommandTimers.carryOffsets(old, offsets, listOf(a.copy(text = "edited"), b)))
        assertEquals(
            listOf(offsetA, offsetB, TimerOffset(610_000, 610_000, false)),
            CommandTimers.carryOffsets(old, offsets, old + CommandEntry("c", 610)),
        )
    }

    @Test
    fun `a shorter interval pulls the next send in, a longer one leaves it`() {
        val old = listOf(CommandEntry("a", 610))
        val offsets = listOf(TimerOffset(0, 300_000, false))
        val shorter = CommandTimers.carryOffsets(old, offsets, listOf(CommandEntry("a", 60)))
        val longer = CommandTimers.carryOffsets(old, offsets, listOf(CommandEntry("a", 900)))
        assertEquals(60_000L, shorter[0]?.nextInMs)
        assertEquals(300_000L, longer[0]?.nextInMs)
    }
}
