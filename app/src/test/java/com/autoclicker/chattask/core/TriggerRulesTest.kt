package com.autoclicker.chattask.core

import com.autoclicker.chattask.ui.HomeWarning
import com.autoclicker.chattask.ui.PermissionState
import com.autoclicker.chattask.ui.homeWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TriggerRulesTest {
    private val triggers = listOf("phrase one", "عبارة ثانية")
    private val own = listOf("my reply")
    private val start = 1_000_000L
    private val noon = LocalDateTime.of(2026, 1, 15, 12, 0)

    private fun fresh(posted: Long, message: Long = 0, handled: Long = 0, now: Long = posted + 1_000) =
        NotificationAge.isFresh(posted, message, start, handled, now)

    @Test
    fun `a trigger matches inside longer text, across case and spelling`() {
        assertTrue(TriggerMatcher.matches(listOf("title", "Someone: PHRASE ONE now"), triggers, own))
        assertTrue(TriggerMatcher.matches(listOf("عبــارة ثانية"), triggers, own))
    }

    @Test
    fun `text without a trigger does not match`() {
        assertFalse(TriggerMatcher.matches(listOf("something else", null, ""), triggers, own))
        assertFalse(TriggerMatcher.matches(listOf("phrase"), triggers, own))
    }

    @Test
    fun `the reaction command never sets off a reply`() {
        assertFalse(TriggerMatcher.matches(listOf("phrase one my reply"), triggers, own))
        assertFalse(TriggerMatcher.matches(listOf("my reply"), listOf("reply"), own))
    }

    @Test
    fun `another line can still match when one is the app's own`() {
        assertTrue(TriggerMatcher.matches(listOf("my reply", "phrase one"), triggers, own))
    }

    @Test
    fun `a blank own text or trigger never matches everything`() {
        assertTrue(TriggerMatcher.matches(listOf("phrase one"), triggers, listOf("", "  ")))
        assertFalse(TriggerMatcher.matches(listOf("anything"), listOf("", " "), own))
    }

    @Test
    fun `notifications from before Start are ignored`() {
        assertFalse(fresh(start - 1))
        assertTrue(fresh(start))
    }

    @Test
    fun `an old message posted again after Start is ignored`() {
        assertFalse(fresh(start + 5_000, message = start - 60_000))
        assertTrue(fresh(start + 5_000, message = start + 4_000))
    }

    @Test
    fun `a notification arriving long after it was posted is too old`() {
        assertTrue(fresh(start, now = start + NotificationAge.MAX_AGE_MS))
        assertFalse(fresh(start, now = start + NotificationAge.MAX_AGE_MS + 1))
    }

    @Test
    fun `a notification for an event already handled from the screen is ignored`() {
        assertFalse(fresh(start + 5_000, handled = start + 5_000))
        assertFalse(fresh(start + 5_000, handled = start + 9_000))
        assertTrue(fresh(start + 5_000, handled = start + 4_000))
    }

    @Test
    fun `one event seen on screen and in a notification gets one reply`() {
        val gate = ReplyGate()
        assertTrue(gate.tryBegin(0, 0, inHours = true))
        assertFalse(gate.tryBegin(1_000, 1_000, inHours = true))
        gate.finish(2_000, sent = true)
        assertFalse(gate.tryBegin(3_000, 3_000, inHours = true))
    }

    @Test
    fun `replies stay at least 20 seconds apart, counted from the send`() {
        val gate = ReplyGate()
        assertTrue(gate.tryBegin(0, 0, inHours = true))
        gate.finish(10_000, sent = true)
        assertFalse(gate.tryBegin(29_999, 29_999, inHours = true))
        assertTrue(gate.tryBegin(30_000, 30_000, inHours = true))
    }

    @Test
    fun `a burst gets one reply and nothing is left for later`() {
        val gate = ReplyGate()
        val passed = (0L until 10L).count { gate.tryBegin(it * 100, it * 100, inHours = true) }
        assertEquals(1, passed)
        gate.finish(1_000, sent = true)
        assertTrue(gate.tryBegin(21_000, 21_000, inHours = true))
        gate.finish(22_000, sent = true)
        assertFalse(gate.tryBegin(22_001, 22_001, inHours = true))
    }

    @Test
    fun `nothing gets through outside active hours, and it does not use up the gap`() {
        val gate = ReplyGate()
        assertFalse(gate.tryBegin(0, 0, inHours = false))
        assertTrue(gate.tryBegin(1, 1, inHours = true))
    }

    @Test
    fun `a failed reply frees the gate but is not retried at once`() {
        val gate = ReplyGate()
        assertTrue(gate.tryBegin(0, 0, inHours = true))
        gate.finish(5_000, sent = false)
        assertFalse(gate.tryBegin(19_999, 19_999, inHours = true))
        assertTrue(gate.tryBegin(20_000, 20_000, inHours = true))
    }

    @Test
    fun `an abandoned reply does not hold the gate`() {
        val gate = ReplyGate()
        assertTrue(gate.tryBegin(0, 0, inHours = true))
        gate.cancel()
        assertTrue(gate.tryBegin(20_000, 20_000, inHours = true))
    }

    @Test
    fun `the last handled time follows what got through`() {
        val gate = ReplyGate()
        gate.tryBegin(0, 5_000, inHours = true)
        assertEquals(5_000, gate.lastHandledWallMs)
        gate.tryBegin(1, 9_000, inHours = true)
        assertEquals(5_000, gate.lastHandledWallMs)
    }

    @Test
    fun `Auto reply needs a channel, a trigger and a reply before Start`() {
        val reply = ReactiveConfig(listOf("phrase one"), "my reply", "room")
        val config = TaskConfig(mode = OperatingMode.AUTO_REPLY, reactive = reply)
        assertTrue(config.validate().isEmpty())
        assertEquals(listOf(ConfigError.REACTIVE_ROOM_REQUIRED), config.copy(reactive = reply.copy(room = " ")).validate())
        assertEquals(listOf(ConfigError.REACTIVE_NO_TRIGGER), config.copy(reactive = reply.copy(triggers = emptyList())).validate())
        assertEquals(listOf(ConfigError.REACTIVE_NO_COMMAND), config.copy(reactive = reply.copy(command = "")).validate())
    }

    @Test
    fun `no notification access warns in Auto reply but does not block Start`() {
        val config = TaskConfig(mode = OperatingMode.AUTO_REPLY, reactive = ReactiveConfig(listOf("a phrase"), "x", "y"))
        val off = PermissionState(true, true, true, true, notificationAccess = false)
        val warning = homeWarnings(config, off).single()
        assertEquals(HomeWarning.NOTIFICATION_ACCESS, warning)
        assertFalse(warning.blocksStart)
        assertTrue(homeWarnings(TaskConfig(commands = listOf(CommandEntry("x", 60))), off).isEmpty())
    }

    @Test
    fun `Stop holds Daily hours until Start, whatever the time`() {
        val action = StopRule.whenStopped(true, inHours = true, StopRule.UNTIL_START, Long.MAX_VALUE - 1)
        assertEquals(StoppedAction.NONE, action)
        assertEquals(StoppedAction.NONE, StopRule.whenStopped(true, false, StopRule.UNTIL_START, 0))
        assertFalse(StopRule.mayAutoStart(StopRule.UNTIL_START, System.currentTimeMillis()))
    }

    @Test
    fun `pressing Start clears the hold`() {
        assertEquals(StoppedAction.START, StopRule.whenStopped(true, inHours = true, blockedUntilMs = 0, nowMs = 1))
        assertEquals(StoppedAction.SHOW_IDLE, StopRule.whenStopped(true, inHours = false, blockedUntilMs = 0, nowMs = 1))
    }

    @Test
    fun `a failure stop holds until Start, like pressing Stop`() {
        val heldAfterFailure = StopRule.UNTIL_START
        assertEquals(StoppedAction.NONE, StopRule.whenStopped(true, inHours = true, heldAfterFailure, Long.MAX_VALUE - 1))
        assertEquals(StoppedAction.NONE, StopRule.whenStopped(false, inHours = false, heldAfterFailure, 0))
    }

    @Test
    fun `a running session resumes by itself after its idle hours`() {
        val hours = CommandEntry("x", 60, allDay = false, startMinute = 13 * 60, endMinute = 14 * 60)
        val timers = CommandTimers.forConfig(TaskConfig(commands = listOf(hours)), 3_000)
        timers.refresh(0, noon)
        assertTrue(timers.allIdle)
        timers.refresh(1_000, noon.plusHours(1))
        assertFalse(timers.allIdle)
        assertEquals(listOf(0), timers.dueOrder(4_000))
    }
}
