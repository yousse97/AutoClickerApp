package com.autoclicker.chattask.core

/**
 * Everything the log can record. Events hold a type and a time and have no text field, which is
 * what keeps chat content and screen text out of the log.
 */
enum class EventType(val isError: Boolean = false) {
    SESSION_STARTED,
    SESSION_STOPPED,
    SESSION_RESTORED,
    RESTORE_FAILED(isError = true),
    SENT,
    SEND_FAILED(isError = true),
    HELD_LOCKED,
    HELD_OTHER_APP,
    HELD_NOT_CHANNEL,
    HELD_WRONG_ROOM,
    RELAUNCH_OK,
    RELAUNCH_FAILED(isError = true),
    ROOM_NOT_FOUND(isError = true),
    ROOM_AMBIGUOUS(isError = true),
    TAB_NOT_FOUND(isError = true),
    STOPPED_LOCKED(isError = true),
    STOPPED_RELAUNCH(isError = true),
    STOPPED_FAILURES(isError = true),
    REACTION_SENT,
    REACTION_FAILED(isError = true),
    POPUP_TRIGGER,
    POPUP_SENDER_UNVERIFIED,
    SCHEDULE_START,
    SCHEDULE_STOP,
    SCHEDULE_MISSED(isError = true),
    IDLE_SCREEN_OFF(isError = true),
    CHECK_PASSED,
    CHECK_FAILED,
    APP_KILLED_MEMORY(isError = true),
    APP_KILLED_SYSTEM(isError = true),
    APP_CRASHED(isError = true),
    APP_STOPPED_BY_USER(isError = true),
}

private val ERROR_STOPS = setOf(
    EventType.STOPPED_LOCKED,
    EventType.STOPPED_RELAUNCH,
    EventType.STOPPED_FAILURES,
    EventType.RESTORE_FAILED,
    EventType.SCHEDULE_MISSED,
)
private val SESSION_ENDS = ERROR_STOPS + setOf(
    EventType.SESSION_STARTED,
    EventType.SESSION_STOPPED,
    EventType.SESSION_RESTORED,
    EventType.SCHEDULE_START,
)

data class LogEvent(val timeMs: Long, val type: EventType)

/** The newest [capacity] events, oldest first. */
class EventRing(initial: List<LogEvent> = emptyList(), private val capacity: Int = 200) {
    private val events = ArrayDeque(initial.takeLast(capacity))

    val all: List<LogEvent> get() = events.toList()

    fun add(event: LogEvent) {
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
    }

    val lastSend: LogEvent? get() = events.lastOrNull { it.type == EventType.SENT }

    val lastError: LogEvent? get() = events.lastOrNull { it.type.isError }

    /** The event that ended the last session, if something other than the user ended it. */
    val lastStop: LogEvent?
        get() = events.lastOrNull { it.type in SESSION_ENDS }?.takeIf { it.type in ERROR_STOPS }

    /** Failed sends since the last successful one, ignoring everything that is not a send. */
    val consecutiveFailures: Int
        get() = events.toList().asReversed()
            .filter { it.type == EventType.SENT || it.type == EventType.SEND_FAILED }
            .takeWhile { it.type == EventType.SEND_FAILED }
            .size
}
