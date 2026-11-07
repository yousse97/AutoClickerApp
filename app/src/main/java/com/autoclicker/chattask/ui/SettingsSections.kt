package com.autoclicker.chattask.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.ConfigError
import com.autoclicker.chattask.core.ReactiveConfig
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.databinding.SectionAutoReplyBinding
import com.autoclicker.chattask.databinding.SectionChannelBinding
import com.autoclicker.chattask.databinding.SectionCommandsBinding
import com.autoclicker.chattask.databinding.SectionTimerBinding
import com.autoclicker.chattask.service.AutoTaskService
import com.autoclicker.chattask.service.Notices
import com.autoclicker.chattask.service.TaskBus

/**
 * One group of settings, used unchanged by its own page and by All Settings. Saved values live only
 * in [SettingsStore], which is what keeps the two views the same.
 */
interface SettingsSection {
    val view: View

    /** False when everything here applies at once, so the page needs no Save button. */
    val hasFormFields: Boolean get() = true

    fun show(config: TaskConfig)

    /** Returns [base] with this section's fields taken from the screen. */
    fun read(base: TaskConfig): TaskConfig

    /** Marks this section's errors, or clears them when given none. */
    fun showErrors(errors: List<ConfigError>, message: (ConfigError) -> String) = Unit

    fun onResume() = Unit
    fun saveState(state: Bundle) = Unit
    fun restoreState(state: Bundle) = Unit
}

private fun List<ConfigError>.messageOf(message: (ConfigError) -> String, vararg matching: ConfigError) =
    firstOrNull { it in matching }?.let(message)

private fun android.widget.EditText.textValue() = text?.toString().orEmpty()

private fun android.widget.EditText.intValue(): Int = textValue().toIntOrNull() ?: 0

class CommandsSection(
    inflater: LayoutInflater,
    parent: ViewGroup,
    private val store: SettingsStore,
) : SettingsSection {
    private val binding = SectionCommandsBinding.inflate(inflater, parent, false)
    private val editor = CommandListEditor(binding.commandList, binding.buttonAddCommand, ::showPrompt)
    override val view: View get() = binding.root

    override fun show(config: TaskConfig) {
        binding.inputJitterMin.setText(config.jitterMinSec.toString())
        binding.inputJitterMax.setText(config.jitterMaxSec.toString())
        editor.show(config.commands)
    }

    override fun read(base: TaskConfig) = base.copy(
        commands = editor.read(),
        jitterMinSec = binding.inputJitterMin.intValue(),
        jitterMaxSec = binding.inputJitterMax.intValue(),
    )

    private fun showPrompt() {
        val needed = store.legacySchedulePrompt || editor.read().any { it.needsSchedule }
        binding.textSchedulePrompt.visibility = if (needed) View.VISIBLE else View.GONE
    }

    override fun showErrors(errors: List<ConfigError>, message: (ConfigError) -> String) {
        editor.showBlankError(errors.messageOf(message, ConfigError.EMPTY_COMMAND))
        val listError = errors.messageOf(
            message,
            ConfigError.COMMAND_TOO_LONG,
            ConfigError.TOO_MANY_COMMANDS,
            ConfigError.INTERVAL_TOO_SHORT,
            ConfigError.JITTER_TOO_LARGE,
        )
        binding.textCommandsError.text = listError
        binding.textCommandsError.visibility = if (listError == null) View.GONE else View.VISIBLE
        binding.layoutJitterMin.error = errors.messageOf(message, ConfigError.JITTER_RANGE)
    }

    /** The rows share view ids, so the framework cannot restore them. */
    override fun saveState(state: Bundle) =
        state.putString(STATE_COMMANDS, SettingsStore.encodeCommands(editor.read()))

    override fun restoreState(state: Bundle) {
        state.getString(STATE_COMMANDS)?.let(SettingsStore::decodeCommands)?.let(editor::show)
    }

    private companion object {
        const val STATE_COMMANDS = "commands"
    }
}

class AutoReplySection(inflater: LayoutInflater, parent: ViewGroup) : SettingsSection {
    private val binding = SectionAutoReplyBinding.inflate(inflater, parent, false)
    override val view: View get() = binding.root

    override fun show(config: TaskConfig) = with(binding) {
        switchReactive.isChecked = config.reactive.enabled
        inputTriggers.setText(config.reactive.triggers.joinToString("\n"))
        inputReaction.setText(config.reactive.command)
        checkRestrictReactive.isChecked = config.reactive.restrictToRoom
        inputReactiveRoom.setText(config.reactive.room)
        inputKeywords.setText(config.popupKeywords.joinToString(", "))
        switchSkipLists.isChecked = config.skipListItems
    }

