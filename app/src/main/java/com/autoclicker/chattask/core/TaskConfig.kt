package com.autoclicker.chattask.core

/** What a session does. Only one runs at a time, and each keeps its settings while the other is used. */
enum class OperatingMode {
    SCHEDULED,
    AUTO_REPLY,
    ;

    companion object {
        /** Anything unreadable, including a device updated from an older version, means Scheduled. */
        fun fromStored(name: String?): OperatingMode = entries.firstOrNull { it.name == name } ?: SCHEDULED
    }
}

/** User-tunable settings for one automation session. */
data class TaskConfig(
    val mode: OperatingMode = OperatingMode.SCHEDULED,
    /** Starts as one empty row: each user enters their own commands. */
    val commands: List<CommandEntry> = listOf(CommandEntry("")),
    val jitterMinSec: Int = 2,
    val jitterMaxSec: Int = 4,
    val popupKeywords: List<String> = DEFAULT_POPUP_KEYWORDS,
    /** The channel exactly as this user's account shows it. Never given a default. */
    val targetRoom: String = "",
    /** Send only while the chat header shows [targetRoom]. */
    val restrictToRoom: Boolean = false,
    val reactive: ReactiveConfig = ReactiveConfig(),
    val keepScreenWhenIdle: Boolean = true,
    val vibrateBeforeSend: Boolean = true,
    /** Bring the user to WOLF 5 seconds before a send. Otherwise sends wait for them. */
    val returnBeforeSend: Boolean = true,
    /** Ignore matches inside scrolling lists, so chat messages reading "تم" are never clicked. */
    val skipListItems: Boolean = true,
    val keepScreenOn: Boolean = false,
) {
    /** What Home edits. Everything else, including each command's hours, stays as saved. */
    fun withQuickCommands(edited: List<CommandEntry>, room: String) =
        copy(commands = edited, targetRoom = room.trim())

    fun withQuickReply(triggerText: String, command: String, room: String) = copy(
        reactive = reactive.copy(
            triggers = parseKeywords(triggerText),
            command = command.trim(),
            room = room.trim(),
        ),
    )

    private val repliesOnly: Boolean get() = mode == OperatingMode.AUTO_REPLY

    /** The hours that drive the schedule in the current mode. */
    val activeHours: List<CommandEntry> get() = if (repliesOnly) listOf(reactive.hours) else commands

    /** The reply settings to act on, or null in Scheduled mode, where nothing may reply. */
    val replies: ReactiveConfig? get() = reactive.takeIf { repliesOnly }

    val activeRestrict: Boolean get() = repliesOnly || restrictToRoom
    val activeRoom: String get() = if (repliesOnly) reactive.room else targetRoom
    val activeReturn: Boolean get() = if (repliesOnly) reactive.returnBeforeSend else returnBeforeSend

    /** Daily without valid times is valid to save: it just waits and sends nothing. */
    val needsSchedule: Boolean get() = activeHours.any { it.needsSchedule }

    /** Only the selected mode has to be complete. The other keeps whatever was saved for it. */
    fun validate(): List<ConfigError> = buildList {
        val rooms = listOf(targetRoom, reactive.room)
        if (rooms.any { it.trim().length > MAX_ROOM_LENGTH }) add(ConfigError.ROOM_TOO_LONG)
        addAll(if (repliesOnly) replyErrors() else commandErrors())
    }

    private fun commandErrors(): List<ConfigError> = buildList {
        val texts = commands.map { it.text.trim() }
        if (texts.isEmpty() || texts.any { it.isEmpty() }) add(ConfigError.EMPTY_COMMAND)
        if (texts.any { it.length > MAX_COMMAND_LENGTH }) add(ConfigError.COMMAND_TOO_LONG)
        if (commands.size > MAX_COMMANDS) add(ConfigError.TOO_MANY_COMMANDS)
        val intervals = commands.map { it.intervalSec }
        if (intervals.any { it < MIN_INTERVAL_SEC }) add(ConfigError.INTERVAL_TOO_SHORT)
        val shortest = intervals.minOrNull() ?: MIN_INTERVAL_SEC
        if (jitterMinSec < 0 || jitterMaxSec < jitterMinSec) add(ConfigError.JITTER_RANGE)
        val intervalsSet = shortest >= MIN_INTERVAL_SEC
        if (intervalsSet && jitterMaxSec + MIN_JITTER_MARGIN_SEC > shortest) add(ConfigError.JITTER_TOO_LARGE)
        if (restrictToRoom && targetRoom.isBlank()) add(ConfigError.ROOM_REQUIRED)
    }

    private fun replyErrors(): List<ConfigError> = buildList {
        if (reactive.triggers.isEmpty()) add(ConfigError.REACTIVE_NO_TRIGGER)
        if (reactive.command.isBlank()) add(ConfigError.REACTIVE_NO_COMMAND)
        if (reactive.command.trim().length > MAX_COMMAND_LENGTH) add(ConfigError.COMMAND_TOO_LONG)
        if (reactive.room.isBlank()) add(ConfigError.REACTIVE_ROOM_REQUIRED)
    }

    companion object {
        const val MIN_INTERVAL_SEC = 10
        const val MIN_JITTER_MARGIN_SEC = 5
        const val MAX_COMMAND_LENGTH = 1000
        const val MAX_COMMANDS = 5
        const val MAX_ROOM_LENGTH = 64

        val DEFAULT_POPUP_KEYWORDS = listOf("تم", "إغلاق", "اغلاق", "close", "done", "x", "X", "×", "✕")

        /** Splits on Latin commas, Arabic commas and new lines. */
        fun parseKeywords(raw: String): List<String> =
            raw.split(',', '،', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}

enum class ConfigError {
    EMPTY_COMMAND,
    COMMAND_TOO_LONG,
    TOO_MANY_COMMANDS,
    INTERVAL_TOO_SHORT,
    JITTER_RANGE,
    JITTER_TOO_LARGE,
    ROOM_TOO_LONG,
    ROOM_REQUIRED,
    REACTIVE_NO_TRIGGER,
    REACTIVE_NO_COMMAND,
    REACTIVE_ROOM_REQUIRED,
}
