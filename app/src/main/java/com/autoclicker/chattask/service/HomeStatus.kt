package com.autoclicker.chattask.service

import com.autoclicker.chattask.core.BarState

enum class HomeState { RUNNING, ATTENTION, PAUSED, IDLE, ERROR, STOPPED }

/** Modes change only with nothing running or waiting to be restored. */
fun canSwitchMode(phase: TaskPhase, hasSavedSession: Boolean): Boolean =
    phase == TaskPhase.IDLE && !hasSavedSession

/** The single state shown on the home screen card. */
fun homeStateOf(status: TaskStatus, stoppedByError: Boolean): HomeState = when {
    status.phase == TaskPhase.PAUSED -> HomeState.PAUSED
    status.idleUntilMs > 0 || status.scheduleNeeded -> HomeState.IDLE
    status.heldByLock -> HomeState.PAUSED
    status.phase == TaskPhase.RUNNING && status.barState != BarState.NORMAL -> HomeState.ATTENTION
    status.phase == TaskPhase.RUNNING -> HomeState.RUNNING
    stoppedByError -> HomeState.ERROR
    else -> HomeState.STOPPED
}
