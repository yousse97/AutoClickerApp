package com.autoclicker.chattask.core

import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.service.ScheduleKeeper
import com.autoclicker.chattask.service.TaskPhase
import com.autoclicker.chattask.service.canSwitchMode
import com.autoclicker.chattask.ui.HomeWarning
import com.autoclicker.chattask.ui.PermissionState
import com.autoclicker.chattask.ui.SettingsPage
import com.autoclicker.chattask.ui.homeWarnings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OperatingModeTest {
    private val commands = listOf(CommandEntry("my command", 60))
    private val replies = ReactiveConfig(command = "my reply", room = "two")
    private val scheduled = TaskConfig(commands = commands, reactive = replies)
    private val autoReply = scheduled.copy(mode = OperatingMode.AUTO_REPLY)
    private val granted = PermissionState(accessibility = true, overlay = true, notifications = true, battery = true)
    private val noon = LocalDateTime.of(2026, 1, 15, 12, 0)

    private fun daily(start: Int, end: Int) = CommandEntry("", allDay = false, startMinute = start, endMinute = end)

    @Test
    fun `an update with no saved mode stays on scheduled commands`() {
        assertEquals(OperatingMode.SCHEDULED, TaskConfig().mode)
        assertEquals(OperatingMode.SCHEDULED, OperatingMode.fromStored(null))
        assertEquals(OperatingMode.SCHEDULED, OperatingMode.fromStored("unknown"))
        assertEquals(OperatingMode.AUTO_REPLY, OperatingMode.fromStored(OperatingMode.AUTO_REPLY.name))
    }

    @Test
    fun `scheduled mode gives auto reply nothing to act on`() {
        assertNull(scheduled.replies)
        assertEquals(replies, autoReply.replies)
    }

    @Test
    fun `auto reply mode never lets a command come due`() {
        val timers = CommandTimers.forConfig(autoReply, 3_000)
        timers.refresh(0, noon)
        val aLongDayLater = 86_400_000L * 30
        assertTrue(timers.dueOrder(aLongDayLater).isEmpty())
        assertEquals(1, timers.size)
    }

    @Test
    fun `scheduled mode still sends its commands`() {
        val timers = CommandTimers.forConfig(scheduled, 3_000)
        timers.refresh(0, noon)
        assertEquals(listOf(0), timers.dueOrder(3_000))
    }

    @Test
    fun `each mode validates only itself`() {
        val blank = TaskConfig(commands = listOf(CommandEntry("")), reactive = replies)
        assertTrue(blank.copy(mode = OperatingMode.AUTO_REPLY).validate().isEmpty())
        assertEquals(listOf(ConfigError.EMPTY_COMMAND, ConfigError.INTERVAL_TOO_SHORT), blank.validate())
        val noReply = TaskConfig(commands = commands)
        assertTrue(noReply.validate().isEmpty())
        assertEquals(
            listOf(ConfigError.REACTIVE_NO_COMMAND, ConfigError.REACTIVE_ROOM_REQUIRED),
            noReply.copy(mode = OperatingMode.AUTO_REPLY).validate(),
        )
    }

    @Test
    fun `each mode uses its own channel and return setting`() {
        val config = TaskConfig(
            targetRoom = "one",
            restrictToRoom = true,
            returnBeforeSend = true,
            reactive = ReactiveConfig(room = "two", returnBeforeSend = false),
        )
        assertEquals("one", config.activeRoom)
        assertTrue(config.activeRestrict && config.activeReturn)
        val reply = config.copy(mode = OperatingMode.AUTO_REPLY)
        assertEquals("two", reply.activeRoom)
        assertFalse(reply.activeReturn)
        assertTrue(reply.activeRestrict)
    }

    @Test
    fun `switching needs a stopped session with nothing to restore`() {
        assertTrue(canSwitchMode(TaskPhase.IDLE, hasSavedSession = false))
        assertFalse(canSwitchMode(TaskPhase.RUNNING, hasSavedSession = true))
        assertFalse(canSwitchMode(TaskPhase.PAUSED, hasSavedSession = true))
        assertFalse(canSwitchMode(TaskPhase.IDLE, hasSavedSession = true))
    }

    @Test
    fun `switching there and back keeps both modes settings`() {
        val daily = autoReply.copy(reactive = replies.copy(hours = daily(60, 120), room = "two"))
        val back = daily.copy(mode = OperatingMode.SCHEDULED).copy(mode = OperatingMode.AUTO_REPLY)
        assertEquals(daily, back)
        assertEquals(commands, back.commands)
    }

    @Test
    fun `reply hours survive being saved`() {
        val hours = daily(600, 90)
        assertEquals(hours, SettingsStore.decodeReplyHours(SettingsStore.encodeReplyHours(hours)))
        assertNull(SettingsStore.decodeReplyHours("not saved data"))
    }

    @Test
    fun `reply hours default to 24 hours with no times`() {
        val hours = ReactiveConfig().hours
        assertTrue(hours.allDay)
        assertFalse(hours.window.isComplete)
        assertTrue(ReactiveConfig().returnBeforeSend)
    }

    @Test
    fun `replies wait outside their hours and the schedule follows them`() {
        val config = autoReply.copy(reactive = replies.copy(hours = daily(13 * 60, 14 * 60)))
        assertFalse(config.reactive.isActive(noon))
        assertTrue(config.reactive.isActive(noon.plusHours(1)))
        assertEquals(noon.plusHours(1), ScheduleKeeper.nextStart(config, noon))
        val timers = CommandTimers.forConfig(config, 3_000)
        timers.refresh(0, noon)
        assertTrue(timers.allIdle)
        timers.refresh(1_000, noon.plusHours(1))
        assertFalse(timers.allIdle)
    }

    @Test
    fun `reply hours can cross midnight`() {
        val reply = replies.copy(hours = daily(23 * 60, 60))
        assertTrue(reply.isActive(noon.withHour(23).withMinute(30)))
        assertTrue(reply.isActive(noon.withHour(0).withMinute(30)))
        assertFalse(reply.isActive(noon))
    }

    @Test
    fun `scheduled hours do not move the auto reply schedule`() {
        val config = autoReply.copy(commands = listOf(CommandEntry("x", 60, false, 1, 2)))
        assertTrue(config.activeHours.single().allDay)
        assertFalse(config.needsSchedule)
    }

    @Test
    fun `stopping 24 hour replies waits for a person, and Daily waits for the next start`() {
        assertEquals(Long.MAX_VALUE, ScheduleKeeper.blockUntil(autoReply, noon))
        val daily = autoReply.copy(reactive = replies.copy(hours = daily(13 * 60, 14 * 60)))
        assertEquals(ScheduleKeeper.toEpochMs(noon.plusHours(1)), ScheduleKeeper.blockUntil(daily, noon))
    }

    @Test
    fun `the return comes 5 seconds before the hours and once for the whole stretch`() {
        val reply = replies.copy(hours = daily(13 * 60, 14 * 60))
        val planner = ReturnPlanner()
        fun returns(time: LocalDateTime): Boolean {
            val slot = assertNotNullSlot(reply, time)
            return planner.shouldReturn(slot.id, slot.startsInMs, true, Foreground.OTHER_APP, true)
        }
        val start = noon.plusHours(1)
        assertFalse(returns(start.minusSeconds(6)))
        assertTrue(returns(start.minusSeconds(5)))
        assertFalse(returns(start))
        assertFalse(returns(start.plusMinutes(10)))
    }

    @Test
    fun `the next day's hours get their own return`() {
        val reply = replies.copy(hours = daily(13 * 60, 14 * 60))
        val today = assertNotNullSlot(reply, noon.plusHours(1))
        val tomorrow = assertNotNullSlot(reply, noon.plusHours(25))
        assertTrue(today.id != tomorrow.id)
    }

    @Test
    fun `24 hour replies return at Start and Daily without times never does`() {
        assertEquals(ReplySlot(0, 0), replies.nextSlot(noon))
        assertNull(replies.copy(hours = CommandEntry("", allDay = false)).nextSlot(noon))
    }

    @Test
    fun `a daily reply with no times warns on the auto reply page but does not block Start`() {
        val config = autoReply.copy(reactive = replies.copy(hours = CommandEntry("", allDay = false)))
        val warning = homeWarnings(config, granted).single()
        assertEquals(HomeWarning.REPLY_SCHEDULE, warning)
        assertTrue(config.validate().isEmpty())
        assertEquals(SettingsPage.AUTO_REPLY, warning.page)
        assertFalse(warning.blocksStart)
    }

    @Test
    fun `a missing reply blocks Start only in auto reply mode`() {
        val missing = TaskConfig(commands = commands)
        assertTrue(homeWarnings(missing, granted).isEmpty())
        val warning = homeWarnings(missing.copy(mode = OperatingMode.AUTO_REPLY), granted).single()
        assertEquals(HomeWarning.AUTO_REPLY, warning)
        assertTrue(warning.blocksStart)
    }

    private fun assertNotNullSlot(reply: ReactiveConfig, time: LocalDateTime): ReplySlot {
        val slot = reply.nextSlot(time)
        assertNotNull(slot)
        return slot!!
    }
}
