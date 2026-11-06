package com.autoclicker.chattask.data

import android.content.Context
import com.autoclicker.chattask.core.ScheduleOffsets
import com.autoclicker.chattask.core.TaskConfig

/** A running session saved to disk so it can resume if Android kills the process. */
data class SessionSnapshot(
    val target: String,
    val paused: Boolean,
    val offsets: ScheduleOffsets,
    val sent: Int,
    val failed: Int,
    val popups: Int,
    val savedAtWallMs: Long,
)

class SettingsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val sessionPrefs =
        context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun loadConfig(): TaskConfig {
        val defaults = TaskConfig()
        return TaskConfig(
            command = prefs.getString(KEY_COMMAND, null) ?: defaults.command,
            intervalSec = prefs.getInt(KEY_INTERVAL, defaults.intervalSec),
            durationSec = prefs.getInt(KEY_DURATION, defaults.durationSec),
            jitterMinSec = prefs.getInt(KEY_JITTER_MIN, defaults.jitterMinSec),
            jitterMaxSec = prefs.getInt(KEY_JITTER_MAX, defaults.jitterMaxSec),
            popupKeywords = prefs.getString(KEY_KEYWORDS, null)?.let(TaskConfig::parseKeywords)
                ?: defaults.popupKeywords,
            targetPackage = prefs.getString(KEY_TARGET, null) ?: defaults.targetPackage,
            skipListItems = prefs.getBoolean(KEY_SKIP_LISTS, defaults.skipListItems),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN, defaults.keepScreenOn),
        )
    }

    fun saveConfig(config: TaskConfig) {
        prefs.edit()
            .putString(KEY_COMMAND, config.command)
            .putInt(KEY_INTERVAL, config.intervalSec)
            .putInt(KEY_DURATION, config.durationSec)
            .putInt(KEY_JITTER_MIN, config.jitterMinSec)
            .putInt(KEY_JITTER_MAX, config.jitterMaxSec)
            .putString(KEY_KEYWORDS, config.popupKeywords.joinToString("\n"))
            .putString(KEY_TARGET, config.targetPackage)
            .putBoolean(KEY_SKIP_LISTS, config.skipListItems)
            .putBoolean(KEY_KEEP_SCREEN, config.keepScreenOn)
            .apply()
    }

    fun saveSession(snapshot: SessionSnapshot) {
        sessionPrefs.edit()
            .putString(KEY_S_TARGET, snapshot.target)
            .putBoolean(KEY_S_PAUSED, snapshot.paused)
            .putLong(KEY_S_ANCHOR, snapshot.offsets.anchorInMs)
            .putLong(KEY_S_NEXT, snapshot.offsets.nextInMs)
            .putLong(KEY_S_END, snapshot.offsets.endInMs)
            .putLong(KEY_S_INTERVAL, snapshot.offsets.intervalMs)
            .putInt(KEY_S_SENT, snapshot.sent)
            .putInt(KEY_S_FAILED, snapshot.failed)
            .putInt(KEY_S_POPUPS, snapshot.popups)
            .putLong(KEY_S_SAVED_AT, snapshot.savedAtWallMs)
            .apply()
    }

    fun loadSession(): SessionSnapshot? {
        val target = sessionPrefs.getString(KEY_S_TARGET, null) ?: return null
        return SessionSnapshot(
            target = target,
            paused = sessionPrefs.getBoolean(KEY_S_PAUSED, false),
            offsets = ScheduleOffsets(
                anchorInMs = sessionPrefs.getLong(KEY_S_ANCHOR, 0),
                nextInMs = sessionPrefs.getLong(KEY_S_NEXT, 0),
                endInMs = sessionPrefs.getLong(KEY_S_END, 0),
                intervalMs = sessionPrefs.getLong(KEY_S_INTERVAL, 0),
            ),
            sent = sessionPrefs.getInt(KEY_S_SENT, 0),
            failed = sessionPrefs.getInt(KEY_S_FAILED, 0),
            popups = sessionPrefs.getInt(KEY_S_POPUPS, 0),
            savedAtWallMs = sessionPrefs.getLong(KEY_S_SAVED_AT, 0),
        )
    }

    fun clearSession() {
        sessionPrefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_COMMAND = "command"
        const val KEY_INTERVAL = "interval_sec"
        const val KEY_DURATION = "duration_sec"
        const val KEY_JITTER_MIN = "jitter_min_sec"
        const val KEY_JITTER_MAX = "jitter_max_sec"
        const val KEY_KEYWORDS = "popup_keywords"
        const val KEY_TARGET = "target_package"
        const val KEY_SKIP_LISTS = "skip_list_items"
        const val KEY_KEEP_SCREEN = "keep_screen_on"

        const val KEY_S_TARGET = "target"
        const val KEY_S_PAUSED = "paused"
        const val KEY_S_ANCHOR = "anchor_in"
        const val KEY_S_NEXT = "next_in"
        const val KEY_S_END = "end_in"
        const val KEY_S_INTERVAL = "interval"
        const val KEY_S_SENT = "sent"
        const val KEY_S_FAILED = "failed"
        const val KEY_S_POPUPS = "popups"
        const val KEY_S_SAVED_AT = "saved_at"
    }
}
