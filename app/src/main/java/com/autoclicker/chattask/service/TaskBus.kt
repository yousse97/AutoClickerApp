package com.autoclicker.chattask.service

import androidx.annotation.StringRes
import com.autoclicker.chattask.core.BarState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class TaskPhase { IDLE, RUNNING, PAUSED }

data class TaskStatus(
    val phase: TaskPhase = TaskPhase.IDLE,
    val target: String = "",
    val nextInMs: Long = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val popups: Int = 0,
    val reactions: Int = 0,
    val barState: BarState = BarState.NORMAL,
    /** The wall clock time idle ends, or 0 when the daily schedule is not idling. */
    val idleUntilMs: Long = 0,
    val idleKeepScreen: Boolean = false,
    /** Nothing can start until a Daily command gets its times. */
    val scheduleNeeded: Boolean = false,
    val heldByLock: Boolean = false,
    /** The size chosen in settings, or -1 until one is chosen in this run. */
    val overlaySize: Int = -1,
    val stoppedByError: Boolean = false,
    val nextIndex: Int? = null,
    val nextTotalMs: Long = 0,
    val commandNames: List<String> = emptyList(),
    val commands: List<CommandView> = emptyList(),
    val keepScreenOn: Boolean = false,
    /** Message for the overlay, or 0 for none. */
    @StringRes val notice: Int = 0,
)

/** In-process status channel from the accessibility service to the overlay. */
object TaskBus {
    private val mutableStatus = MutableStateFlow(TaskStatus())
    val status: StateFlow<TaskStatus> = mutableStatus.asStateFlow()

    fun update(transform: (TaskStatus) -> TaskStatus) = mutableStatus.update(transform)

    fun show(view: RunnerView) = update {
        it.copy(
            phase = view.phase,
            nextInMs = view.nextInMs,
            sent = view.sent,
            failed = view.failed,
            nextIndex = view.nextIndex,
            nextTotalMs = view.nextTotalMs,
            commands = view.commands,
        )
    }
}
