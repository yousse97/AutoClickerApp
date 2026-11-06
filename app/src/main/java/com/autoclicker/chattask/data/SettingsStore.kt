package com.autoclicker.chattask.data

import android.content.Context
import com.autoclicker.chattask.core.CommandEntry
import com.autoclicker.chattask.core.DailySchedule
import com.autoclicker.chattask.core.ReactiveConfig
import com.autoclicker.chattask.core.ScheduleOffsets
import com.autoclicker.chattask.core.SendMode
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.security.SecureStore
import org.json.JSONArray
import org.json.JSONObject

/** A running session saved to disk so it can resume if Android kills the process. */
data class SessionSnapshot(
    val paused: Boolean,
    val offsets: ScheduleOffsets,
    val sent: Int,
    val failed: Int,
    val popups: Int,
    val savedAtWallMs: Long,
    val resting: Boolean,
    val workCycle: Int,
)

class SettingsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val sessionPrefs =
        context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    /** A stop that needs a manual restart holds until this time. */
    var blockedUntilMs: Long
        get() = prefs.getLong(KEY_BLOCKED_UNTIL, 0)
        set(value) = prefs.edit().putLong(KEY_BLOCKED_UNTIL, value).apply()

    var watchdogWaiting: Boolean
        get() = prefs.getBoolean(KEY_WATCHDOG_WAITING, false)
        set(value) = prefs.edit().putBoolean(KEY_WATCHDOG_WAITING, value).apply()

    var lastExitLoggedMs: Long
        get() = prefs.getLong(KEY_LAST_EXIT, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_EXIT, value).apply()

    var overlaySizeIndex: Int
        get() = prefs.getInt(KEY_OVERLAY_SIZE, 1)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_SIZE, value).apply()

    fun loadConfig(): TaskConfig {
        val defaults = TaskConfig()
        // Settings saved before version 1.1 hold a single command and lack the x keywords.
        val legacy = !prefs.contains(KEY_COMMANDS)
        val legacyCommand = prefs.getString(KEY_COMMAND, null)?.let { listOf(CommandEntry(it)) }
        return TaskConfig(
            commands = prefs.getString(KEY_COMMANDS, null)?.let(::decodeCommands)
                ?: legacyCommand ?: defaults.commands,
            intervalSec = prefs.getInt(KEY_INTERVAL, defaults.intervalSec),
            workDurationSec = prefs.getInt(KEY_DURATION, defaults.workDurationSec),
            restDurationSec = prefs.getInt(KEY_REST, defaults.restDurationSec),
            jitterMinSec = prefs.getInt(KEY_JITTER_MIN, defaults.jitterMinSec),
            jitterMaxSec = prefs.getInt(KEY_JITTER_MAX, defaults.jitterMaxSec),
            popupKeywords = prefs.getString(KEY_KEYWORDS, null)?.let(TaskConfig::parseKeywords)
                ?.let { if (legacy) (it + LEGACY_NEW_KEYWORDS).distinct() else it }
                ?: defaults.popupKeywords,
            schedule = DailySchedule(
                enabled = prefs.getBoolean(KEY_SCHEDULE_ENABLED, false),
                startMinute = prefs.getInt(KEY_SCHEDULE_START, DailySchedule.UNSET),
                endMinute = prefs.getInt(KEY_SCHEDULE_END, DailySchedule.UNSET),
                keepScreenOnWhenIdle = prefs.getBoolean(KEY_IDLE_SCREEN, true),
            ),
            vibrateBeforeSend = prefs.getBoolean(KEY_VIBRATE, true),
            targetRoom = secure.taskRoom,
            restrictToRoom = secure.restrictTaskRoom,
            reactive = ReactiveConfig(
                enabled = prefs.getBoolean(KEY_REACTIVE_ENABLED, false),
                triggers = prefs.getString(KEY_REACTIVE_TRIGGERS, null)?.let(TaskConfig::parseKeywords)
                    ?: defaults.reactive.triggers,
                command = prefs.getString(KEY_REACTIVE_COMMAND, null).orEmpty(),
                restrictToRoom = secure.restrictReactiveRoom,
                room = secure.reactiveRoom,
            ),
            skipListItems = prefs.getBoolean(KEY_SKIP_LISTS, defaults.skipListItems),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_SCREEN, defaults.keepScreenOn),
        )
    }

    fun saveConfig(config: TaskConfig) {
        secure.taskRoom = config.targetRoom
        secure.restrictTaskRoom = config.restrictToRoom
        secure.reactiveRoom = config.reactive.room
        secure.restrictReactiveRoom = config.reactive.restrictToRoom
        prefs.edit()
            .putString(KEY_COMMANDS, encodeCommands(config.commands))
            .putInt(KEY_INTERVAL, config.intervalSec)
            .putInt(KEY_DURATION, config.workDurationSec)
            .putInt(KEY_REST, config.restDurationSec)
            .putInt(KEY_JITTER_MIN, config.jitterMinSec)
            .putInt(KEY_JITTER_MAX, config.jitterMaxSec)
            .putString(KEY_KEYWORDS, config.popupKeywords.joinToString("\n"))
            .putBoolean(KEY_SCHEDULE_ENABLED, config.schedule.enabled)
            .putInt(KEY_SCHEDULE_START, config.schedule.startMinute)
            .putInt(KEY_SCHEDULE_END, config.schedule.endMinute)
            .putBoolean(KEY_IDLE_SCREEN, config.schedule.keepScreenOnWhenIdle)
            .putBoolean(KEY_VIBRATE, config.vibrateBeforeSend)
            .putBoolean(KEY_REACTIVE_ENABLED, config.reactive.enabled)
            .putString(KEY_REACTIVE_TRIGGERS, config.reactive.triggers.joinToString("\n"))
            .putString(KEY_REACTIVE_COMMAND, config.reactive.command)
            .putBoolean(KEY_SKIP_LISTS, config.skipListItems)
            .putBoolean(KEY_KEEP_SCREEN, config.keepScreenOn)
            .apply()
    }

    fun saveSession(snapshot: SessionSnapshot) {
        sessionPrefs.edit()
            .putBoolean(KEY_S_ACTIVE, true)
            .putBoolean(KEY_S_PAUSED, snapshot.paused)
            .putLong(KEY_S_ANCHOR, snapshot.offsets.anchorInMs)
            .putLong(KEY_S_NEXT, snapshot.offsets.nextInMs)
            .putLong(KEY_S_END, snapshot.offsets.endInMs)
            .putLong(KEY_S_INTERVAL, snapshot.offsets.intervalMs)
            .putInt(KEY_S_SENT, snapshot.sent)
            .putInt(KEY_S_FAILED, snapshot.failed)
            .putInt(KEY_S_POPUPS, snapshot.popups)
            .putLong(KEY_S_SAVED_AT, snapshot.savedAtWallMs)
            .putBoolean(KEY_S_RESTING, snapshot.resting)
            .putInt(KEY_S_WORK_CYCLE, snapshot.workCycle)
            .apply()
    }

    fun loadSession(): SessionSnapshot? {
        if (!sessionPrefs.contains(KEY_S_ACTIVE)) return null
        return SessionSnapshot(
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
            resting = sessionPrefs.getBoolean(KEY_S_RESTING, false),
            workCycle = sessionPrefs.getInt(KEY_S_WORK_CYCLE, 1),
        )
    }

    fun clearSession() {
        sessionPrefs.edit().clear().apply()
    }

    companion object {
        fun encodeCommands(commands: List<CommandEntry>): String {
            val array = JSONArray()
            commands.forEach {
                array.put(
                    JSONObject().put(JSON_TEXT, it.text).put(JSON_MODE, it.mode.name)
                        .put(JSON_DELAY, it.delaySec),
                )
            }
            return array.toString()
        }

        /** Returns null for unreadable data, so the caller falls back to its default. */
        fun decodeCommands(json: String): List<CommandEntry>? = runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                CommandEntry(
                    text = item.getString(JSON_TEXT),
                    mode = SendMode.valueOf(item.getString(JSON_MODE)),
                    delaySec = item.getInt(JSON_DELAY),
                )
            }
        }.getOrNull()

        private const val JSON_TEXT = "text"
        private const val JSON_MODE = "mode"
        private const val JSON_DELAY = "delay"
        private val LEGACY_NEW_KEYWORDS = listOf("x", "X")

        private const val KEY_COMMAND = "command"
        private const val KEY_COMMANDS = "commands"
        private const val KEY_REST = "rest_sec"
        private const val KEY_OVERLAY_SIZE = "overlay_size"
        private const val KEY_BLOCKED_UNTIL = "blocked_until"
        private const val KEY_LAST_EXIT = "last_exit"
        private const val KEY_WATCHDOG_WAITING = "watchdog_waiting"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_SCHEDULE_START = "schedule_start"
        private const val KEY_SCHEDULE_END = "schedule_end"
        private const val KEY_IDLE_SCREEN = "idle_screen"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_REACTIVE_ENABLED = "reactive_enabled"
        private const val KEY_REACTIVE_TRIGGERS = "reactive_triggers"
        private const val KEY_REACTIVE_COMMAND = "reactive_command"
        private const val KEY_INTERVAL = "interval_sec"
        private const val KEY_DURATION = "duration_sec"
        private const val KEY_JITTER_MIN = "jitter_min_sec"
        private const val KEY_JITTER_MAX = "jitter_max_sec"
        private const val KEY_KEYWORDS = "popup_keywords"
        private const val KEY_SKIP_LISTS = "skip_list_items"
        private const val KEY_KEEP_SCREEN = "keep_screen_on"

        private const val KEY_S_ACTIVE = "active"
        private const val KEY_S_PAUSED = "paused"
        private const val KEY_S_ANCHOR = "anchor_in"
        private const val KEY_S_NEXT = "next_in"
        private const val KEY_S_END = "end_in"
        private const val KEY_S_INTERVAL = "interval"
        private const val KEY_S_SENT = "sent"
        private const val KEY_S_FAILED = "failed"
        private const val KEY_S_POPUPS = "popups"
        private const val KEY_S_SAVED_AT = "saved_at"
        private const val KEY_S_RESTING = "resting"
        private const val KEY_S_WORK_CYCLE = "work_cycle"
    }
}
