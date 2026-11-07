package com.autoclicker.chattask.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.OperatingMode
import com.autoclicker.chattask.core.StopRule
import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.service.AutoTaskService
import com.autoclicker.chattask.service.OverlayService
import com.autoclicker.chattask.service.TaskBus
import com.autoclicker.chattask.service.canSwitchMode

/** The one place the mode changes. A running session has to be stopped first. */
object ModeChoice {
    /** Returns true if [mode] is now selected. Choosing one starts nothing: that takes Start. */
    fun select(activity: AppCompatActivity, store: SettingsStore, mode: OperatingMode): Boolean {
        if (mode == store.operatingMode) return true
        if (!canSwitchMode(TaskBus.status.value.phase, store.loadSession() != null)) {
            Toast.makeText(activity, R.string.toast_stop_before_mode, Toast.LENGTH_LONG).show()
            return false
        }
        store.operatingMode = mode
        store.blockedUntilMs = StopRule.UNTIL_START
        AutoTaskService.instance?.reloadSettings()
        return true
    }
}

/** Shared by Home and the Auto reply page. */
object SessionControl {
    fun start(activity: AppCompatActivity, onBlocked: (HomeWarning) -> Unit) {
        val config = SettingsStore(activity).loadConfig()
        val blocker = homeWarnings(config, readPermissions(activity)).firstOrNull { it.blocksStart }
        val service = AutoTaskService.instance
        when {
            blocker != null -> {
                Toast.makeText(activity, blocker.text, Toast.LENGTH_LONG).show()
                onBlocked(blocker)
            }
            service == null -> Toast.makeText(activity, R.string.toast_permissions_needed, Toast.LENGTH_LONG).show()
            // Finishing frees memory and leaves no recents card, as both were behind process kills.
            OverlayService.start(activity) -> {
                service.startFromHome()
                activity.finishAndRemoveTask()
            }
        }
    }
}
