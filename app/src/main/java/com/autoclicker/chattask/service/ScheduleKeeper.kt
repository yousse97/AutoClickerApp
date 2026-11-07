package com.autoclicker.chattask.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
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
    private const val ENDLESS_MS = 3650L * 24 * 3_600_000

    fun toEpochMs(time: LocalDateTime): Long = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun canUseExactAlarms(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** The earliest coming start among the commands that have hours. */
    fun nextStart(config: TaskConfig, now: LocalDateTime): LocalDateTime? = config.commands
        .filter { !it.allDay && it.window.isComplete }
        .minOfOrNull { it.window.nextStart(now) }

    /** Arms the next start or end. The service's own clock also watches while it is alive. */
    fun arm(context: Context, config: TaskConfig) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, WatchdogReceiver::class.java).setAction(ACTION_SCHEDULE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.cancel(intent)
        val now = LocalDateTime.now()
        val windows = config.commands.filter { !it.allDay && it.window.isComplete }.map { it.window }
        val next = windows.flatMap { listOf(it.nextStart(now), it.nextEnd(now)) }.minOrNull() ?: return
        if (canUseExactAlarms(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, toEpochMs(next), intent)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, toEpochMs(next), intent)
        }
    }

    /**
     * Work and rest apply only when every command runs all day. With any daily hours, the session
     * simply stays up and each command follows its own hours.
     */
    fun cyclesOf(config: TaskConfig, firstDelayMs: Long): WorkRestScheduler {
        val allDay = config.commands.all { it.allDay }
        val shortest = config.commands.minOfOrNull { it.intervalSec } ?: 0
        return WorkRestScheduler(
            workMs = if (allDay) config.workDurationSec * 1000L else ENDLESS_MS,
            restMs = if (allDay) config.restDurationSec * 1000L else 0,
            intervalMs = shortest * 1000L,
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
