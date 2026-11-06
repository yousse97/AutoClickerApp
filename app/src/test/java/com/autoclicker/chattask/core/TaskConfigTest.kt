package com.autoclicker.chattask.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskConfigTest {
    @Test
    fun `defaults match the requested schedule and are valid`() {
        val config = TaskConfig()
        assertEquals(610, config.intervalSec)
        assertEquals(79_200, config.durationSec)
        assertTrue(config.validate().isEmpty())
    }

    @Test
    fun `rejects each invalid field`() {
        assertEquals(listOf(ConfigError.EMPTY_COMMAND), TaskConfig(command = "  ").validate())
        assertEquals(listOf(ConfigError.DURATION_TOO_SHORT), TaskConfig(durationSec = 60).validate())
        assertEquals(
            listOf(ConfigError.JITTER_RANGE),
            TaskConfig(jitterMinSec = 5, jitterMaxSec = 3).validate(),
        )
        assertEquals(
            listOf(ConfigError.JITTER_TOO_LARGE),
            TaskConfig(intervalSec = 20, durationSec = 60, jitterMaxSec = 16).validate(),
        )
        assertEquals(listOf(ConfigError.BAD_PACKAGE), TaskConfig(targetPackage = "not a package").validate())
    }

    @Test
    fun `accepts a real package name`() {
        assertTrue(TaskConfig(targetPackage = "com.discord").validate().isEmpty())
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
