package com.autoclicker.chattask.service

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TaskPhase { IDLE, RUNNING, PAUSED }

data class TaskStatus(
    val phase: TaskPhase = TaskPhase.IDLE,
    val target: String = "",
    val nextInMs: Long = 0,
    val sessionLeftMs: Long = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val popups: Int = 0,
    val keepScreenOn: Boolean = false,
    /** Message for the overlay, or 0 for none. */
    @StringRes val notice: Int = 0,
)

/** In-process status channel from the accessibility service to the overlay. */
object TaskBus {
    private val mutableStatus = MutableStateFlow(TaskStatus())
    val status: StateFlow<TaskStatus> = mutableStatus.asStateFlow()

    fun update(transform: (TaskStatus) -> TaskStatus) = mutableStatus.update(transform)
}
