package com.autoclicker.chattask.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import com.autoclicker.chattask.core.DailySchedule
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.core.WorkRestScheduler
import com.autoclicker.chattask.data.EventLog
import com.autoclicker.chattask.data.SettingsStore
import java.time.LocalDateTime
import java.time.ZoneId

/** Alarms that wake the app for daily schedule changes, and the record of why it was killed. */
object ScheduleKeeper {
    private const val ACTION_SCHEDULE = "com.autoclicker.chattask.action.SCHEDULE"

    fun toEpochMs(time: LocalDateTime): Long = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun canUseExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Arms the next start or end. The service's own clock also watches while it is alive. */
    fun arm(context: Context, schedule: DailySchedule) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, WatchdogReceiver::class.java).setAction(ACTION_SCHEDULE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.cancel(intent)
        if (!schedule.enabled || !schedule.isComplete) return
        val now = LocalDateTime.now()
        val next = minOf(schedule.nextStart(now), schedule.nextEnd(now))
        if (canUseExactAlarms(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, toEpochMs(next), intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, toEpochMs(next), intent)
        }
    }

    /** With a daily schedule, one work period lasts until the end time and there is no rest. */
    fun cyclesOf(config: TaskConfig, firstDelayMs: Long): WorkRestScheduler {
        val now = LocalDateTime.now()
        val scheduled = config.schedule.enabled && config.schedule.isActive(now)
        val untilEndMs = if (scheduled) toEpochMs(config.schedule.nextEnd(now)) - System.currentTimeMillis() else 0
        return WorkRestScheduler(
            workMs = if (scheduled) untilEndMs else config.workDurationSec * 1000L,
            restMs = if (config.schedule.enabled) 0 else config.restDurationSec * 1000L,
            intervalMs = config.intervalSec * 1000L,
            firstDelayMs = firstDelayMs,
        )
    }

    /** Adds to the log each time Android ended this process since the last check. */
    fun logProcessExits(context: Context, store: SettingsStore) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val exits = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
        val seen = store.lastExitLoggedMs
        exits.filter { it.timestamp > seen }.sortedBy { it.timestamp }.forEach { exit ->
            eventFor(exit.reason)?.let { EventLog.addAt(context, exit.timestamp, it) }
        }
        exits.maxOfOrNull { it.timestamp }?.let { store.lastExitLoggedMs = it }
    }

    private fun eventFor(reason: Int): EventType? = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> EventType.APP_KILLED_MEMORY
        ApplicationExitInfo.REASON_CRASH,
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR,
        -> EventType.APP_CRASHED
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        -> EventType.APP_STOPPED_BY_USER
        ApplicationExitInfo.REASON_OTHER,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        ApplicationExitInfo.REASON_FREEZER,
        -> EventType.APP_KILLED_SYSTEM
        else -> null
    }
}
