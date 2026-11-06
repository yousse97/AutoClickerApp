package com.autoclicker.chattask.core

/** Settings for YOUSE 2, which answers a trigger phrase instead of following a timer. */
data class ReactiveConfig(
    val enabled: Boolean = false,
    val triggers: List<String> = DEFAULT_TRIGGERS,
    val command: String = "",
    val restrictToRoom: Boolean = false,
    val room: String = "",
) {
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
