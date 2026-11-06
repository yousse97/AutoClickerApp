package com.autoclicker.chattask.service

import com.autoclicker.chattask.core.BarState

enum class HomeState { RUNNING, ATTENTION, PAUSED, REST, IDLE, ERROR, STOPPED }

/** The single state shown on the home screen card. */
fun homeStateOf(status: TaskStatus, stoppedByError: Boolean): HomeState = when {
    status.phase == TaskPhase.RESTING -> HomeState.REST
    status.phase == TaskPhase.PAUSED || status.heldByLock -> HomeState.PAUSED
    status.phase == TaskPhase.RUNNING && status.barState != BarState.NORMAL -> HomeState.ATTENTION
    status.phase == TaskPhase.RUNNING -> HomeState.RUNNING
    status.idleUntilMs > 0 -> HomeState.IDLE
    stoppedByError -> HomeState.ERROR
    else -> HomeState.STOPPED
}
