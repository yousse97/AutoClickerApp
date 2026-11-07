package com.autoclicker.chattask.data

import android.content.Context
import com.autoclicker.chattask.core.CommandEntry
import com.autoclicker.chattask.core.DailySchedule
import com.autoclicker.chattask.core.OperatingMode
import com.autoclicker.chattask.core.ReactiveConfig
import com.autoclicker.chattask.core.TimerOffset
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.security.SecureStore
import org.json.JSONArray
import org.json.JSONObject

/** A running session saved to disk so it can resume if Android kills the process. */
data class SessionSnapshot(
    val paused: Boolean,
    val sent: Int,
    val failed: Int,
    val popups: Int,
    val savedAtWallMs: Long,
    val timers: List<TimerOffset?>,
)

class SettingsStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val sessionPrefs =
        context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    /** Old Work and Rest times cannot become clock times, so the user is asked to pick them. */
    var legacySchedulePrompt: Boolean
        get() = prefs.contains(KEY_DURATION) && !prefs.getBoolean(KEY_SCHEDULE_PROMPT_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_SCHEDULE_PROMPT_DONE, !value).apply()

    /** Written only by the mode choice, which refuses while a session runs. Pages never touch it. */
    var operatingMode: OperatingMode
        get() = OperatingMode.fromStored(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var returnBeforeSend: Boolean
        get() = prefs.getBoolean(KEY_RETURN_BEFORE_SEND, true)
        set(value) = prefs.edit().putBoolean(KEY_RETURN_BEFORE_SEND, value).apply()

    var overlayX: Int
        get() = prefs.getInt(KEY_OVERLAY_X, NO_POSITION)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_X, value).apply()

    var overlayY: Int
        get() = prefs.getInt(KEY_OVERLAY_Y, NO_POSITION)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_Y, value).apply()

    /** A stop that needs a manual restart holds until this time. */
    var blockedUntilMs: Long
        get() = prefs.getLong(KEY_BLOCKED_UNTIL, 0)
        set(value) = prefs.edit().putLong(KEY_BLOCKED_UNTIL, value).apply()

    var watchdogMisses: Int
        get() = prefs.getInt(KEY_WATCHDOG_MISSES, 0)
        set(value) = prefs.edit().putInt(KEY_WATCHDOG_MISSES, value).apply()

    var lastExitLoggedMs: Long
        get() = prefs.getLong(KEY_LAST_EXIT, 0)
        set(value) = prefs.edit().putLong(KEY_LAST_EXIT, value).apply()

    var overlaySizeIndex: Int
        get() = prefs.getInt(KEY_OVERLAY_SIZE, OVERLAY_SIZE_MEDIUM)
        set(value) = prefs.edit().putInt(KEY_OVERLAY_SIZE, value).apply()

    fun loadConfig(): TaskConfig {
        val defaults = TaskConfig()
        // Settings saved before version 1.1 hold a single command and lack the x keywords.
        val legacy = !prefs.contains(KEY_COMMANDS)
        val legacyInterval = prefs.getInt(KEY_INTERVAL, LEGACY_INTERVAL_SEC)
        val legacySchedule = DailySchedule(
            prefs.getInt(KEY_SCHEDULE_START, DailySchedule.UNSET),
            prefs.getInt(KEY_SCHEDULE_END, DailySchedule.UNSET),
        ).takeIf { prefs.getBoolean(KEY_SCHEDULE_ENABLED, false) }
        val legacyCommand = prefs.getString(KEY_COMMAND, null)
            ?.let { listOf(CommandEntry(it, legacyInterval)) }
        return TaskConfig(
            mode = operatingMode,
            commands = prefs.getString(KEY_COMMANDS, null)
                ?.let { decodeCommands(it, legacyInterval, legacySchedule) }
                ?: legacyCommand ?: defaults.commands,
            jitterMinSec = prefs.getInt(KEY_JITTER_MIN, defaults.jitterMinSec),
            jitterMaxSec = prefs.getInt(KEY_JITTER_MAX, defaults.jitterMaxSec),
            popupKeywords = prefs.getString(KEY_KEYWORDS, null)?.let(TaskConfig::parseKeywords)
                ?.let { if (legacy) (it + LEGACY_NEW_KEYWORDS).distinct() else it }
                ?: defaults.popupKeywords,
            keepScreenWhenIdle = prefs.getBoolean(KEY_IDLE_SCREEN, true),
            vibrateBeforeSend = prefs.getBoolean(KEY_VIBRATE, true),
            returnBeforeSend = returnBeforeSend,
            targetRoom = secure.taskRoom,
            restrictToRoom = secure.restrictTaskRoom,
            reactive = ReactiveConfig(
                triggers = prefs.getString(KEY_REACTIVE_TRIGGERS, null)?.let(TaskConfig::parseKeywords)
                    ?: defaults.reactive.triggers,
                command = prefs.getString(KEY_REACTIVE_COMMAND, null).orEmpty(),
                restrictToRoom = secure.restrictReactiveRoom,
                room = secure.reactiveRoom,
                returnBeforeSend = prefs.getBoolean(KEY_REPLY_RETURN, defaults.reactive.returnBeforeSend),
                hours = prefs.getString(KEY_REPLY_HOURS, null)?.let(::decodeReplyHours)
                    ?: defaults.reactive.hours,
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
            .putInt(KEY_JITTER_MIN, config.jitterMinSec)
            .putInt(KEY_JITTER_MAX, config.jitterMaxSec)
            .putString(KEY_KEYWORDS, config.popupKeywords.joinToString("\n"))
            .putBoolean(KEY_SCHEDULE_ENABLED, false)
            .putBoolean(KEY_IDLE_SCREEN, config.keepScreenWhenIdle)
            .putBoolean(KEY_VIBRATE, config.vibrateBeforeSend)
            .putBoolean(KEY_RETURN_BEFORE_SEND, config.returnBeforeSend)
            .putString(KEY_REACTIVE_TRIGGERS, config.reactive.triggers.joinToString("\n"))
            .putString(KEY_REACTIVE_COMMAND, config.reactive.command)
            .putBoolean(KEY_REPLY_RETURN, config.reactive.returnBeforeSend)
            .putString(KEY_REPLY_HOURS, encodeReplyHours(config.reactive.hours))
            .putBoolean(KEY_SKIP_LISTS, config.skipListItems)
            .putBoolean(KEY_KEEP_SCREEN, config.keepScreenOn)
            .apply()
    }

    fun saveSession(snapshot: SessionSnapshot) {
        sessionPrefs.edit()
            .putBoolean(KEY_S_ACTIVE, true)
            .putBoolean(KEY_S_PAUSED, snapshot.paused)
            .putInt(KEY_S_SENT, snapshot.sent)
            .putInt(KEY_S_FAILED, snapshot.failed)
            .putInt(KEY_S_POPUPS, snapshot.popups)
            .putLong(KEY_S_SAVED_AT, snapshot.savedAtWallMs)
            .putString(KEY_S_TIMERS, encodeTimers(snapshot.timers))
            .apply()
    }

    fun loadSession(): SessionSnapshot? {
        if (!sessionPrefs.contains(KEY_S_ACTIVE)) return null
        return SessionSnapshot(
            paused = sessionPrefs.getBoolean(KEY_S_PAUSED, false),
            sent = sessionPrefs.getInt(KEY_S_SENT, 0),
            failed = sessionPrefs.getInt(KEY_S_FAILED, 0),
            popups = sessionPrefs.getInt(KEY_S_POPUPS, 0),
            savedAtWallMs = sessionPrefs.getLong(KEY_S_SAVED_AT, 0),
            timers = sessionPrefs.getString(KEY_S_TIMERS, null)?.let(::decodeTimers).orEmpty(),
        )
    }

    fun clearSession() {
        sessionPrefs.edit().clear().apply()
    }

    companion object {
        const val NO_POSITION = Int.MIN_VALUE
        const val OVERLAY_SIZE_SMALL = 0
        const val OVERLAY_SIZE_MEDIUM = 1
        const val OVERLAY_SIZE_LARGE = 2

        fun encodeCommands(commands: List<CommandEntry>): String {
            val array = JSONArray()
            commands.forEach {
                array.put(
                    JSONObject().put(JSON_TEXT, it.text).put(JSON_INTERVAL, it.intervalSec)
                        .put(JSON_ALL_DAY, it.allDay).put(JSON_START, it.startMinute)
                        .put(JSON_END, it.endMinute),
                )
            }
            return array.toString()
        }

        /** Auto reply's hours use the same stored form as a command's. */
        fun encodeReplyHours(hours: CommandEntry): String = encodeCommands(listOf(hours))

        fun decodeReplyHours(json: String): CommandEntry? = decodeCommands(json)?.firstOrNull()

        fun encodeTimers(timers: List<TimerOffset?>): String {
            val array = JSONArray()
            timers.forEach { timer ->
                array.put(
                    timer?.let {
                        JSONObject().put(JSON_ANCHOR, it.anchorInMs).put(JSON_NEXT, it.nextInMs)
                            .put(JSON_HELD, it.held)
                    } ?: JSONObject.NULL,
                )
            }
            return array.toString()
        }

        fun decodeTimers(json: String): List<TimerOffset?>? = runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                array.optJSONObject(index)?.let {
                    TimerOffset(it.getLong(JSON_ANCHOR), it.getLong(JSON_NEXT), it.getBoolean(JSON_HELD))
                }
            }
        }.getOrNull()

        /**
         * Returns null for unreadable data, so the caller falls back to its default. Commands saved
         * before each had its own timing take the old shared interval and any old daily hours.
         */
        fun decodeCommands(
            json: String,
            legacyIntervalSec: Int = 0,
            legacySchedule: DailySchedule? = null,
        ): List<CommandEntry>? = runCatching {
            val array = JSONArray(json)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val isLegacy = !item.has(JSON_INTERVAL)
                val window = legacySchedule?.takeIf { isLegacy && it.isComplete }
                CommandEntry(
                    text = item.getString(JSON_TEXT),
                    intervalSec = item.optInt(JSON_INTERVAL, legacyIntervalSec),
                    allDay = window == null && item.optBoolean(JSON_ALL_DAY, true),
                    startMinute = window?.startMinute ?: item.optInt(JSON_START, DailySchedule.UNSET),
                    endMinute = window?.endMinute ?: item.optInt(JSON_END, DailySchedule.UNSET),
                )
            }
        }.getOrNull()

        private const val JSON_TEXT = "text"
        private const val JSON_INTERVAL = "interval"
        private const val JSON_ALL_DAY = "all_day"
        private const val JSON_START = "start"
        private const val JSON_END = "end"
        private const val JSON_ANCHOR = "anchor"
        private const val JSON_NEXT = "next"
        private const val JSON_HELD = "held"
        private const val LEGACY_INTERVAL_SEC = 610
        private val LEGACY_NEW_KEYWORDS = listOf("x", "X")

        private const val KEY_COMMAND = "command"
        private const val KEY_COMMANDS = "commands"
        private const val KEY_OVERLAY_SIZE = "overlay_size_setting"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_S_TIMERS = "timers"
        private const val KEY_BLOCKED_UNTIL = "blocked_until"
        private const val KEY_LAST_EXIT = "last_exit"
        private const val KEY_WATCHDOG_MISSES = "watchdog_misses"
        private const val KEY_SCHEDULE_ENABLED = "schedule_enabled"
        private const val KEY_SCHEDULE_START = "schedule_start"
        private const val KEY_SCHEDULE_END = "schedule_end"
        private const val KEY_IDLE_SCREEN = "idle_screen"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_SCHEDULE_PROMPT_DONE = "schedule_prompt_done"
        private const val KEY_RETURN_BEFORE_SEND = "return_before_send"
        private const val KEY_MODE = "operating_mode"
        private const val KEY_REPLY_RETURN = "reply_return_before_send"
        private const val KEY_REPLY_HOURS = "reply_hours"
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
        private const val KEY_S_SENT = "sent"
        private const val KEY_S_FAILED = "failed"
        private const val KEY_S_POPUPS = "popups"
        private const val KEY_S_SAVED_AT = "saved_at"
    }
}
