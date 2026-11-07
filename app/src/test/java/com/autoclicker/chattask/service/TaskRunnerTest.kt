package com.autoclicker.chattask.service

import com.autoclicker.chattask.core.CommandEntry
import com.autoclicker.chattask.core.CommandState
import com.autoclicker.chattask.core.CommandTimers
import com.autoclicker.chattask.core.JitterPolicy
import com.autoclicker.chattask.core.SessionSchedule
import com.autoclicker.chattask.core.TimerOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class TaskRunnerTest {
    private class Recorder : TaskRunner.Listener {
        var plansSaved = 0
        var skipped = 0

        override fun onCommandSkipped(index: Int) {
            skipped++
        }

        override fun onTick(view: RunnerView) = Unit

        override fun onPlanChanged() {
            plansSaved++
        }

    }

    private val interval = 60_000L
    private val recorder = Recorder()

    private fun TestScope.runner(scope: CoroutineScope, action: suspend () -> Boolean) =
        cycleRunner(scope) { if (action()) CycleResult.SENT else CycleResult.FAILED }

    private fun TestScope.cycleRunner(scope: CoroutineScope, action: suspend () -> CycleResult) =
        indexRunner(scope) { action() }

    private var wall = LocalDateTime.of(2026, 3, 10, 12, 0)

    private fun TestScope.indexRunner(scope: CoroutineScope, action: suspend (Int) -> CycleResult) = TaskRunner(
        scope = scope,
        clock = { currentTime },
        random = Random(3),
        action = action,
        listener = recorder,
        wallClock = { wall },
    )

    private fun TestScope.startCommands(runner: TaskRunner, vararg commands: CommandEntry) {
        val timers = CommandTimers.of(commands.toList(), 3_000)
        timers.refresh(currentTime, wall)
        runner.start(timers, noJitter)
    }

    @Test
    fun `commands follow their own intervals in one session`() = runTest {
        val sent = ArrayList<Int>()
        val runner = indexRunner(backgroundScope) { sent += it; CycleResult.SENT }
        startCommands(runner, CommandEntry("a", 60), CommandEntry("b", 150))

        advanceTimeBy(160_000)
        runCurrent()

        assertEquals(3, sent.count { it == 0 })
        assertEquals(2, sent.count { it == 1 })
    }

    @Test
    fun `due commands go out one at a time with a gap`() = runTest {
        val times = ArrayList<Long>()
        val runner = indexRunner(backgroundScope) { times += currentTime; CycleResult.SENT }
        startCommands(runner, CommandEntry("a", 60), CommandEntry("b", 60))

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(2, times.size)
        assertTrue(times[1] - times[0] >= 2_000)
    }

    @Test
    fun `any two sends keep the gap, even when due a moment apart`() = runTest {
        val times = ArrayList<Long>()
        val runner = indexRunner(backgroundScope) { times += currentTime; CycleResult.SENT }
        startCommands(runner, CommandEntry("a", 60), CommandEntry("b", 61))

        advanceTimeBy(200_000)
        runCurrent()

        assertTrue(times.size > 4)
        assertTrue(times.zipWithNext { first, second -> second - first }.all { it >= 2_000 })
    }

    @Test
    fun `a command that stays held is skipped when its hours end, others carry on`() = runTest {
        val sent = ArrayList<Int>()
        val runner = indexRunner(backgroundScope) {
            if (it == 0) CycleResult.HELD else {
                sent += it
                CycleResult.SENT
            }
        }
        startCommands(
            runner,
            CommandEntry("a", 60, allDay = false, startMinute = 11 * 60, endMinute = 13 * 60),
        )
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(CommandState.PENDING, runner.view().commands[0].state)

        wall = wall.withHour(13)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, recorder.skipped)
        assertEquals(CommandState.IDLE, runner.view().commands[0].state)
        assertTrue(runner.view().allIdle)
        assertEquals(TaskPhase.RUNNING, runner.phase)
    }

    @Test
    fun `a session stays up while all commands are idle and resumes at the next hours`() = runTest {
        var sends = 0
        val runner = indexRunner(backgroundScope) { sends++; CycleResult.SENT }
        startCommands(runner, CommandEntry("a", 60, allDay = false, startMinute = 13 * 60, endMinute = 14 * 60))

        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(runner.view().allIdle)
        assertEquals(0, sends)

        wall = wall.withHour(13)
        advanceTimeBy(6_000)
        runCurrent()
        assertEquals(1, sends)
    }

    private fun TestScope.plan() = SessionSchedule.create(currentTime, 3_000, interval)

    private fun TaskRunner.start(
        plan: SessionSchedule,
        jitter: JitterPolicy,
        sentSoFar: Int = 0,
        failedSoFar: Int = 0,
        paused: Boolean = false,
    ) = start(CommandTimers.single(plan, 3_000), jitter, sentSoFar, failedSoFar, paused)

    private val noJitter = JitterPolicy(0, 0)

    @Test
    fun `a held send is pending, keeps the clock running and is sent once on return`() = runTest {
        var held = true
        var sends = 0
        val runner = cycleRunner(backgroundScope) {
            if (held) {
                CycleResult.HELD
            } else {
                sends++
                CycleResult.SENT
            }
        }
        runner.start(plan(), noJitter)

        advanceTimeBy(300_000)
        runCurrent()
        assertEquals(0, sends)
        assertEquals(0, runner.view().failed)
        assertEquals(0, runner.view().sent)
        assertEquals(TaskPhase.RUNNING, runner.phase)
        assertEquals(0, runner.view().nextInMs)

        held = false
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, sends)
        assertTrue(runner.view().nextInMs > 0)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, sends)
    }

    @Test
    fun `a restored overdue send is not followed by another one seconds later`() = runTest {
        val sentAt = ArrayList<Long>()
        val runner = runner(backgroundScope) { sentAt += currentTime; true }
        val overdue = SessionSchedule(
            anchorMs = currentTime - 41_000,
            nextFireMs = currentTime - 41_000,
            intervalMs = interval,
        )
        runner.start(overdue, noJitter)

        advanceTimeBy(50_000)
        runCurrent()
        assertEquals(1, sentAt.size)

        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(2, sentAt.size)
        assertEquals(interval, sentAt[1] - sentAt[0])
    }

    private fun TestScope.restoreDaily(runner: TaskRunner, offsets: List<TimerOffset?>) {
        val timers = CommandTimers.of(listOf(daily), 3_000)
        timers.restore(offsets, currentTime)
        runner.start(timers, noJitter)
    }

    private val daily = CommandEntry("a", 60, allDay = false, startMinute = 0, endMinute = 13 * 60)

    @Test
    fun `a session that recovers inside the hours resumes at once`() = runTest {
        var sends = 0
        val runner = indexRunner(backgroundScope) { sends++; CycleResult.SENT }
        restoreDaily(runner, listOf(TimerOffset(-500_000, -500_000, false)))

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, sends)
    }

    @Test
    fun `a session that recovers outside the hours stays idle until the real start`() = runTest {
        var sends = 0
        val runner = indexRunner(backgroundScope) { sends++; CycleResult.SENT }
        wall = wall.withHour(15)
        restoreDaily(runner, listOf(TimerOffset(-500_000, -500_000, false)))

        advanceTimeBy(600_000)
        runCurrent()
        assertTrue(runner.view().allIdle)
        assertEquals(0, sends)

        wall = wall.plusDays(1).withHour(0)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, sends)
    }

    @Test
    fun `the end of the hours comes from the clock, not from when a session started`() = runTest {
        val runner = indexRunner(backgroundScope) { CycleResult.SENT }
        wall = wall.withHour(12).withMinute(59)
        val timers = CommandTimers.of(listOf(daily), 3_000)
        timers.refresh(currentTime, wall)
        runner.start(timers, noJitter)
        advanceTimeBy(30_000)
        runCurrent()
        assertFalse(runner.view().allIdle)

        wall = wall.withHour(13).withMinute(0)
        advanceTimeBy(2_000)
        runCurrent()

        assertTrue(runner.view().allIdle)
    }

    @Test
    fun `a session keeps running with no end of its own`() = runTest {
        val runner = indexRunner(backgroundScope) { CycleResult.SENT }
        val timers = CommandTimers.of(listOf(CommandEntry("a", 3_600)), 3_000)
        timers.refresh(currentTime, wall)
        runner.start(timers, noJitter)

        advanceTimeBy(30 * 3_600_000L)
        runCurrent()

        assertEquals(TaskPhase.RUNNING, runner.phase)
    }

    @Test
    fun `sends on schedule`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) { sends++; true }
        runner.start(plan(), noJitter)

        advanceTimeBy(300_500)
        runCurrent()

        assertEquals(5, sends)
        assertEquals(5, runner.view().sent)
        assertEquals(TaskPhase.RUNNING, runner.phase)
    }

    @Test
    fun `failed cycles are counted and do not stop the session`() = runTest {
        var calls = 0
        val runner = runner(backgroundScope) { calls++ % 2 == 0 }
        runner.start(plan(), noJitter)

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
        runner.start(plan(), noJitter)
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
    }

    @Test
    fun `resuming during a slow send does not fire a duplicate`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) {
            sends++
            delay(5_000)
            true
        }
        runner.start(plan(), noJitter)
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
        runner.start(plan(), noJitter)
        advanceTimeBy(10_000)
        runCurrent()

        runner.stop()
        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(1, sends)
    }

    @Test
    fun `a restored paused session waits for resume`() = runTest {
        var sends = 0
        val runner = runner(backgroundScope) { sends++; true }
        runner.start(plan(), noJitter, sentSoFar = 4, failedSoFar = 1, paused = true)
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
        runner.start(plan(), noJitter)
        advanceTimeBy(10_000)
        runCurrent()
        runner.pause()
        runner.resume()

        assertEquals(4, recorder.plansSaved)
    }
}
