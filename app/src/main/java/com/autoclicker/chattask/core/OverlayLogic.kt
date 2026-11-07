package com.autoclicker.chattask.core

import kotlin.math.abs

/** Tells a tap from a drag, so moving the bar can never act as a tap. */
class TapTracker(private val slopPx: Float) {
    private var startX = 0f
    private var startY = 0f
    var isDrag = false
        private set

    fun down(x: Float, y: Float) {
        startX = x
        startY = y
        isDrag = false
    }

    fun move(x: Float, y: Float) {
        if (abs(x - startX) > slopPx || abs(y - startY) > slopPx) isDrag = true
    }

    /** Returns true if the finger lifted without ever dragging. */
    fun up(x: Float, y: Float): Boolean {
        move(x, y)
        return !isDrag
    }
}

/**
 * Decides when to bring the user back to WOLF ahead of a send. It acts once per command, and the
 * due time it is given already includes jitter. A locked phone is never pulled.
 */
class ReturnPlanner(private val leadMs: Long = 5_000) {
    private var returnedSlot: Long? = null

    fun shouldReturn(slot: Long, nextInMs: Long, working: Boolean, foreground: Foreground, enabled: Boolean): Boolean {
        val locked = foreground == Foreground.LOCKED
        if (!enabled || !working || locked || nextInMs > leadMs || returnedSlot == slot) return false
        returnedSlot = slot
        return true
    }
}

enum class BarState { NORMAL, SEND_SOON, COMMAND_READY }

/** Warns someone outside WOLF that a send is coming, with one vibration per command. */
class PreSendAlert(private val windowMs: Long = 30_000) {
    private var vibratedSlot: Long? = null

    fun barState(nextInMs: Long, working: Boolean, foreground: Foreground): BarState = when {
        !working -> BarState.NORMAL
        nextInMs <= 0 && foreground != Foreground.WOLF -> BarState.COMMAND_READY
        nextInMs <= windowMs -> BarState.SEND_SOON
        else -> BarState.NORMAL
    }

    /** [slot] identifies the command being counted down to. */
    fun shouldVibrate(slot: Long, nextInMs: Long, working: Boolean, foreground: Foreground, enabled: Boolean): Boolean {
        val outside = foreground == Foreground.HOME || foreground == Foreground.OTHER_APP
        val inWindow = nextInMs in 1..windowMs
        if (!enabled || !working || !outside || !inWindow || vibratedSlot == slot) return false
        vibratedSlot = slot
        return true
    }
}
