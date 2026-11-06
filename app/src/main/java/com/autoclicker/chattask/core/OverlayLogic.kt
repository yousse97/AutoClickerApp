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
