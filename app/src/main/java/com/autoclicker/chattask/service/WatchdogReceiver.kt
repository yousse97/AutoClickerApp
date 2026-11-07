package com.autoclicker.chattask.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.data.EventLog
import com.autoclicker.chattask.data.SettingsStore
import java.time.LocalDateTime

/**
 * Checks on a saved session every minute and after a reboot.
 *
 * The accessibility service restores the session once it is connected. This receiver gets it
 * connected again after a kill, and tells the user if that does not work.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = SettingsStore(context)
        val config = store.loadConfig()
        ScheduleKeeper.arm(context, config)
        val service = AutoTaskService.instance
        service?.tickSchedule()
        val now = LocalDateTime.now()
        val mustRun = config.commands.any { !it.allDay && it.isActive(now) }
        val hasSession = store.loadSession() != null
        if (service != null) {
            store.watchdogMisses = 0
            if (hasSession) {
                schedule(context)
                OverlayService.start(context)
            }
        } else if (hasSession || mustRun) {
            onServiceMissing(context, store, hasSession)
        }
    }

    /**
     * Under memory pressure Android can delay restarting a killed service by over an hour, which
     * is what used to look like a sudden stop. The app may start its own service, and the system's
     * accessibility connection then reattaches. The foreground service goes first, as it lifts the
     * limits on starting anything from the background.
     */
    private fun onServiceMissing(context: Context, store: SettingsStore, hasSession: Boolean) {
        OverlayService.start(context)
        val started = runCatching {
            context.startService(Intent(context, AutoTaskService::class.java))
        }
        Log.i(EventLog.TAG, "revive service, started=${started.isSuccess}")
        val misses = store.watchdogMisses + 1
        store.watchdogMisses = misses
        schedule(context, RETRY_MS)
        if (misses == MISSES_BEFORE_ALERT) {
            EventLog.add(context, if (hasSession) EventType.RESTORE_FAILED else EventType.SCHEDULE_MISSED)
            Alerts.post(context, R.string.alert_restore_failed)
        }
    }

    companion object {
        private const val INTERVAL_MS = 60_000L
        private const val RETRY_MS = 60_000L
        private const val MISSES_BEFORE_ALERT = 3

        fun schedule(context: Context, delayMs: Long = INTERVAL_MS) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + delayMs
            if (ScheduleKeeper.canUseExactAlarms(context)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(context))
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, alarmIntent(context))
            }
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
