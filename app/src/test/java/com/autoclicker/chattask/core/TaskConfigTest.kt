package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskConfigTest {
    private val own = listOf(CommandEntry("my command", intervalSec = 610))

    @Test
    fun `defaults match the requested schedule and are valid`() {
        val config = TaskConfig()
        assertTrue("x" in config.popupKeywords && "X" in config.popupKeywords)
        assertEquals(listOf(ConfigError.EMPTY_COMMAND, ConfigError.INTERVAL_TOO_SHORT), config.validate())
        assertTrue(config.commands.all { it.text.isEmpty() })
        assertTrue(config.targetRoom.isEmpty() && config.reactive.room.isEmpty())
        assertTrue(config.reactive.command.isEmpty())
    }

    @Test
    fun `rejects each invalid field`() {
        assertEquals(
            listOf(ConfigError.EMPTY_COMMAND),
            TaskConfig(commands = listOf(CommandEntry("ok", 610), CommandEntry("  ", 610))).validate(),
        )
        assertTrue(ConfigError.EMPTY_COMMAND in TaskConfig(commands = emptyList()).validate())
        assertEquals(
            listOf(ConfigError.TOO_MANY_COMMANDS),
            TaskConfig(commands = List(6) { CommandEntry("c$it", 610) }).validate(),
        )
        assertEquals(listOf(ConfigError.ROOM_TOO_LONG), TaskConfig(commands = own, targetRoom = "9".repeat(65)).validate())
        assertEquals(
            listOf(ConfigError.JITTER_RANGE),
            TaskConfig(commands = own, jitterMinSec = 5, jitterMaxSec = 3).validate(),
        )
        assertEquals(
            listOf(ConfigError.JITTER_TOO_LARGE),
            TaskConfig(commands = listOf(CommandEntry("a", 20)), jitterMaxSec = 16).validate(),
        )
    }

    @Test
    fun `five commands are allowed`() {
        assertTrue(TaskConfig(commands = List(5) { CommandEntry("c$it", 60) }).validate().isEmpty())
    }

    @Test
    fun `an interval of zero is rejected`() {
        assertEquals(
            listOf(ConfigError.INTERVAL_TOO_SHORT),
            TaskConfig(commands = listOf(CommandEntry("a", 610), CommandEntry("b", 0))).validate()
                .filter { it == ConfigError.INTERVAL_TOO_SHORT },
        )
    }

    @Test
    fun `daily without valid times can be saved and asks for a schedule`() {
        val unset = TaskConfig(commands = listOf(CommandEntry("a", 610, allDay = false)))
        val same = TaskConfig(
            commands = listOf(CommandEntry("a", 610, allDay = false, startMinute = 60, endMinute = 60)),
        )
        val allDay = TaskConfig(commands = listOf(CommandEntry("a", 610)))
        assertEquals(emptyList<ConfigError>(), unset.validate())
        assertEquals(emptyList<ConfigError>(), same.validate())
        assertEquals(true, unset.needsSchedule)
        assertEquals(true, same.needsSchedule)
        assertEquals(false, allDay.needsSchedule)
    }

    @Test
    fun `parses keywords separated by either comma style`() {
        assertEquals(
            listOf("تم", "إغلاق", "close"),
            TaskConfig.parseKeywords("تم، إغلاق, close\n ,تم"),
        )
    }

    @Test
    fun `formats durations`() {
        assertEquals("10:10", formatDuration(610_000))
        assertEquals("22:00:00", formatDuration(79_200_000))
        assertEquals("0:01", formatDuration(1))
        assertEquals("0:00", formatDuration(-5))
    }
}
