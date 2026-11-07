package com.autoclicker.chattask.ui

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.OperatingMode
import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.databinding.ModeChoiceBinding
import com.autoclicker.chattask.service.AutoTaskService
import com.autoclicker.chattask.service.OverlayService
import com.autoclicker.chattask.service.ScheduleKeeper
import com.autoclicker.chattask.service.TaskBus
import com.autoclicker.chattask.service.canSwitchMode
import java.time.LocalDateTime

/** The one place the mode changes. A running session has to be stopped first. */
class ModeChoice(
    private val binding: ModeChoiceBinding,
    private val store: SettingsStore,
    private val onChanged: () -> Unit,
) {
    private var showing = false

    init {
        binding.root.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !showing) select(if (id == binding.buttonModeAutoReply.id) OperatingMode.AUTO_REPLY else OperatingMode.SCHEDULED)
        }
        show()
    }

    fun show() {
        showing = true
        val auto = store.operatingMode == OperatingMode.AUTO_REPLY
        binding.root.check(if (auto) binding.buttonModeAutoReply.id else binding.buttonModeScheduled.id)
        showing = false
    }

    private fun select(mode: OperatingMode) {
        if (mode == store.operatingMode) return
        if (!canSwitchMode(TaskBus.status.value.phase, store.loadSession() != null)) {
            Toast.makeText(binding.root.context, R.string.toast_stop_before_mode, Toast.LENGTH_LONG).show()
            return show()
        }
        store.operatingMode = mode
        // Choosing a mode starts nothing, even inside its hours: that takes Start or the next start.
        store.blockedUntilMs = ScheduleKeeper.blockUntil(store.loadConfig(), LocalDateTime.now())
        AutoTaskService.instance?.reloadSettings()
        onChanged()
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
