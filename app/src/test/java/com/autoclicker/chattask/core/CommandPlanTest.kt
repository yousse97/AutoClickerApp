package com.autoclicker.chattask.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandPlanTest {
    private val commands = listOf(
        CommandEntry(" one "),
        CommandEntry("two"),
        CommandEntry("three", SendMode.DELAYED, 5),
    )

    @Test
    fun `simultaneous follows at once and delayed waits`() = runTest {
        val sentAt = ArrayList<Pair<String, Long>>()
        val result = CommandSequence.run(commands, { true }, { delay(it) }) {
            sentAt += it to currentTime
            true
        }
        assertTrue(result)
        assertEquals(listOf("one" to 0L, "two" to 0L, "three" to 5_000L), sentAt)
    }

    @Test
    fun `the first command ignores its own delay`() = runTest {
        var sentAt = -1L
        val first = listOf(CommandEntry("only", SendMode.DELAYED, 30))
        CommandSequence.run(first, { true }, { delay(it) }) {
            sentAt = currentTime
            true
        }
        assertEquals(0L, sentAt)
        assertEquals(0L, CommandSequence.totalWaitMs(first))
    }

    @Test
    fun `a failed send stops the rest and reports failure`() = runTest {
        val sent = ArrayList<String>()
        val result = CommandSequence.run(commands, { true }, { delay(it) }) {
            sent += it
            it != "two"
        }
        assertFalse(result)
        assertEquals(listOf("one", "two"), sent)
    }

    @Test
    fun `pausing during a delay abandons the rest without a failure`() = runTest {
        var active = true
        var result = false
        val sent = ArrayList<String>()
        launch {
            result = CommandSequence.run(commands, { active }, { delay(it) }) {
                sent += it
                true
            }
        }
        advanceTimeBy(1_000)
        active = false
        advanceTimeBy(300)
        runCurrent()

        assertEquals(listOf("one", "two"), sent)
        assertTrue(result)
    }

    @Test
    fun `work and rest plans alternate without drifting`() {
        val cycles = WorkRestScheduler(workMs = 1_000, restMs = 500, intervalMs = 100, firstDelayMs = 10)
        val work = cycles.workPlan(now = 50)
        assertEquals(1_050, work.endMs)
        assertEquals(60, work.nextFireMs)

        val rest = cycles.restPlan(work)
        assertEquals(1_550, rest.endMs)
        assertEquals(rest.endMs, rest.nextFireMs)
        assertTrue(cycles.loops)
        assertFalse(WorkRestScheduler(1_000, 0, 100, 10).loops)
    }

    @Test
    fun `passcode check accepts only the matching value`() {
        // PBKDF2-HMAC-SHA256 of "secret" with this salt and 1000 rounds.
        val verifier = PasscodeVerifier(
            saltHex = "00112233445566778899aabbccddeeff",
            iterations = 1_000,
            hashHex = "588372775fb38b5711b6f36b465e293dc7b793e008550ee8a10bb26a8e7f85b8",
        )
        assertTrue(verifier.matches("secret"))
        assertTrue(verifier.matches(" secret "))
        assertFalse(verifier.matches("Secret"))
        assertFalse(verifier.matches(""))
    }

    @Test
    fun `master passcode matches when provided by the environment`() {
        val passcode = System.getenv("AUTOCLICKER_PASSCODE") ?: return
        assertTrue(PasscodeVerifier.MASTER.matches(passcode))
        assertFalse(PasscodeVerifier.MASTER.matches(passcode + "1"))
    }
}
