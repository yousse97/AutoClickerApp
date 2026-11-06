package com.autoclicker.chattask.core

/**
 * Responds once to a popup message. A popup can stay up and be seen many times, so it counts as one
 * message until it has been gone for [goneMs]. Not thread-safe.
 */
class PopupTrigger(private val goneMs: Long = 3_000) {
    private var visible = false
    private var lastSeenMs = 0L

    /** Returns true only for the first sighting of a message. */
    fun observe(present: Boolean, now: Long): Boolean {
        if (present) {
            val isNew = !visible
            visible = true
            lastSeenMs = now
            return isNew
        }
        if (visible && now - lastSeenMs >= goneMs) visible = false
        return false
    }
}
