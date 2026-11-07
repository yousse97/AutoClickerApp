package com.autoclicker.chattask.service

import com.autoclicker.chattask.core.CommandState
import com.autoclicker.chattask.core.CommandTimers
import com.autoclicker.chattask.core.JitterPolicy
import com.autoclicker.chattask.core.ScheduleOffsets
import com.autoclicker.chattask.core.SessionSchedule
import com.autoclicker.chattask.core.TimerOffset
import com.autoclicker.chattask.core.WorkRestScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** HELD means the send waits for a valid screen: the slot stays pending and is not a failure. */
enum class CycleResult { SENT, FAILED, HELD }

/** One command's state for the home screen. */
data class CommandView(val state: CommandState, val nextInMs: Long?)

data class RunnerView(
    val phase: TaskPhase,
    val nextInMs: Long,
    val sessionLeftMs: Long,
    val sent: Int,
    val failed: Int,
    val resting: Boolean = false,
    val workCycle: Int = 1,
    val allIdle: Boolean = false,
    val nextIndex: Int? = null,
    val nextTotalMs: Long = 0,
    val commands: List<CommandView> = emptyList(),
)

/**
 * The send loop. All methods must be called on the thread of [scope].
 *
 * The session period and work and rest live in one plan, and each command has its own timer in
 * [CommandTimers]. The loop polls a monotonic [clock] once a second rather than delaying for a
 * whole interval, because a coroutine delay stops counting during deep sleep.
 *
 * Popup handling is deliberately absent: it has no way to touch these timers.
 */
