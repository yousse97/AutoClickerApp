package com.autoclicker.chattask.service

import com.autoclicker.chattask.core.CommandState
import com.autoclicker.chattask.core.CommandTimers
import com.autoclicker.chattask.core.JitterPolicy
import com.autoclicker.chattask.core.TimerOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.min
import kotlin.random.Random

/** HELD means the send waits for a valid screen: the slot stays pending and is not a failure. */
enum class CycleResult { SENT, FAILED, HELD }

/** One command's state for the home screen. */
data class CommandView(val state: CommandState, val nextInMs: Long?)

data class RunnerView(
    val phase: TaskPhase,
    val nextInMs: Long,
    val sent: Int,
    val failed: Int,
    val allIdle: Boolean = false,
    val nextIndex: Int? = null,
    val nextTotalMs: Long = 0,
    val commands: List<CommandView> = emptyList(),
)

/**
 * The send loop. All methods must be called on the thread of [scope].
 *
 * A session has no length of its own: it runs until stopped, and each command's hours in
 * [CommandTimers] follow the real clock. The loop polls a monotonic [clock] once a second rather
 * than delaying for a whole interval, because a coroutine delay stops counting during deep sleep.
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

        /** The timers or counters changed, so this is the moment to persist. */
        fun onPlanChanged()

        /** A pending send ran out of active time, so it will not be sent. */
        fun onCommandSkipped(index: Int) = Unit
    }

    private var job: Job? = null
    private var timers: CommandTimers? = null
    private var jitter = JitterPolicy(0, 0)
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
        commandTimers: CommandTimers,
        jitterPolicy: JitterPolicy,
        sentSoFar: Int = 0,
        failedSoFar: Int = 0,
        paused: Boolean = false,
    ) {
        job?.cancel()
        timers = commandTimers
        // A restored session can be overdue, and its late send must also restart the interval.
        commandTimers.markOverdue(clock())
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
        timers?.shiftBy(clock() - pausedAt)
        pausedAtMs = null
        phase = TaskPhase.RUNNING
        launchLoop()
        listener.onPlanChanged()
        listener.onTick(view())
    }

    fun stop() {
        job?.cancel()
        job = null
        timers = null
        pausedAtMs = null
        phase = TaskPhase.IDLE
    }

    fun view(): RunnerView {
        val now = pausedAtMs ?: clock()
        val active = timers
        val next = active?.nextIndex()
        return RunnerView(
            phase = phase,
            nextInMs = next?.let { active.nextInMs(it, now) } ?: 0,
            sent = sent,
            failed = failed,
            allIdle = active?.allIdle == true,
            nextIndex = next,
            nextTotalMs = next?.let { active.intervalMs(it) } ?: 0,
            commands = active?.let { list ->
                List(list.size) { CommandView(list.stateOf(it), list.nextInMs(it, now)) }
            }.orEmpty(),
        )
    }

    fun timerOffsets(): List<TimerOffset?> = timers?.offsets(pausedAtMs ?: clock()).orEmpty()

    private fun launchLoop() {
        job = scope.launch {
            while (isActive) {
                val active = timers ?: return@launch
                val now = clock()
                active.refresh(now, wallClock()).forEach(listener::onCommandSkipped)
                val due = active.dueOrder(now)
                val gapPassed = lastSendMs?.let { now - it >= SEND_GAP_MS } != false
                if (due.isNotEmpty() && !actionInFlight && gapPassed) {
                    runCycle(due)
                    continue
                }
                listener.onTick(view())
                delay(untilNextDeadline(active, now))
            }
        }
    }

    private fun untilNextDeadline(active: CommandTimers, now: Long): Long {
        val nextFire = active.nextIndex()?.let { active.nextInMs(it, now) } ?: TICK_MS
        return min(nextFire, TICK_MS).coerceAtLeast(MIN_TICK_MS)
    }

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
    }
}
