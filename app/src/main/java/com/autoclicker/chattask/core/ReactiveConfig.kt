package com.autoclicker.chattask.core

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

/** One stretch of active hours: [startsInMs] is 0 once it has begun, and [id] stays the same throughout. */
data class ReplySlot(val id: Long, val startsInMs: Long)

/** Settings for Auto reply, which answers a trigger phrase instead of following a timer. */
data class ReactiveConfig(
    val triggers: List<String> = emptyList(),
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
}

/** What one look at the chat found. Positions are screen pixels. */
data class ChatView(
    /** Changes whenever this is a different chat or the chat's size changed. */
    val place: String,
    /** True at either end of the chat, false in the middle, null when that cannot be read. */
    val atEnd: Boolean?,
    val matchBottoms: List<Int>,
    val viewBottom: Int,
    val viewHeight: Int,
)

/**
 * Decides when a trigger on screen is a message that has just arrived. A message cannot be dated,
 * so the proof is how it shows up: the chat stayed in one place at its end, one more match appeared
 * than before, and it is the newest thing at the bottom. Old text comes into view by scrolling or
 * by opening a page, and neither passes. Anything unclear counts as old. Not thread-safe.
 */
class NewMessageDetector(private val graceMs: Long = 1_500) {
    private var lastPlace: String? = null
    private var baseline: Int? = null
    private var leftEndAtMs: Long? = null

    /** False on the first look at a screen, when nothing on it can be called new. */
    var settled = false
        private set

    fun observe(view: ChatView?, now: Long): Boolean {
        val place = view?.place
        settled = place == lastPlace && place != null
        if (!settled) forget()
        lastPlace = place
        if (view == null || view.atEnd == null) {
            forget()
            return false
        }
        if (!view.atEnd) return onAwayFromEnd(now)
        leftEndAtMs = null
        val previous = baseline
        val count = view.matchBottoms.size
        baseline = count
        return previous != null && count > previous && isAtBottom(view)
    }

    /** A new message can move the chat for a moment. Longer than that means someone is scrolling. */
    private fun onAwayFromEnd(now: Long): Boolean {
        val since = leftEndAtMs ?: now.also { leftEndAtMs = it }
        if (now - since >= graceMs) forget()
        return false
    }

    private fun isAtBottom(view: ChatView): Boolean {
        val lowest = view.matchBottoms.maxOrNull() ?: return false
        return lowest >= view.viewBottom - view.viewHeight * BOTTOM_SHARE
    }

    /** Whatever is on screen at the next look becomes the starting point. */
    fun forget() {
        baseline = null
        leftEndAtMs = null
    }

    private companion object {
        const val BOTTOM_SHARE = 0.35
    }
}
