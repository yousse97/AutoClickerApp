package com.autoclicker.chattask.service

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.autoclicker.chattask.core.Foreground
import com.autoclicker.chattask.core.PreSendAlert
import com.autoclicker.chattask.core.ReactiveConfig
import com.autoclicker.chattask.core.ReturnPlanner
import java.time.LocalDateTime

/** Feeds the floating bar its warning state, and vibrates once before a send outside WOLF. */
class BarWatcher(private val context: Context) {
    private val alert = PreSendAlert()
    private var planner = ReturnPlanner()
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

    /** Keeps the bar current and says whether to return now, for either mode. */
    fun shouldReturn(replies: ReactiveConfig?, view: RunnerView, working: Boolean, foreground: Foreground): Boolean {
        if (replies == null) return update(view, working, foreground)
        update(view, false, foreground)
        return returnForReplies(replies, foreground)
    }

    /** Each session makes its own first return. */
    fun newSession() {
        planner = ReturnPlanner()
    }

    /**
     * Replies have no send time to count down to, and a trigger can only be seen on screen, so the
     * return comes once per stretch of active hours: 5 seconds before it begins, or at Start.
     */
    private fun returnForReplies(config: ReactiveConfig, foreground: Foreground): Boolean {
        val slot = config.nextSlot(LocalDateTime.now()) ?: return false
        return planner.shouldReturn(slot.id, slot.startsInMs, true, foreground, returnBeforeSend)
    }

    private companion object {
        const val VIBRATE_MS = 120L
    }
}
