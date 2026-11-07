package com.autoclicker.chattask.core

/**
 * Builds the alternating work and rest plans of a session.
 *
 * Rest is a [SessionSchedule] with no send slots before its end, which gives it the same pause,
 * persistence and countdown behaviour as work. With [restMs] of zero there is one work period.
 */
class WorkRestScheduler(
    private val workMs: Long,
    private val restMs: Long,
    private val intervalMs: Long,
    private val firstDelayMs: Long,
) {
    val loops: Boolean get() = restMs > 0

    companion object {
        private const val ENDLESS_MS = 3650L * 24 * 3_600_000

        /** Sessions have no length of their own: each command's hours follow the real clock. */
        fun endless(intervalMs: Long, firstDelayMs: Long) =
            WorkRestScheduler(ENDLESS_MS, 0, intervalMs, firstDelayMs)
    }

    fun workPlan(now: Long): SessionSchedule =
        SessionSchedule.create(now, firstDelayMs, intervalMs, workMs)

    /** Counts from the end of [work], so a send that overruns it does not move the rest period. */
    fun restPlan(work: SessionSchedule): SessionSchedule {
        val end = work.endMs + restMs
        return work.copy(anchorMs = end, nextFireMs = end, endMs = end)
    }
}
