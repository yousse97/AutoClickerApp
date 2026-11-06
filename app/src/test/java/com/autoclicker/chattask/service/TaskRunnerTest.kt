package com.autoclicker.chattask.service

import com.autoclicker.chattask.core.JitterPolicy
import com.autoclicker.chattask.core.SessionSchedule
import com.autoclicker.chattask.core.WorkRestScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRunnerTest {
    private class Recorder : TaskRunner.Listener {
        var finished: RunnerView? = null
        var plansSaved = 0
        var restsFinished = 0

        override fun onRestFinished() {
            restsFinished++
        }

        override fun onTick(view: RunnerView) = Unit

        override fun onPlanChanged() {
            plansSaved++
        }

        override fun onFinished(view: RunnerView) {
            finished = view
        }
    }

    private val interval = 60_000L
    private val recorder = Recorder()

    private fun TestScope.runner(scope: CoroutineScope, action: suspend () -> Boolean) = TaskRunner(
        scope = scope,
        clock = { currentTime },
        random = Random(3),
        action = action,
        listener = recorder,
    )

    private fun TestScope.plan(durationMs: Long) =
        SessionSchedule.create(currentTime, 3_000, interval, durationMs)

    private val noJitter = JitterPolicy(0, 0)

    private val workRest = WorkRestScheduler(
        workMs = 200_000,
        restMs = 100_000,
        intervalMs = interval,
        firstDelayMs = 3_000,
    )

    private fun TestScope.startCycles(runner: TaskRunner) =
        runner.start(workRest.workPlan(currentTime), noJitter, workRest)

    @Test
    fun `work, rest and work repeat with no sends during rest`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) { sends++; true }
        startCycles(runner)

        advanceTimeBy(201_000)
        runCurrent()
        assertEquals(TaskPhase.RESTING, runner.phase)
        assertEquals(4, sends)

        advanceTimeBy(90_000)
        runCurrent()
        assertEquals(TaskPhase.RESTING, runner.phase)
        assertEquals(4, sends)
        assertEquals(0, recorder.restsFinished)

        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(TaskPhase.RUNNING, runner.phase)
        assertEquals(1, recorder.restsFinished)
        assertEquals(2, runner.view().workCycle)
        assertEquals(5, sends)
        assertEquals(null, recorder.finished)

        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(runner.view().workCycle >= 3)
        assertEquals(null, recorder.finished)
    }

    @Test
    fun `pausing during rest extends it and resume returns to rest`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) { sends++; true }
        startCycles(runner)
        advanceTimeBy(210_000)
        runCurrent()
        assertEquals(TaskPhase.RESTING, runner.phase)

        runner.pause()
        assertEquals(TaskPhase.PAUSED, runner.phase)
        advanceTimeBy(500_000)
        runCurrent()
        assertEquals(0, recorder.restsFinished)

        runner.resume()
        assertEquals(TaskPhase.RESTING, runner.phase)
        advanceTimeBy(91_000)
        runCurrent()
        assertEquals(TaskPhase.RUNNING, runner.phase)
        assertEquals(1, recorder.restsFinished)
    }

    @Test
    fun `a session restored during rest finishes that rest`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) { sends++; true }
        val rest = workRest.restPlan(workRest.workPlan(currentTime - 200_000))
        runner.start(rest, noJitter, workRest, sentSoFar = 7, isResting = true, workCycleSoFar = 3)

        assertEquals(TaskPhase.RESTING, runner.phase)
        advanceTimeBy(101_000)
        runCurrent()

        assertEquals(TaskPhase.RUNNING, runner.phase)
        assertEquals(4, runner.view().workCycle)
        assertEquals(7, runner.view().sent)
    }

    @Test
    fun `a rest that ended while the process was gone starts work at once`() = runTest {
        val runner = runner(backgroundScope) { true }
        val rest = workRest.restPlan(workRest.workPlan(currentTime - 900_000))
        runner.start(rest, noJitter, workRest, isResting = true)
        runCurrent()

        assertEquals(TaskPhase.RUNNING, runner.phase)
        assertEquals(1, recorder.restsFinished)
    }

    @Test
    fun `stop during rest ends the session`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) { sends++; true }
        startCycles(runner)
        advanceTimeBy(210_000)
        runCurrent()
        val before = sends

        runner.stop()
        advanceTimeBy(1_000_000)
        runCurrent()

        assertEquals(TaskPhase.IDLE, runner.phase)
        assertEquals(before, sends)
        assertEquals(0, recorder.restsFinished)
    }

    @Test
    fun `sends on schedule and finishes at the session limit`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) { sends++; true }
        runner.start(plan(300_000), noJitter)

        advanceTimeBy(300_500)
        runCurrent()

        assertEquals(5, sends)
        assertEquals(5, recorder.finished?.sent)
        assertEquals(TaskPhase.IDLE, runner.phase)
    }

    @Test
    fun `failed cycles are counted and do not stop the session`() = runTest {
        var calls = 0
        val runner = runner(backgroundScope) { calls++ % 2 == 0 }
        runner.start(plan(200_000), noJitter)

        advanceTimeBy(130_000)
        runCurrent()

        assertEquals(3, calls)
        assertEquals(2, runner.view().sent)
        assertEquals(1, runner.view().failed)
        assertEquals(TaskPhase.RUNNING, runner.phase)
    }

    @Test
    fun `pausing freezes the countdown and session clock`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) { sends++; true }
        runner.start(plan(600_000), noJitter)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, sends)

        runner.pause()
        val frozen = runner.view()
        advanceTimeBy(500_000)
        runCurrent()

        assertEquals(1, sends)
        assertEquals(frozen, runner.view())

        runner.resume()
        advanceTimeBy(frozen.nextInMs + 500)
        runCurrent()

        assertEquals(2, sends)
        assertTrue(runner.view().sessionLeftMs > 500_000)
    }

    @Test
    fun `resuming during a slow send does not fire a duplicate`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) {
            sends++
            delay(5_000)
            true
        }
        runner.start(plan(600_000), noJitter)
        advanceTimeBy(3_100)
        runCurrent()
        runner.pause()
        runner.resume()
        advanceTimeBy(4_000)
        runCurrent()

        assertEquals(1, sends)
    }

    @Test
    fun `stop prevents any further sends`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) { sends++; true }
        runner.start(plan(600_000), noJitter)
        advanceTimeBy(10_000)
        runCurrent()

        runner.stop()
        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(1, sends)
        assertEquals(null, recorder.finished)
    }

    @Test
    fun `a restored paused session waits for resume`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) { sends++; true }
        runner.start(plan(600_000), noJitter, sentSoFar = 4, failedSoFar = 1, paused = true)
        advanceTimeBy(120_000)
        runCurrent()

        assertEquals(0, sends)
        assertEquals(TaskPhase.PAUSED, runner.phase)
        assertEquals(4, runner.view().sent)
        assertEquals(1, runner.view().failed)
    }

    @Test
    fun `every plan change is reported for persistence`() = runTest {
        val runner = runner(backgroundScope) { true }
        runner.start(plan(600_000), noJitter)
        advanceTimeBy(10_000)
        runCurrent()
        runner.pause()
        runner.resume()

        assertEquals(4, recorder.plansSaved)
    }
}