    override fun read(base: TaskConfig) = with(binding) {
        base.copy(
            reactive = ReactiveConfig(
                enabled = switchReactive.isChecked,
                triggers = TaskConfig.parseKeywords(inputTriggers.textValue()),
                command = inputReaction.textValue().trim(),
                restrictToRoom = checkRestrictReactive.isChecked,
                room = inputReactiveRoom.textValue().trim(),
            ),
            popupKeywords = TaskConfig.parseKeywords(inputKeywords.textValue()),
            skipListItems = switchSkipLists.isChecked,
        )
    }

    override fun showErrors(errors: List<ConfigError>, message: (ConfigError) -> String) = with(binding) {
        layoutTriggers.error = errors.messageOf(message, ConfigError.REACTIVE_NO_TRIGGER)
        layoutReaction.error = errors.messageOf(message, ConfigError.REACTIVE_NO_COMMAND)
        layoutReactiveRoom.error = errors.messageOf(message, ConfigError.REACTIVE_ROOM_REQUIRED)
    }
}

class ChannelSection(
    inflater: LayoutInflater,
    parent: ViewGroup,
    onToggle: () -> Unit,
) : SettingsSection {
    private val binding = SectionChannelBinding.inflate(inflater, parent, false)
    override val view: View get() = binding.root

    init {
        binding.buttonTestChannel.setOnClickListener { testChannel() }
        val toggles = listOf(binding.switchReturn, binding.switchIdleScreen, binding.switchKeepScreen)
        toggles.forEach { toggle -> toggle.setOnClickListener { onToggle() } }
    }

    override fun show(config: TaskConfig) = with(binding) {
        inputRoom.setText(config.targetRoom)
        checkRestrictRoom.isChecked = config.restrictToRoom
        switchReturn.isChecked = config.returnBeforeSend
        switchIdleScreen.isChecked = config.keepScreenWhenIdle
        switchKeepScreen.isChecked = config.keepScreenOn
    }

    override fun read(base: TaskConfig) = with(binding) {
        base.copy(
            targetRoom = inputRoom.textValue().trim(),
            restrictToRoom = checkRestrictRoom.isChecked,
            returnBeforeSend = switchReturn.isChecked,
            keepScreenWhenIdle = switchIdleScreen.isChecked,
            keepScreenOn = switchKeepScreen.isChecked,
        )
    }

    override fun showErrors(errors: List<ConfigError>, message: (ConfigError) -> String) {
        binding.layoutRoom.error =
            errors.messageOf(message, ConfigError.ROOM_TOO_LONG, ConfigError.ROOM_REQUIRED)
    }

    /** Walks to the channel and verifies it. It never types or sends. */
    private fun testChannel() {
        val context = binding.root.context
        val room = binding.inputRoom.textValue().trim()
        val service = AutoTaskService.instance
        when {
            room.isEmpty() -> binding.layoutRoom.error = context.getString(R.string.error_room_required)
            service == null -> Toast.makeText(context, R.string.toast_permissions_needed, Toast.LENGTH_LONG).show()
            else -> {
                binding.layoutRoom.error = null
                service.testNavigation(room) { result ->
                    val message = Notices.forRecovery(result).takeIf { it != 0 } ?: R.string.toast_channel_ok
                    Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** The size applies to the bar straight away, and vibration saves with the page's other toggles. */
class TimerSection(
    inflater: LayoutInflater,
    parent: ViewGroup,
    private val store: SettingsStore,
    onToggle: () -> Unit,
) : SettingsSection {
    private val binding = SectionTimerBinding.inflate(inflater, parent, false)
    private val sizes = mapOf(
        R.id.radioSizeSmall to SettingsStore.OVERLAY_SIZE_SMALL,
        R.id.radioSizeMedium to SettingsStore.OVERLAY_SIZE_MEDIUM,
        R.id.radioSizeLarge to SettingsStore.OVERLAY_SIZE_LARGE,
    )
    override val view: View get() = binding.root
    override val hasFormFields: Boolean get() = false

    init {
        binding.groupOverlaySize.setOnCheckedChangeListener { _, id ->
            sizes[id]?.let { size ->
                store.overlaySizeIndex = size
                TaskBus.update { it.copy(overlaySize = size) }
            }
        }
        binding.switchVibrate.setOnClickListener { onToggle() }
    }

    override fun show(config: TaskConfig) {
        sizes.entries.first { it.value == store.overlaySizeIndex }.let { binding.groupOverlaySize.check(it.key) }
        binding.switchVibrate.isChecked = config.vibrateBeforeSend
    }

    override fun read(base: TaskConfig) = base.copy(vibrateBeforeSend = binding.switchVibrate.isChecked)
}
