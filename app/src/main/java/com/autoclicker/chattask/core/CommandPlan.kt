package com.autoclicker.chattask.core

import java.time.LocalDateTime

/** One YOUSE 1 command with its own repeat interval and active hours. */
data class CommandEntry(
    val text: String,
    val intervalSec: Int = 0,
    /** True for "24 hours continuous". Otherwise [startMinute] and [endMinute] apply daily. */
    val allDay: Boolean = true,
    val startMinute: Int = DailySchedule.UNSET,
    val endMinute: Int = DailySchedule.UNSET,
) {
    val window: DailySchedule get() = DailySchedule(startMinute, endMinute)

    fun isActive(now: LocalDateTime): Boolean = allDay || window.isActive(now)
}

enum class CommandState { RUNNING, PENDING, IDLE }

/** Where one timer stands, relative to a moment in time, so it can outlive a process. */
data class TimerOffset(val anchorInMs: Long, val nextInMs: Long, val held: Boolean)

/**
 * The independent timers of a session, in milliseconds of a monotonic clock.
 *
 * A held command simply stays due, which is what limits it to one pending send. Not thread-safe.
 */
class CommandTimers(
    private val specs: List<Spec>,
    private val firstDelayMs: Long,
) {
    class Spec(val intervalMs: Long, val isActive: (LocalDateTime) -> Boolean)

    private class Slot(var plan: SessionSchedule? = null, var held: Boolean = false)

    private val slots = List(specs.size) { Slot() }

    val size: Int get() = slots.size

    /** True once every command is outside its active hours. */
    val allIdle: Boolean get() = slots.all { it.plan == null }

    /** Starts commands entering their hours and idles those leaving. Returns skipped commands. */
    fun refresh(now: Long, wall: LocalDateTime): List<Int> {
        val skipped = ArrayList<Int>()
        slots.forEachIndexed { index, slot ->
            val active = specs[index].isActive(wall)
            if (active && slot.plan == null) slot.plan = newPlan(now, specs[index].intervalMs)
            if (!active) {
                if (slot.plan != null && slot.held) skipped += index
                slot.plan = null
                slot.held = false
            }
        }
        return skipped
    }

    private fun newPlan(now: Long, intervalMs: Long) =
        SessionSchedule.create(now, firstDelayMs, intervalMs, FAR_FUTURE_MS)

    /** Commands to send now, earliest due first. */
    fun dueOrder(now: Long): List<Int> = slots.indices
        .filter { slots[it].plan?.isDue(now) == true }
        .sortedBy { slots[it].plan?.nextFireMs }

    fun markHeld(indices: List<Int>) = indices.forEach { slots[it].held = true }

    /** A late send restarts its interval, so two sends of one command never land close together. */
    fun markDone(index: Int, now: Long, jitterMs: Long) {
        val slot = slots[index]
        val plan = slot.plan ?: return
        slot.plan = if (slot.held) plan.restartedAt(now, jitterMs) else plan.advance(now, jitterMs)
        slot.held = false
    }

    /** Sends the next period back through [refresh], as after a rest. */
    fun restart() = slots.forEach {
        it.plan = null
        it.held = false
    }

    fun shiftBy(deltaMs: Long) = slots.forEach { it.plan = it.plan?.shiftedBy(deltaMs) }

    /** Marks anything already overdue as held, which is the state of a restored session. */
    fun markOverdue(now: Long) = slots.forEach { if (it.plan?.isDue(now) == true) it.held = true }

    fun stateOf(index: Int): CommandState = when {
        slots[index].plan == null -> CommandState.IDLE
        slots[index].held -> CommandState.PENDING
        else -> CommandState.RUNNING
    }

    fun nextInMs(index: Int, now: Long): Long? =
        slots[index].plan?.let { (it.nextFireMs - now).coerceAtLeast(0) }

    /** The command that fires soonest, or null when all are idle. */
    fun nextIndex(): Int? = slots.indices
        .filter { slots[it].plan != null }
        .minByOrNull { slots[it].plan?.nextFireMs ?: Long.MAX_VALUE }

    fun intervalMs(index: Int): Long = specs[index].intervalMs

    fun offsets(baseMs: Long): List<TimerOffset?> = slots.map { slot ->
        slot.plan?.let { TimerOffset(it.anchorMs - baseMs, it.nextFireMs - baseMs, slot.held) }
    }

    /** Offsets that do not match these commands are ignored, so the timers simply start fresh. */
    fun restore(offsets: List<TimerOffset?>, baseMs: Long) {
        if (offsets.size != slots.size) return
        offsets.forEachIndexed { index, offset ->
            if (offset != null) {
                slots[index].plan = SessionSchedule(
                    anchorMs = baseMs + offset.anchorInMs,
                    nextFireMs = baseMs + offset.nextInMs,
                    endMs = FAR_FUTURE_MS,
                    intervalMs = specs[index].intervalMs,
                )
                slots[index].held = offset.held
            }
        }
    }

    companion object {
        private const val FAR_FUTURE_MS = Long.MAX_VALUE / 4

        fun of(commands: List<CommandEntry>, firstDelayMs: Long) = CommandTimers(
            commands.map { command -> Spec(command.intervalSec * 1000L, command::isActive) },
            firstDelayMs,
        )

        /** One always-active timer following [plan]. */
        fun single(plan: SessionSchedule, firstDelayMs: Long) =
            CommandTimers(listOf(Spec(plan.intervalMs) { true }), firstDelayMs).apply {
                slots[0].plan = plan
            }
    }
}
