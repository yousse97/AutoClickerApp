package com.autoclicker.chattask.data

import com.autoclicker.chattask.core.CommandEntry
import com.autoclicker.chattask.core.DailySchedule
import com.autoclicker.chattask.core.TimerOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {
    private val oldFormat = """[{"text":"my command","mode":"SIMULTANEOUS","delay":0}]"""

    @Test
    fun `an old command keeps its text and takes the old shared interval`() {
        val command = SettingsStore.decodeCommands(oldFormat, legacyIntervalSec = 610)!!.single()
        assertEquals("my command", command.text)
        assertEquals(610, command.intervalSec)
        assertTrue(command.allDay)
    }

    @Test
    fun `an old daily schedule moves onto the command`() {
        val command = SettingsStore.decodeCommands(oldFormat, 610, DailySchedule(100, 200))!!.single()
        assertFalse(command.allDay)
        assertEquals(100, command.startMinute)
        assertEquals(200, command.endMinute)
    }

    @Test
    fun `new commands round trip unchanged and ignore old values`() {
        val commands = listOf(
            CommandEntry("one", 370, allDay = false, startMinute = 1365, endMinute = 1235),
            CommandEntry("two", 900),
        )
        val json = SettingsStore.encodeCommands(commands)
        assertEquals(commands, SettingsStore.decodeCommands(json, 610, DailySchedule(1, 2)))
    }

    @Test
    fun `timers round trip, including idle ones`() {
        val timers = listOf(TimerOffset(1, 2, true), null)
        assertEquals(timers, SettingsStore.decodeTimers(SettingsStore.encodeTimers(timers)))
    }

    @Test
    fun `unreadable data falls back instead of crashing`() {
        assertNull(SettingsStore.decodeCommands("not json"))
        assertNull(SettingsStore.decodeTimers("not json"))
    }
}
