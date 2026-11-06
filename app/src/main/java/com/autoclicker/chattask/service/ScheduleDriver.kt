package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.DailySchedule
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.Foreground
import com.autoclicker.chattask.data.EventLog
import com.autoclicker.chattask.data.SettingsStore
import java.time.LocalDateTime

/** Applies the daily schedule. Idle hours are not an error and count toward nothing. */
class ScheduleDriver(
    private val service: AccessibilityService,
    private val store: SettingsStore,
    private val hasSession: () -> Boolean,
    private val startSession: () -> Unit,
    private val stopSession: () -> Unit,
) {
    var schedule = DailySchedule()
    private var missedPeriodEndMs = 0L
    private var idleScreenWarned = false
    private var overlayDismissed = false

    fun onOverlayClosed() {
        overlayDismissed = true
    }

    fun isOutsideHours(): Boolean = schedule.enabled && !schedule.isActive(LocalDateTime.now())

    /** A stop that needs a person stays in force until the current schedule period is over. */
    fun blockRestOfPeriod() {
        val now = LocalDateTime.now()
        if (schedule.enabled && schedule.isActive(now)) {
            store.blockedUntilMs = ScheduleKeeper.toEpochMs(schedule.nextEnd(now))
        }
    }

    fun tick() {
        if (!schedule.enabled || !schedule.isComplete) {
            TaskBus.update { it.copy(idleUntilMs = 0, idleKeepScreen = false) }
            return
        }
        val now = LocalDateTime.now()
        when {
            schedule.isActive(now) -> tickActivePeriod(now)
            hasSession() -> {
                stopSession()
                EventLog.add(service, EventType.SCHEDULE_STOP)
                ScheduleKeeper.arm(service, schedule)
            }
            else -> showIdle(now)
        }
    }

    private fun tickActivePeriod(now: LocalDateTime) {
        if (hasSession()) return
        if (System.currentTimeMillis() < store.blockedUntilMs) return showIdle(now)
        idleScreenWarned = false
        overlayDismissed = false
        val periodEnd = ScheduleKeeper.toEpochMs(schedule.nextEnd(now))
        val errors = store.loadConfig().validate()
        if (errors.isEmpty()) {
            TaskBus.update { it.copy(idleUntilMs = 0, idleKeepScreen = false) }
            OverlayService.start(service)
            EventLog.add(service, EventType.SCHEDULE_START)
            startSession()
            ScheduleKeeper.arm(service, schedule)
        } else if (missedPeriodEndMs != periodEnd) {
            missedPeriodEndMs = periodEnd
            EventLog.add(service, EventType.SCHEDULE_MISSED)
            TaskBus.update { it.copy(notice = Notices.forErrors(errors)) }
            Alerts.post(service, Notices.forErrors(errors))
        }
    }

    /** The bar and services stay up, and nothing is sent until the next start. */
    private fun showIdle(now: LocalDateTime) {
        val until = ScheduleKeeper.toEpochMs(schedule.nextStart(now))
        val keepOn = schedule.keepScreenOnWhenIdle
        TaskBus.update { it.copy(idleUntilMs = until, idleKeepScreen = keepOn) }
        if (keepOn && !overlayDismissed && !OverlayService.isRunning) OverlayService.start(service)
        val locked = NodeTools.foreground(service) == Foreground.LOCKED
        if (keepOn && locked && !idleScreenWarned) {
            idleScreenWarned = true
            EventLog.add(service, EventType.IDLE_SCREEN_OFF)
            Alerts.post(service, R.string.alert_idle_screen_off)
        }
    }
}
