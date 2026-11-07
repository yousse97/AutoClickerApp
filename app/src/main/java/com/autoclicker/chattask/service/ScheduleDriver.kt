package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.Foreground
import com.autoclicker.chattask.core.StopRule
import com.autoclicker.chattask.core.StoppedAction
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.data.EventLog
import com.autoclicker.chattask.data.SettingsStore
import java.time.LocalDateTime

/**
 * Starts sessions at command start times and shows the idle state. A running session stays up
 * while its commands sit idle. Idle hours are not an error and count toward nothing.
 */
class ScheduleDriver(
    private val service: AccessibilityService,
    private val store: SettingsStore,
    private val hasSession: () -> Boolean,
    private val startSession: () -> Unit,
) {
    var config = TaskConfig()
    private var missedForStart = 0L
    private var idleScreenWarned = false
    private var overlayDismissed = false
    private var wasIdle: Boolean? = null

    fun onOverlayClosed() {
        overlayDismissed = true
    }

    /** Called each second while nothing is running. */
    fun tickStopped() {
        if (hasSession()) return
        val now = LocalDateTime.now()
        val action = StopRule.whenStopped(
            hasHours = config.activeHours.any { !it.allDay },
            inHours = config.activeHours.any { it.isActive(now) },
            blockedUntilMs = store.blockedUntilMs,
            nowMs = System.currentTimeMillis(),
        )
        when (action) {
            StoppedAction.NONE -> clearIdle()
            StoppedAction.SHOW_IDLE -> showIdle(now)
            StoppedAction.START -> startForSchedule(now)
        }
    }

    private fun startForSchedule(now: LocalDateTime) {
        val startedAt = ScheduleKeeper.nextStart(config, now)?.let(ScheduleKeeper::toEpochMs) ?: 0
        val errors = config.validate()
        if (errors.isEmpty()) {
            clearIdle()
            idleScreenWarned = false
            overlayDismissed = false
            OverlayService.start(service)
            EventLog.add(service, EventType.SCHEDULE_START)
            startSession()
            ScheduleKeeper.arm(service, config)
        } else if (missedForStart != startedAt) {
            missedForStart = startedAt
            EventLog.add(service, EventType.SCHEDULE_MISSED)
            TaskBus.update { it.copy(notice = Notices.forErrors(errors)) }
            Alerts.post(service, Notices.forErrors(errors))
        }
    }

    /** Called each second during a session, with whether all commands are idle right now. */
    fun tickSession(allIdle: Boolean) {
        // The first look only learns the state, so a service rebind during idle logs no new stop.
        val changed = wasIdle != null && allIdle != wasIdle
        wasIdle = allIdle
        if (changed) {
            EventLog.add(service, if (allIdle) EventType.SCHEDULE_STOP else EventType.SCHEDULE_START)
            ScheduleKeeper.arm(service, config)
        }
        if (allIdle) showIdle(LocalDateTime.now()) else clearIdle()
    }

    private fun clearIdle() =
        TaskBus.update { it.copy(idleUntilMs = 0, idleKeepScreen = false, scheduleNeeded = false) }

    /** The bar and services stay up, and nothing is sent until the next start. */
    private fun showIdle(now: LocalDateTime) {
        val until = ScheduleKeeper.nextStart(config, now)?.let(ScheduleKeeper::toEpochMs) ?: 0
        val keepOn = config.keepScreenWhenIdle
        TaskBus.update {
            it.copy(idleUntilMs = until, idleKeepScreen = keepOn, scheduleNeeded = until == 0L)
        }
        if (keepOn && !overlayDismissed && !OverlayService.isRunning) OverlayService.start(service)
        val locked = NodeTools.foreground(service) == Foreground.LOCKED
        if (keepOn && locked && !idleScreenWarned) {
            idleScreenWarned = true
            EventLog.add(service, EventType.IDLE_SCREEN_OFF)
            Alerts.post(service, R.string.alert_idle_screen_off)
        }
    }
}
