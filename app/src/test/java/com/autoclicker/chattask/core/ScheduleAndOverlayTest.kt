package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ScheduleAndOverlayTest {
    private fun day(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 3, 10, hour, minute)

    private val dayWindow = DailySchedule(true, 9 * 60, 17 * 60)
    private val nightWindow = DailySchedule(true, 22 * 60, 6 * 60)

    @Test
    fun `a daytime window starts at its start and ends at its end`() {
        assertFalse(dayWindow.isActive(day(8, 59)))
        assertTrue(dayWindow.isActive(day(9)))
        assertTrue(dayWindow.isActive(day(16, 59)))
        assertFalse(dayWindow.isActive(day(17)))
    }

    @Test
    fun `a window can cross midnight`() {
        assertTrue(nightWindow.isActive(day(23)))
        assertTrue(nightWindow.isActive(day(0)))
        assertTrue(nightWindow.isActive(day(5, 59)))
        assertFalse(nightWindow.isActive(day(6)))
        assertFalse(nightWindow.isActive(day(21, 59)))
    }

    @Test
    fun `the next start and end roll to the following day when passed`() {
        assertEquals(day(22), nightWindow.nextStart(day(12)))
        assertEquals(day(6).plusDays(1), nightWindow.nextEnd(day(23)))
        assertEquals(day(9).plusDays(1), dayWindow.nextStart(day(9)))
    }

    @Test
    fun `an unset or zero-length schedule is never active`() {
        assertFalse(DailySchedule(enabled = true).isActive(day(12)))
        assertFalse(DailySchedule(true, 600, 600).isActive(day(10)))
    }

    @Test
    fun `an enabled schedule needs two different times, and has no built-in times`() {
        val own = listOf(CommandEntry("my command"))
        assertEquals(DailySchedule.UNSET, DailySchedule().startMinute)
        assertEquals(DailySchedule.UNSET, DailySchedule().endMinute)
        assertEquals(
            listOf(ConfigError.SCHEDULE_TIMES),
            TaskConfig(commands = own, schedule = DailySchedule(enabled = true)).validate(),
        )
        assertTrue(TaskConfig(commands = own, schedule = dayWindow).validate().isEmpty())
    }

    @Test
    fun `movement inside the slop is a tap, and beyond it is a drag`() {
        val tracker = TapTracker(slopPx = 10f)
        tracker.down(100f, 100f)
        tracker.move(105f, 103f)
        assertTrue(tracker.up(106f, 104f))

        tracker.down(100f, 100f)
        tracker.move(140f, 100f)
        assertFalse(tracker.up(100f, 100f))
    }

    @Test
    fun `one vibration per command, only outside WOLF`() {
        val alert = PreSendAlert()
        assertFalse(alert.shouldVibrate(1, 31_000, true, Foreground.OTHER_APP, true))
        assertFalse(alert.shouldVibrate(1, 20_000, true, Foreground.WOLF, true))
        assertFalse(alert.shouldVibrate(1, 20_000, true, Foreground.LOCKED, true))
        assertTrue(alert.shouldVibrate(1, 20_000, true, Foreground.OTHER_APP, true))
        assertFalse(alert.shouldVibrate(1, 10_000, true, Foreground.OTHER_APP, true))
        assertTrue(alert.shouldVibrate(2, 10_000, true, Foreground.HOME, true))
    }

    @Test
    fun `vibration can be turned off`() {
        assertFalse(PreSendAlert().shouldVibrate(1, 20_000, true, Foreground.OTHER_APP, false))
    }

    @Test
    fun `the bar warns near send time and calls the user back after it`() {
        val alert = PreSendAlert()
        assertEquals(BarState.NORMAL, alert.barState(60_000, true, Foreground.OTHER_APP))
        assertEquals(BarState.SEND_SOON, alert.barState(30_000, true, Foreground.WOLF))
        assertEquals(BarState.COMMAND_READY, alert.barState(0, true, Foreground.OTHER_APP))
        assertEquals(BarState.SEND_SOON, alert.barState(0, true, Foreground.WOLF))
        assertEquals(BarState.NORMAL, alert.barState(0, false, Foreground.OTHER_APP))
    }

    @Test
    fun `a popup that stays up counts as one message`() {
        val popup = PopupTrigger(goneMs = 3_000)
        assertTrue(popup.observe(true, 0))
        repeat(20) { assertFalse(popup.observe(true, it * 500L)) }
        assertFalse(popup.observe(false, 10_000))
        assertFalse(popup.observe(false, 12_000))
    }

    @Test
    fun `a new popup after the last one is gone counts again`() {
        val popup = PopupTrigger(goneMs = 3_000)
        assertTrue(popup.observe(true, 0))
        popup.observe(false, 4_000)
        assertTrue(popup.observe(true, 5_000))
    }

    @Test
    fun `a brief gap does not count as a second popup`() {
        val popup = PopupTrigger(goneMs = 3_000)
        assertTrue(popup.observe(true, 0))
        assertFalse(popup.observe(false, 1_000))
        assertFalse(popup.observe(true, 2_000))
    }

    private val channel = WolfScreen(hasInput = true, title = "my room", hasMembersButton = true)

    private fun check(screen: WolfScreen = channel, inFront: Boolean = true, room: String = "my room", restrict: Boolean = true, send: Boolean = false) =
        ReadOnlyCheck.evaluate(true, true, inFront, screen, restrict, room, send).associate { it.item to it.status }

    @Test
    fun `a valid channel passes, and an unseen send button is not a failure`() {
        val result = check()
        assertEquals(CheckStatus.PASS, result[CheckItem.CHANNEL])
        assertEquals(CheckStatus.PASS, result[CheckItem.ROOM])
        assertEquals(CheckStatus.PASS, result[CheckItem.MESSAGE_BOX])
        assertEquals(CheckStatus.INFO, result[CheckItem.SEND_BUTTON])
    }

    @Test
    fun `a private chat fails the channel check, and a different channel fails the room check`() {
        assertEquals(CheckStatus.FAIL, check(channel.copy(hasMembersButton = false))[CheckItem.CHANNEL])
        assertEquals(CheckStatus.FAIL, check(room = "other room")[CheckItem.ROOM])
    }

    @Test
    fun `with WOLF closed the check stops at that item, and room is skipped when unrestricted`() {
        assertEquals(3, check(inFront = false).size)
        assertFalse(CheckItem.ROOM in check(restrict = false))
    }
}
