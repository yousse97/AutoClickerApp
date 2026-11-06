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

/** A schedule expressed relative to a moment in time, so it can outlive a process or reboot. */
data class ScheduleOffsets(
    val anchorInMs: Long,
    val nextInMs: Long,
    val endInMs: Long,
    val intervalMs: Long,
)

/**
 * Immutable timing plan, in milliseconds of a monotonic clock.
 *
 * [anchorMs] advances by exact intervals and jitter is applied on top of it, so jitter never
 * accumulates: over 22 hours the send count stays what the interval says it should be.
 */
data class SessionSchedule(
    val anchorMs: Long,
    val nextFireMs: Long,
    val endMs: Long,
    val intervalMs: Long,
) {
    fun isFinished(now: Long): Boolean = now >= endMs

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

    /** Pausing moves every deadline, so the paused time does not consume the session. */
    fun shiftedBy(deltaMs: Long): SessionSchedule = copy(
        anchorMs = anchorMs + deltaMs,
        nextFireMs = nextFireMs + deltaMs,
        endMs = endMs + deltaMs,
    )

    fun offsetsFrom(baseMs: Long) = ScheduleOffsets(
        anchorInMs = anchorMs - baseMs,
        nextInMs = nextFireMs - baseMs,
        endInMs = endMs - baseMs,
        intervalMs = intervalMs,
    )

    companion object {
        const val MIN_GAP_MS = 1_000L

        fun create(now: Long, firstDelayMs: Long, intervalMs: Long, durationMs: Long) =
            SessionSchedule(
                anchorMs = now + firstDelayMs,
                nextFireMs = now + firstDelayMs,
                endMs = now + durationMs,
                intervalMs = intervalMs,
            )

        fun fromOffsets(offsets: ScheduleOffsets, baseMs: Long) = SessionSchedule(
            anchorMs = baseMs + offsets.anchorInMs,
            nextFireMs = baseMs + offsets.nextInMs,
            endMs = baseMs + offsets.endInMs,
            intervalMs = offsets.intervalMs,
        )
    }
}
