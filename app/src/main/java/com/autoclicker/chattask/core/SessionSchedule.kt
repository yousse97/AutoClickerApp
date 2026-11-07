package com.autoclicker.chattask.core

import kotlin.math.max
import kotlin.random.Random

/** Picks a random signed offset whose magnitude lies in [minSec]..[maxSec] seconds. */
class JitterPolicy(private val minSec: Int, private val maxSec: Int) {
    fun nextMs(random: Random): Long {
        val magnitude = random.nextLong(minSec * 1000L, maxSec * 1000L + 1)
        return if (random.nextBoolean()) magnitude else -magnitude
    }
}

/**
 * Immutable timing plan, in milliseconds of a monotonic clock.
 *
 * [anchorMs] advances by exact intervals and jitter is applied on top of it, so jitter never
 * accumulates: over a long run the send count stays what the interval says it should be.
 */
data class SessionSchedule(
    val anchorMs: Long,
    val nextFireMs: Long,
    val intervalMs: Long,
) {
    fun isDue(now: Long): Boolean = now >= nextFireMs

    /** Moves to the next slot. Slots missed during sleep are dropped, never fired as a burst. */
    fun advance(now: Long, jitterMs: Long): SessionSchedule {
        var anchor = anchorMs + intervalMs
        if (anchor <= now) {
            val missed = (now - anchor) / intervalMs + 1
            anchor += missed * intervalMs
        }
        return copy(anchorMs = anchor, nextFireMs = max(anchor + jitterMs, now + MIN_GAP_MS))
    }

    /** After a delayed send the interval counts from now, so two sends never land close together. */
    fun restartedAt(now: Long, jitterMs: Long): SessionSchedule {
        val anchor = now + intervalMs
        return copy(anchorMs = anchor, nextFireMs = max(anchor + jitterMs, now + MIN_GAP_MS))
    }

    /** Pausing moves the deadlines, so the paused time does not count. */
    fun shiftedBy(deltaMs: Long): SessionSchedule = copy(
        anchorMs = anchorMs + deltaMs,
        nextFireMs = nextFireMs + deltaMs,
    )

    companion object {
        const val MIN_GAP_MS = 1_000L

        fun create(now: Long, firstDelayMs: Long, intervalMs: Long) = SessionSchedule(
            anchorMs = now + firstDelayMs,
            nextFireMs = now + firstDelayMs,
            intervalMs = intervalMs,
        )
    }
}
