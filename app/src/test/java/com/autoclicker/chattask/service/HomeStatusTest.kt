package com.autoclicker.chattask.service

import com.autoclicker.chattask.core.BarState
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeStatusTest {
    private val running = TaskStatus(phase = TaskPhase.RUNNING)

    @Test
    fun `each session state maps to its card`() {
        assertEquals(HomeState.RUNNING, homeStateOf(running, false))
        assertEquals(HomeState.ATTENTION, homeStateOf(running.copy(barState = BarState.SEND_SOON), false))
        assertEquals(HomeState.ATTENTION, homeStateOf(running.copy(barState = BarState.COMMAND_READY), false))
        assertEquals(HomeState.PAUSED, homeStateOf(TaskStatus(phase = TaskPhase.PAUSED), false))
        assertEquals(HomeState.PAUSED, homeStateOf(running.copy(heldByLock = true), false))
        assertEquals(HomeState.REST, homeStateOf(TaskStatus(phase = TaskPhase.RESTING), false))
    }

    @Test
    fun `idle wins over stopped, and over an earlier error`() {
        assertEquals(HomeState.IDLE, homeStateOf(TaskStatus(idleUntilMs = 1), true))
        assertEquals(HomeState.ERROR, homeStateOf(TaskStatus(), true))
        assertEquals(HomeState.STOPPED, homeStateOf(TaskStatus(), false))
    }

    @Test
    fun `an old error does not hide a running session`() {
        assertEquals(HomeState.RUNNING, homeStateOf(running, true))
    }
}
