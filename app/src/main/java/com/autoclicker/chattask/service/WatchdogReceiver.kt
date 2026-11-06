package com.autoclicker.chattask.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.data.EventLog
import com.autoclicker.chattask.data.SettingsStore
import java.time.LocalDateTime

/**
 * Checks on a saved session every 5 minutes and after a reboot.
 *
 * Android rebinds the accessibility service by itself, and that service restores the session. This
 * receiver covers the case where that did not happen, so the user is told instead of left guessing.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = SettingsStore(context)
        val config = store.loadConfig()
        ScheduleKeeper.arm(context, config.schedule)
        val service = AutoTaskService.instance
        service?.tickSchedule()
        val mustRun = config.schedule.enabled && config.schedule.isActive(LocalDateTime.now())
        val hasSession = store.loadSession() != null
        if (service != null) {
            store.watchdogWaiting = false
            if (hasSession) {
                schedule(context)
                service.ensureOverlay()
            }
        } else if (hasSession || mustRun) {
            onServiceMissing(context, store, hasSession)
        }
    }

    /** Waking this process often lets Android rebind the service, so it gets a minute first. */
    private fun onServiceMissing(context: Context, store: SettingsStore, hasSession: Boolean) {
        if (!store.watchdogWaiting) {
            store.watchdogWaiting = true
            schedule(context, GRACE_MS)
            return
        }
        store.watchdogWaiting = false
        schedule(context)
        EventLog.add(context, if (hasSession) EventType.RESTORE_FAILED else EventType.SCHEDULE_MISSED)
        Alerts.post(context, R.string.alert_restore_failed)
    }

    companion object {
        private const val INTERVAL_MS = 5 * 60_000L
        private const val GRACE_MS = 60_000L

        fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
            context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMs,
                alarmIntent(context),
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        }

        private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
