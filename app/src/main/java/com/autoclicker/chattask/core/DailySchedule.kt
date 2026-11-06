package com.autoclicker.chattask.core

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * A daily window in the phone's local time. Times are unset until the user picks them, and an
 * end earlier than the start means the window crosses midnight.
 */
data class DailySchedule(
    val enabled: Boolean = false,
    val startMinute: Int = UNSET,
    val endMinute: Int = UNSET,
    val keepScreenOnWhenIdle: Boolean = true,
) {
    val isComplete: Boolean
        get() = startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY &&
            startMinute != endMinute

    fun isActive(now: LocalDateTime): Boolean {
        if (!isComplete) return false
        val minute = now.hour * 60 + now.minute
        return if (startMinute < endMinute) {
            minute in startMinute until endMinute
        } else {
            minute >= startMinute || minute < endMinute
        }
    }

    fun nextStart(now: LocalDateTime): LocalDateTime = nextAt(startMinute, now)

    fun nextEnd(now: LocalDateTime): LocalDateTime = nextAt(endMinute, now)

    private fun nextAt(minute: Int, now: LocalDateTime): LocalDateTime {
        val today = now.toLocalDate().atTime(LocalTime.of(minute / 60, minute % 60))
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    companion object {
        const val UNSET = -1
        const val MINUTES_PER_DAY = 24 * 60
    }
}