class TaskRunner(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val random: Random,
    private val action: suspend (Int) -> CycleResult,
    private val listener: Listener,
    private val wallClock: () -> LocalDateTime = LocalDateTime::now,
) {
    interface Listener {
        fun onTick(view: RunnerView)

        /** The plan or counters changed, so this is the moment to persist. */
        fun onPlanChanged()

        /** Work is starting again, so the target app should be brought back. */
        fun onRestFinished() = Unit

        /** A pending send ran out of active time, so it will not be sent. */
        fun onCommandSkipped(index: Int) = Unit

        fun onFinished(view: RunnerView)
    }

    private var job: Job? = null
    private var schedule: SessionSchedule? = null
    private var timers: CommandTimers? = null
    private var jitter = JitterPolicy(0, 0)
    private var cycles: WorkRestScheduler? = null
    private var resting = false
    private var workCycle = 1
    private var pausedAtMs: Long? = null
    private var actionInFlight = false
    private var lastSendMs: Long? = null
    private var sent = 0
    private var failed = 0

    /** Written on the scope's thread; read from the node worker to abandon retries on pause. */
    @Volatile
    var phase = TaskPhase.IDLE
        private set

    fun start(
        plan: SessionSchedule,
        jitterPolicy: JitterPolicy,
        workRest: WorkRestScheduler? = null,
        sentSoFar: Int = 0,
        failedSoFar: Int = 0,
        paused: Boolean = false,
        isResting: Boolean = false,
        workCycleSoFar: Int = 1,
        commandTimers: CommandTimers = CommandTimers.single(plan, DEFAULT_FIRST_DELAY_MS),
    ) {
        job?.cancel()
        schedule = plan
        timers = commandTimers
        // A restored session can be overdue, and its late send must also restart the interval.
        commandTimers.markOverdue(clock())
        jitter = jitterPolicy
        cycles = workRest
        resting = isResting
        workCycle = workCycleSoFar
        sent = sentSoFar
        failed = failedSoFar
        if (paused) {
            pausedAtMs = clock()
            phase = TaskPhase.PAUSED
        } else {
            pausedAtMs = null
            phase = activePhase()
            launchLoop()
        }
        listener.onPlanChanged()
        listener.onTick(view())
    }

    fun pause() {
        if (phase != TaskPhase.RUNNING && phase != TaskPhase.RESTING) return
        job?.cancel()
        pausedAtMs = clock()
        phase = TaskPhase.PAUSED
        listener.onPlanChanged()
        listener.onTick(view())
    }

    fun resume() {
        if (phase != TaskPhase.PAUSED) return
        val pausedAt = pausedAtMs ?: return
        val pausedFor = clock() - pausedAt
        schedule = schedule?.shiftedBy(pausedFor)
        timers?.shiftBy(pausedFor)
        pausedAtMs = null
        phase = activePhase()
        launchLoop()
        listener.onPlanChanged()
        listener.onTick(view())
    }

    fun stop() {
        job?.cancel()
        job = null
        schedule = null
        timers = null
        cycles = null
        resting = false
        pausedAtMs = null
        phase = TaskPhase.IDLE
    }

    fun view(): RunnerView {
        val now = pausedAtMs ?: clock()
        val plan = schedule
        val active = timers
        val next = active?.nextIndex()?.takeUnless { resting }
        val commandLeft = next?.let { active.nextInMs(it, now) }
        return RunnerView(
            phase = phase,
            nextInMs = commandLeft ?: plan?.takeIf { resting }?.let { max(0, it.nextFireMs - now) } ?: 0,
            sessionLeftMs = plan?.let { max(0, it.endMs - now) } ?: 0,
            sent = sent,
            failed = failed,
            resting = resting,
            workCycle = workCycle,
            allIdle = !resting && active?.allIdle == true,
            nextIndex = next,
            nextTotalMs = next?.let { active.intervalMs(it) } ?: 0,
            commands = active?.let { list ->
                List(list.size) { CommandView(list.stateOf(it), list.nextInMs(it, now)) }
            }.orEmpty(),
        )
    }

    fun offsets(): ScheduleOffsets? = schedule?.offsetsFrom(pausedAtMs ?: clock())

    fun timerOffsets(): List<TimerOffset?> = timers?.offsets(pausedAtMs ?: clock()).orEmpty()

    private fun launchLoop() {
        job = scope.launch {
            while (isActive) {
                val plan = schedule ?: return@launch
                val now = clock()
                if (plan.isFinished(now) && !actionInFlight) {
                    if (!beginNextPeriod(plan, now)) return@launch
                    continue
                }
                val due = if (resting) emptyList() else dueCommands(now)
                val gapPassed = lastSendMs?.let { now - it >= SEND_GAP_MS } != false
                if (due.isNotEmpty() && !actionInFlight && gapPassed) {
                    runCycle(due)
                    continue
                }
                listener.onTick(view())
                delay(untilNextDeadline(plan, now))
            }
        }
    }

    private fun dueCommands(now: Long): List<Int> {
        val active = timers ?: return emptyList()
        val skipped = active.refresh(now, wallClock())
        skipped.forEach(listener::onCommandSkipped)
        return active.dueOrder(now)
    }

    private fun untilNextDeadline(plan: SessionSchedule, now: Long): Long {
        val active = timers
        val nextFire = active?.nextIndex()?.takeUnless { resting }?.let { active.nextInMs(it, now) }
        val deadline = min(nextFire ?: TICK_MS, plan.endMs - now)
        return deadline.coerceIn(MIN_TICK_MS, TICK_MS)
    }

    /** Moves between work and rest. Returns false when the session is over. */
    private fun beginNextPeriod(plan: SessionSchedule, now: Long): Boolean {
        val workRest = cycles?.takeIf { it.loops }
        when {
            workRest == null -> {
                val finalView = view()
                stop()
                listener.onFinished(finalView)
                return false
            }
            resting -> {
                resting = false
                workCycle++
                schedule = workRest.workPlan(now)
                timers?.restart()
                listener.onRestFinished()
            }
            else -> {
                resting = true
                schedule = workRest.restPlan(plan)
            }
        }
        phase = activePhase()
        listener.onPlanChanged()
        listener.onTick(view())
        return true
    }

    private fun activePhase() = if (resting) TaskPhase.RESTING else TaskPhase.RUNNING

    /**
     * Sends the earliest due command. It runs to completion even if paused midway, so a message is
     * never left half typed. The loop keeps a gap after every send, due at the same time or not.
     */
    private suspend fun runCycle(due: List<Int>) {
        val index = due.first()
        actionInFlight = true
        val result = try {
            withContext(NonCancellable) { action(index) }
        } finally {
            actionInFlight = false
        }
        val active = timers ?: return
        if (result == CycleResult.HELD) {
            // Each stays due, which is what sends it once, as soon as the screen is valid.
            active.markHeld(due)
            listener.onTick(view())
            delay(HOLD_RECHECK_MS)
            return
        }
        if (result == CycleResult.SENT) sent++ else failed++
        active.markDone(index, clock(), jitter.nextMs(random))
        lastSendMs = clock()
        listener.onPlanChanged()
        listener.onTick(view())
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MIN_TICK_MS = 50L
        const val SEND_GAP_MS = 2_000L

        /** Each check reads WOLF's screen, so a held send is not rechecked every second. */
        const val HOLD_RECHECK_MS = 2_000L
        const val DEFAULT_FIRST_DELAY_MS = 3_000L
    }
}
