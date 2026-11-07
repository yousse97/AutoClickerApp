package com.autoclicker.chattask.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

/** One stretch of active hours: [startsInMs] is 0 once it has begun, and [id] stays the same throughout. */
data class ReplySlot(val id: Long, val startsInMs: Long)

/** Settings for Auto reply, which answers a trigger phrase instead of following a timer. */
data class ReactiveConfig(
    val triggers: List<String> = DEFAULT_TRIGGERS,
    val command: String = "",
    /** Replies go out only here, so this is required. */
    val room: String = "",
    /** Bring the user back 5 seconds before the active hours begin. */
    val returnBeforeSend: Boolean = true,
    /** Only the hours are used. Replies have no text or interval of their own here. */
    val hours: CommandEntry = CommandEntry(""),
) {
    fun isActive(now: LocalDateTime): Boolean = hours.isActive(now)

    /** The coming or current active hours, or null while Daily has no valid times. */
    fun nextSlot(now: LocalDateTime): ReplySlot? {
        if (hours.allDay) return ReplySlot(0, 0)
        val window = hours.window
        if (!window.isComplete) return null
        val startsIn = if (window.isActive(now)) 0 else Duration.between(now, window.nextStart(now)).toMillis()
        return ReplySlot(window.nextEnd(now).toEpochSecond(ZoneOffset.UTC), startsIn)
    }

    companion object {
        val DEFAULT_TRIGGERS = listOf(
            "your animal is back to full energy!",
            "Your animal is back to full energy!",
            "عاد حيوانك لطاقته الكاملة!",
        )
    }
}

/**
 * Decides when visible trigger messages mean something new happened.
 *
 * A chat message stays on screen after it arrives, so only a rise in the number of matches counts.
 * The first observation sets the baseline, which keeps old messages from firing. Not thread-safe.
 */
class ReactiveDetector(private val cooldownMs: Long = 10_000) {
    private var lastCount: Int? = null
    private var lastFiredMs: Long? = null

    fun observe(count: Int, now: Long): Boolean {
        val previous = lastCount
        lastCount = count
        if (previous == null || count <= previous) return false
        val fired = lastFiredMs
        if (fired != null && now - fired < cooldownMs) return false
        lastFiredMs = now
        return true
    }

    /** Call when the chat leaves the screen, since the next chat has its own message history. */
    fun forgetBaseline() {
        lastCount = null
    }
}
