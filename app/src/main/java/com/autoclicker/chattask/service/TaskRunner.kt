package com.autoclicker.chattask.service

import com.autoclicker.chattask.core.JitterPolicy
import com.autoclicker.chattask.core.ScheduleOffsets
import com.autoclicker.chattask.core.SessionSchedule
import com.autoclicker.chattask.core.WorkRestScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** HELD means the send waits for a valid screen: the slot stays pending and is not a failure. */
enum class CycleResult { SENT, FAILED, HELD }

data class RunnerView(
    val phase: TaskPhase,
    val nextInMs: Long,
    val sessionLeftMs: Long,
    val sent: Int,
    val failed: Int,
    val resting: Boolean = false,
    val workCycle: Int = 1,
)

/**
 * The periodic send loop. All methods must be called on the thread of [scope].
 *
 * The loop polls a monotonic [clock] once a second rather than delaying for a whole interval.
 * A coroutine delay stops counting during deep sleep, whereas these deadlines do not.
 *
 * Popup handling is deliberately absent: it has no way to touch this timer.
 */
class TaskRunner(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val random: Random,
    private val action: suspend () -> CycleResult,
    private val listener: Listener,
) {
    interface Listener {
        fun onTick(view: RunnerView)

        /** The plan or counters changed, so this is the moment to persist. */
        fun onPlanChanged()

        /** Work is starting again, so the target app should be brought back. */
        fun onRestFinished() = Unit

        fun onFinished(view: RunnerView)
    }

    private var job: Job? = null
    private var schedule: SessionSchedule? = null
    private var jitter = JitterPolicy(0, 0)
    private var cycles: WorkRestScheduler? = null
    private var resting = false
    private var workCycle = 1
    private var pausedAtMs: Long? = null
    private var actionInFlight = false
    private var slotWasHeld = false
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
    ) {
        job?.cancel()
        schedule = plan
        // A restored session can be overdue, and its late send must also restart the interval.
        slotWasHeld = plan.isDue(clock())
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
        schedule = schedule?.shiftedBy(clock() - pausedAt)
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
        cycles = null
        resting = false
        pausedAtMs = null
        phase = TaskPhase.IDLE
    }

    fun view(): RunnerView {
        val now = pausedAtMs ?: clock()
        val plan = schedule
        return RunnerView(
            phase = phase,
            nextInMs = plan?.let { max(0, it.nextFireMs - now) } ?: 0,
            sessionLeftMs = plan?.let { max(0, it.endMs - now) } ?: 0,
            sent = sent,
            failed = failed,
            resting = resting,
            workCycle = workCycle,
        )
    }

    fun offsets(): ScheduleOffsets? = schedule?.offsetsFrom(pausedAtMs ?: clock())

    private fun launchLoop() {
        job = scope.launch {
            while (isActive) {
                val plan = schedule ?: return@launch
                val now = clock()
                if (plan.isFinished(now) && !actionInFlight) {
                    if (!beginNextPeriod(plan, now)) return@launch
                    continue
                }
                if (!resting && plan.isDue(now) && !actionInFlight) {
                    runCycle()
                    continue
                }
                listener.onTick(view())
                val untilDeadline = min(plan.nextFireMs, plan.endMs) - now
                delay(untilDeadline.coerceIn(MIN_TICK_MS, TICK_MS))
            }
        }
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

    /** Runs to completion even if paused midway, so a message is never left half typed. */
    private suspend fun runCycle() {
        actionInFlight = true
        val result = try {
            withContext(NonCancellable) { action() }
        } finally {
            actionInFlight = false
        }
        val plan = schedule ?: return
        if (result == CycleResult.HELD) {
            // The slot stays due, which is what sends it once, as soon as the screen is valid.
            slotWasHeld = true
            listener.onTick(view())
            delay(TICK_MS)
            return
        }
        if (result == CycleResult.SENT) sent++ else failed++
        val jitterMs = jitter.nextMs(random)
        schedule = if (slotWasHeld) plan.restartedAt(clock(), jitterMs) else plan.advance(clock(), jitterMs)
        slotWasHeld = false
        listener.onPlanChanged()
        listener.onTick(view())
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MIN_TICK_MS = 50L
    }
}
