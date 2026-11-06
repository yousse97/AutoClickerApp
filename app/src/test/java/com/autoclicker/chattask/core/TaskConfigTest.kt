package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskConfigTest {
    private val own = listOf(CommandEntry("my command"))

    @Test
    fun `defaults match the requested schedule and are valid`() {
        val config = TaskConfig()
        assertEquals(610, config.intervalSec)
        assertEquals(79_200, config.workDurationSec)
        assertEquals(7_260, config.restDurationSec)
        assertTrue("x" in config.popupKeywords && "X" in config.popupKeywords)
        assertEquals(listOf(ConfigError.EMPTY_COMMAND), config.validate())
        assertTrue(config.commands.all { it.text.isEmpty() })
        assertTrue(config.targetRoom.isEmpty() && config.reactive.room.isEmpty())
        assertTrue(config.reactive.command.isEmpty())
    }

    @Test
    fun `rejects each invalid field`() {
        assertEquals(
            listOf(ConfigError.EMPTY_COMMAND),
            TaskConfig(commands = listOf(CommandEntry("ok"), CommandEntry("  "))).validate(),
        )
        assertEquals(listOf(ConfigError.EMPTY_COMMAND), TaskConfig(commands = emptyList()).validate())
        assertEquals(
            listOf(ConfigError.TOO_MANY_COMMANDS),
            TaskConfig(commands = List(6) { CommandEntry("c$it") }).validate(),
        )
        assertEquals(
            listOf(ConfigError.BAD_COMMAND_DELAY),
            TaskConfig(                commands = listOf(CommandEntry("a"), CommandEntry("b", SendMode.DELAYED, 0)),
            ).validate(),
        )
        assertEquals(
            listOf(ConfigError.SEQUENCE_TOO_LONG),
            TaskConfig(                intervalSec = 60,
                commands = listOf(CommandEntry("a"), CommandEntry("b", SendMode.DELAYED, 52)),
            ).validate(),
        )
        assertEquals(listOf(ConfigError.ROOM_TOO_LONG), TaskConfig(commands = own, targetRoom = "9".repeat(65)).validate())
        assertEquals(listOf(ConfigError.DURATION_TOO_SHORT), TaskConfig(commands = own, workDurationSec = 60).validate())
        assertEquals(
            listOf(ConfigError.JITTER_RANGE),
            TaskConfig(commands = own, jitterMinSec = 5, jitterMaxSec = 3).validate(),
        )
        assertEquals(
            listOf(ConfigError.JITTER_TOO_LARGE),
            TaskConfig(commands = own, intervalSec = 20, workDurationSec = 60, jitterMaxSec = 16).validate(),
        )
    }

    @Test
    fun `five commands with delays that fit are valid`() {
        val commands = List(5) { CommandEntry("c$it", SendMode.DELAYED, 60) }
        assertTrue(TaskConfig(commands = commands).validate().isEmpty())
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
