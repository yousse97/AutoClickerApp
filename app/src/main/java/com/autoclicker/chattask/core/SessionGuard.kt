package com.autoclicker.chattask.core

/** What is on screen. Only the name of the foreground app is used to tell these apart. */
enum class Foreground { WOLF, HOME, OTHER_APP, LOCKED }

enum class GuardAction { NONE, RELAUNCH, STOP_LOCKED, STOP_RELAUNCH_FAILED, STOP_SEND_FAILURES }

/**
 * Decides when a session should reopen WOLF or stop. Pure logic, called once a second.
 *
 * Rest, a locked screen and another app are never failures. Not thread-safe.
 */
class SessionGuard(
    private val lockTimeoutMs: Long = 15 * 60_000L,
    private val homeTimeoutMs: Long = 60_000L,
    private val maxRelaunchFailures: Int = 3,
    private val maxSendFailures: Int = 5,
) {
    private var lockedSinceMs: Long? = null
    private var homeSinceMs: Long? = null
    private var relaunchSoon = false
    private var relaunchFailures = 0
    private var nextRetryMs = 0L

    var sendFailures = 0
        private set

    fun observe(foreground: Foreground, resting: Boolean, now: Long): GuardAction {
        // The lock timeout counts work time only, so it starts when a rest ends.
        if (foreground != Foreground.LOCKED || resting) lockedSinceMs = null
        if (foreground != Foreground.HOME || resting) homeSinceMs = null
        if (resting) return GuardAction.NONE
        return when (foreground) {
            Foreground.LOCKED -> onLocked(now)
            Foreground.HOME -> onHome(now)
            Foreground.WOLF, Foreground.OTHER_APP -> {
                relaunchSoon = false
                GuardAction.NONE
            }
        }
    }

    private fun onLocked(now: Long): GuardAction {
        val since = lockedSinceMs ?: now.also { lockedSinceMs = it }
        return if (now - since >= lockTimeoutMs) GuardAction.STOP_LOCKED else GuardAction.NONE
    }

    private fun onHome(now: Long): GuardAction {
        val since = homeSinceMs ?: now.also { homeSinceMs = it }
        val waitedLongEnough = now - since >= homeTimeoutMs && now >= nextRetryMs
        if (!relaunchSoon && !waitedLongEnough) return GuardAction.NONE
        relaunchSoon = false
        homeSinceMs = null
        return GuardAction.RELAUNCH
    }

    /** Spaces out navigation attempts, so a failing one does not fight the user every second. */
    fun mayNavigate(now: Long): Boolean = now >= nextRetryMs

    /** A rest has ended, so the home screen need not stay up for the full wait. */
    fun onRestFinished() {
        relaunchSoon = true
    }

    fun onRelaunchResult(success: Boolean, now: Long): GuardAction {
        if (success) {
            relaunchFailures = 0
            return GuardAction.NONE
        }
        relaunchFailures++
        nextRetryMs = now + homeTimeoutMs
        return if (relaunchFailures >= maxRelaunchFailures) {
            GuardAction.STOP_RELAUNCH_FAILED
        } else {
            GuardAction.NONE
        }
    }

    fun onSendResult(success: Boolean): GuardAction {
        sendFailures = if (success) 0 else sendFailures + 1
        return if (sendFailures >= maxSendFailures) GuardAction.STOP_SEND_FAILURES else GuardAction.NONE
    }
}
