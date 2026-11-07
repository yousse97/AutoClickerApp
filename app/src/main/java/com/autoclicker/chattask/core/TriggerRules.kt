package com.autoclicker.chattask.core

/** Decides whether text holds a trigger. Nothing here keeps the text it is given. */
object TriggerMatcher {
    fun matches(texts: List<CharSequence?>, triggers: List<String>, ownTexts: List<String>): Boolean {
        val wanted = comparable(triggers)
        return texts.any { text -> !text.isNullOrBlank() && containsAny(text, wanted) && !isOwn(text, ownTexts) }
    }

    /** What this app sends can come back on screen, and must never set off another reply. */
    fun isOwn(text: CharSequence, ownTexts: List<String>): Boolean = containsAny(text, comparable(ownTexts))

    private fun containsAny(text: CharSequence, wanted: List<String>): Boolean {
        val normalized = TextNormalizer.normalize(text)
        return wanted.any { it in normalized }
    }

    /** A blank entry would match everything, so it never takes part. */
    private fun comparable(phrases: List<String>) = phrases.map(TextNormalizer::normalize).filter { it.isNotEmpty() }
}

/** Keeps out what was already there when a session began, and anything too old to answer. */
object NotificationAge {
    const val MAX_AGE_MS = 60_000L

    /** [postedMs] is when Android received it. [messageMs] is the time the app put on it, or 0. */
    fun isFresh(postedMs: Long, messageMs: Long, sessionStartMs: Long, lastHandledMs: Long, nowMs: Long): Boolean {
        val fromBeforeStart = postedMs < sessionStartMs || messageMs in 1 until sessionStartMs
        return !fromBeforeStart && postedMs > lastHandledMs && nowMs - postedMs <= MAX_AGE_MS
    }
}

/**
 * Lets one reply through at a time, at least [minGapMs] apart. A trigger that does not get through
 * is dropped, never kept for later, which is what stops an old burst from being answered.
 * Called from the notification thread and the scanning thread.
 */
class ReplyGate(private val minGapMs: Long = 20_000) {
    private var lastMs: Long? = null
    private var busy = false

    /** The wall clock time of the last trigger let through, or 0. */
    @Volatile
    var lastHandledWallMs = 0L
        private set

    @Synchronized
    fun tryBegin(nowMs: Long, wallMs: Long, inHours: Boolean): Boolean {
        val last = lastMs
        if (!inHours || busy || (last != null && nowMs - last < minGapMs)) return false
        busy = true
        lastMs = nowMs
        lastHandledWallMs = wallMs
        return true
    }

    /** The gap counts from the send itself, which can be well after the trigger. */
    @Synchronized
    fun finish(nowMs: Long, sent: Boolean) {
        busy = false
        if (sent) lastMs = nowMs
    }

    /** The reply was abandoned before anything happened. The gap stays, so it is not retried. */
    @Synchronized
    fun cancel() {
        busy = false
    }
}
