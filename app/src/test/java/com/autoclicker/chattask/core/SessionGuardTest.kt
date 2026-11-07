package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGuardTest {
    private val minute = 60_000L
    private val guard = SessionGuard()

    private fun observe(foreground: Foreground, now: Long, idle: Boolean = false) =
        guard.observe(foreground, idle, now)

    @Test
    fun `a locked phone that returns within 15 minutes carries on`() {
        assertEquals(GuardAction.NONE, observe(Foreground.LOCKED, 0))
        assertEquals(GuardAction.NONE, observe(Foreground.LOCKED, 14 * minute))
        assertEquals(GuardAction.NONE, observe(Foreground.WOLF, 14 * minute + 1))
        assertEquals(GuardAction.NONE, observe(Foreground.LOCKED, 20 * minute))
        assertEquals(GuardAction.NONE, observe(Foreground.LOCKED, 34 * minute))
    }

    @Test
    fun `a phone locked for 15 minutes stops the session`() {
        observe(Foreground.LOCKED, 0)
        assertEquals(GuardAction.STOP_LOCKED, observe(Foreground.LOCKED, 15 * minute))
    }

    @Test
    fun `the lock timeout starts when idle hours end`() {
        observe(Foreground.LOCKED, 0, idle = true)
        assertEquals(GuardAction.NONE, observe(Foreground.LOCKED, 120 * minute, idle = true))
        assertEquals(GuardAction.NONE, observe(Foreground.LOCKED, 121 * minute))
        assertEquals(GuardAction.NONE, observe(Foreground.LOCKED, 135 * minute))
        assertEquals(GuardAction.STOP_LOCKED, observe(Foreground.LOCKED, 136 * minute))
    }

    @Test
    fun `the home screen reopens WOLF after 60 seconds and not before`() {
        observe(Foreground.HOME, 0)
        assertEquals(GuardAction.NONE, observe(Foreground.HOME, 59_000))
        assertEquals(GuardAction.RELAUNCH, observe(Foreground.HOME, 60_000))
    }

    @Test
    fun `leaving the home screen restarts its wait`() {
        observe(Foreground.HOME, 0)
        observe(Foreground.WOLF, 30_000)
        observe(Foreground.HOME, 31_000)
        assertEquals(GuardAction.NONE, observe(Foreground.HOME, 90_000))
    }

    @Test
    fun `another app never causes a relaunch or a stop`() {
        for (now in 0..600L step 10) {
            assertEquals(GuardAction.NONE, observe(Foreground.OTHER_APP, now * minute))
        }
    }

    @Test
    fun `nothing happens on the home screen during idle hours`() {
        assertEquals(GuardAction.NONE, observe(Foreground.HOME, 0, idle = true))
        assertEquals(GuardAction.NONE, observe(Foreground.HOME, 30 * minute, idle = true))
    }

    @Test
    fun `three failed relaunches in a row stop the session`() {
        assertEquals(GuardAction.NONE, guard.onRelaunchResult(false, 0))
        assertEquals(GuardAction.NONE, guard.onRelaunchResult(false, minute))
        assertEquals(GuardAction.STOP_RELAUNCH_FAILED, guard.onRelaunchResult(false, 2 * minute))
    }

    @Test
    fun `a successful relaunch clears the count`() {
        guard.onRelaunchResult(false, 0)
        guard.onRelaunchResult(false, minute)
        guard.onRelaunchResult(true, 2 * minute)
        assertEquals(GuardAction.NONE, guard.onRelaunchResult(false, 3 * minute))
    }

    @Test
    fun `browsing WOLF never moves the user, and retries wait a minute`() {
        guard.onRelaunchResult(false, 0)
        assertEquals(GuardAction.NONE, observe(Foreground.WOLF, 120_000))
        assertFalse(guard.mayNavigate(59_000))
        assertTrue(guard.mayNavigate(60_000))
    }

    @Test
    fun `five failed sends in a row stop the session`() {
        repeat(4) { assertEquals(GuardAction.NONE, guard.onSendResult(false)) }
        assertEquals(GuardAction.STOP_SEND_FAILURES, guard.onSendResult(false))
    }

    @Test
    fun `a successful send resets the failure count`() {
        repeat(4) { guard.onSendResult(false) }
        guard.onSendResult(true)
        assertEquals(0, guard.sendFailures)
        repeat(4) { assertEquals(GuardAction.NONE, guard.onSendResult(false)) }
    }

    @Test
    fun `the log keeps the newest 200 events`() {
        val ring = EventRing()
        repeat(250) { ring.add(LogEvent(it.toLong(), EventType.SENT)) }
        assertEquals(200, ring.all.size)
        assertEquals(50L, ring.all.first().timeMs)
        assertEquals(249L, ring.all.last().timeMs)
    }

    @Test
    fun `the log reports the last send, last error and failure streak`() {
        val ring = EventRing()
        assertNull(ring.lastSend)
        ring.add(LogEvent(1, EventType.SEND_FAILED))
        ring.add(LogEvent(2, EventType.SENT))
        ring.add(LogEvent(3, EventType.SEND_FAILED))
        ring.add(LogEvent(4, EventType.HELD_OTHER_APP))
        ring.add(LogEvent(5, EventType.SEND_FAILED))

        assertEquals(2L, ring.lastSend?.timeMs)
        assertEquals(5L, ring.lastError?.timeMs)
        assertEquals(2, ring.consecutiveFailures)
    }

    @Test
    fun `holding a send is never logged as an error`() {
        val held = listOf(
            EventType.HELD_LOCKED,
            EventType.HELD_OTHER_APP,
            EventType.HELD_NOT_CHANNEL,
            EventType.HELD_WRONG_ROOM,
        )
        assertTrue(held.none { it.isError })
    }

    @Test
    fun `only a screen with all three signals counts as a channel`() {
        val channel = WolfScreen(hasInput = true, title = "12345", hasMembersButton = true)
        assertTrue(channel.isChannel)
        assertFalse(channel.copy(hasMembersButton = false).isChannel)
        assertFalse(channel.copy(hasInput = false).isChannel)
        assertFalse(channel.copy(title = " ").isChannel)
        assertFalse(WolfScreen().isChannel)
    }

    @Test
    fun `room check needs the whole header title`() {
        assertTrue(WolfUi.isRoom("12345", " 12345 "))
        assertTrue(WolfUi.isRoom("غرفة الأصدقاء", "غرفة الاصدقاء"))
        assertFalse(WolfUi.isRoom("112345", "12345"))
        assertFalse(WolfUi.isRoom("12345 fans", "12345"))
        assertFalse(WolfUi.isRoom(null, "12345"))
        assertFalse(WolfUi.isRoom("12345", " "))
    }

    @Test
    fun `the logout dialog is recognised, so popups never confirm it`() {
        assertTrue(WolfUi.isLogoutTitle("تسجيل الخروج"))
        assertFalse(WolfUi.isLogoutTitle("تنبيه"))
        assertFalse(WolfUi.isLogoutTitle(null))
        assertTrue(WolfUi.isLogoutTitle("Sign Out"))
    }

    @Test
    fun `the channel button is recognised in Arabic and English, but not other buttons`() {
        assertTrue(WolfUi.isMembersButton("اعضاء القناة"))
        assertTrue(WolfUi.isMembersButton("Channel Members"))
        assertFalse(WolfUi.isMembersButton("More"))
        assertFalse(WolfUi.isMembersButton(null))
    }

    @Test
    fun `only the real send id counts, so the gift button cannot`() {
        assertTrue(WolfUi.isSendButton("com.palringo.android:id/chat_bar_send"))
        assertFalse(WolfUi.isSendButton("com.palringo.android:id/chat_bar_gifting"))
        assertFalse(WolfUi.isSendButton("com.palringo.android:id/vm_record_hitzone"))
        assertFalse(WolfUi.isSendButton(null))
    }
}
