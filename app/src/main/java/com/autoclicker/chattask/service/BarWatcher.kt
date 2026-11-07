package com.autoclicker.chattask.service

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.autoclicker.chattask.core.Foreground
import com.autoclicker.chattask.core.PreSendAlert
import com.autoclicker.chattask.core.ReturnPlanner

/** Feeds the floating bar its warning state, and vibrates once before a send outside WOLF. */
class BarWatcher(private val context: Context) {
    private val alert = PreSendAlert()
    private val planner = ReturnPlanner()
    var vibrate = true
    var returnBeforeSend = true

    /** Returns true when it is time to bring the user back to WOLF for the next send. */
    fun update(view: RunnerView, working: Boolean, foreground: Foreground): Boolean {
        val slot = (view.sent + view.failed).toLong() shl 8 or (view.nextIndex ?: 0).toLong()
        if (alert.shouldVibrate(slot, view.nextInMs, working, foreground, vibrate)) {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        }
        val bar = alert.barState(view.nextInMs, working, foreground)
        TaskBus.update { it.copy(barState = bar, heldByLock = foreground == Foreground.LOCKED) }
        return planner.shouldReturn(slot, view.nextInMs, working, foreground, returnBeforeSend)
    }

    private companion object {
        const val VIBRATE_MS = 120L
    }
}
