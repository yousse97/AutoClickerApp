package com.autoclicker.chattask.core

enum class StoppedAction { NONE, SHOW_IDLE, START }

/**
 * What happens with no session running. Pressing Stop holds until Start is pressed. A running
 * session never comes through here: it stays up through idle hours and resumes by itself.
 */
object StopRule {
    const val UNTIL_START = Long.MAX_VALUE

    fun mayAutoStart(blockedUntilMs: Long, nowMs: Long): Boolean = nowMs >= blockedUntilMs

    fun whenStopped(hasHours: Boolean, inHours: Boolean, blockedUntilMs: Long, nowMs: Long): StoppedAction = when {
        !hasHours || blockedUntilMs == UNTIL_START -> StoppedAction.NONE
        !inHours || !mayAutoStart(blockedUntilMs, nowMs) -> StoppedAction.SHOW_IDLE
        else -> StoppedAction.START
    }
}
