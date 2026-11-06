package com.autoclicker.chattask.service

import com.autoclicker.chattask.core.JitterPolicy
import com.autoclicker.chattask.core.ScheduleOffsets
import com.autoclicker.chattask.core.SessionSchedule
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

data class RunnerView(
    val phase: TaskPhase,
    val nextInMs: Long,
    val sessionLeftMs: Long,
    val sent: Int,
    val failed: Int,
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
    private val action: suspend () -> Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onTick(view: RunnerView)

        /** The plan or counters changed, so this is the moment to persist. */
        fun onPlanChanged()

        fun onFinished(view: RunnerView)
    }

    private var job: Job? = null
    private var schedule: SessionSchedule? = null
    private var jitter = JitterPolicy(0, 0)
    private var pausedAtMs: Long? = null
    private var actionInFlight = false
    private var sent = 0
    private var failed = 0

    /** Written on the scope's thread; read from the node worker to abandon retries on pause. */
    @Volatile
    var phase = TaskPhase.IDLE
        private set

    fun start(
        plan: SessionSchedule,
        jitterPolicy: JitterPolicy,
        sentSoFar: Int = 0,
        failedSoFar: Int = 0,
        paused: Boolean = false,
    ) {
        job?.cancel()
        schedule = plan
        jitter = jitterPolicy
        sent = sentSoFar
        failed = failedSoFar
        if (paused) {
            pausedAtMs = clock()
            phase = TaskPhase.PAUSED
        } else {
            pausedAtMs = null
            phase = TaskPhase.RUNNING
            launchLoop()
        }
        listener.onPlanChanged()
        listener.onTick(view())
    }

    fun pause() {
        if (phase != TaskPhase.RUNNING) return
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
        phase = TaskPhase.RUNNING
        launchLoop()
        listener.onPlanChanged()
        listener.onTick(view())
    }

    fun stop() {
        job?.cancel()
        job = null
        schedule = null
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
        )
    }

    fun offsets(): ScheduleOffsets? = schedule?.offsetsFrom(pausedAtMs ?: clock())

    private fun launchLoop() {
        job = scope.launch {
            while (isActive) {
                val plan = schedule ?: return@launch
                val now = clock()
                if (plan.isFinished(now)) {
                    val finalView = view()
                    stop()
                    listener.onFinished(finalView)
                    return@launch
                }
                if (plan.isDue(now) && !actionInFlight) {
                    runCycle()
                    continue
                }
                listener.onTick(view())
                val untilDeadline = min(plan.nextFireMs, plan.endMs) - now
                delay(untilDeadline.coerceIn(MIN_TICK_MS, TICK_MS))
            }
        }
    }

    /** Runs to completion even if paused midway, so a message is never left half typed. */
    private suspend fun runCycle() {
        actionInFlight = true
        val success = try {
            withContext(NonCancellable) { action() }
        } finally {
            actionInFlight = false
        }
        val plan = schedule ?: return
        if (success) sent++ else failed++
        schedule = plan.advance(clock(), jitter.nextMs(random))
        listener.onPlanChanged()
        listener.onTick(view())
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MIN_TICK_MS = 50L
    }
}
