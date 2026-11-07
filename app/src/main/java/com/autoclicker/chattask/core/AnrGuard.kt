package com.autoclicker.chattask.core

enum class AnrAction { NONE, SEEN, CLOSE, GIVE_UP }

/**
 * Handles Android's "app isn't responding" dialog for WOLF. It waits for WOLF to recover by itself,
 * then closes the dialog, and gives up after [maxAttempts] closes in a row. Not thread-safe.
 */
class AnrGuard(private val waitMs: Long = 30_000, private val maxAttempts: Int = 3) {
    private var seenSinceMs: Long? = null
    private var attempts = 0

    fun observe(showing: Boolean, now: Long): AnrAction {
        if (!showing) {
            seenSinceMs = null
            return AnrAction.NONE
        }
        val since = seenSinceMs
        if (since == null) {
            seenSinceMs = now
            return if (attempts >= maxAttempts) AnrAction.GIVE_UP else AnrAction.SEEN
        }
        if (now - since < waitMs) return AnrAction.NONE
        seenSinceMs = null
        attempts++
        return AnrAction.CLOSE
    }

    /** A send went out, so WOLF is healthy and the count starts again. */
    fun onRecovered() {
        attempts = 0
    }

    companion object {
        private val NOT_RESPONDING = listOf("responding", "لا يستجيب").map(TextNormalizer::normalize)

        /** The title must name WOLF and say it is not responding. Any other app is ignored. */
        fun namesWolf(title: CharSequence?, wolfLabel: String): Boolean {
            if (title.isNullOrBlank()) return false
            val text = TextNormalizer.normalize(title)
            val names = text.contains(TextNormalizer.normalize(wolfLabel))
            return names && NOT_RESPONDING.any { it in text }
        }
    }
}
