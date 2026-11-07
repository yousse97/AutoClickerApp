package com.autoclicker.chattask.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CommandEntry
import com.autoclicker.chattask.core.ConfigError
import com.autoclicker.chattask.core.OperatingMode
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
import com.autoclicker.chattask.service.TaskPhase
import com.autoclicker.chattask.service.TaskStatus
import com.autoclicker.chattask.service.homeStateOf

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

    /** Called whenever the session changes, for sections that show it. */
    fun onStatus(status: TaskStatus) = Unit

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

/** Everything Auto reply needs lives here, so it works without the Commands page. */
class AutoReplySection(
    inflater: LayoutInflater,
    parent: ViewGroup,
    private val store: SettingsStore,
    private val onStart: () -> Unit,
) : SettingsSection {
    private val binding = SectionAutoReplyBinding.inflate(inflater, parent, false)
    private val replyHours = HoursEditor(binding.hours, R.string.summary_reply_pick_times) {}
    private val modeChoice = ModeChoice(binding.modeChoice, store) { onStatus(TaskBus.status.value) }
    override val view: View get() = binding.root

    init {
        binding.buttonReplyStart.setOnClickListener {
            if (store.operatingMode == OperatingMode.AUTO_REPLY) {
                onStart()
            } else {
                Toast.makeText(view.context, R.string.toast_choose_auto_reply, Toast.LENGTH_LONG).show()
            }
        }
        binding.buttonReplyStop.setOnClickListener { AutoTaskService.instance?.stopSession() }
    }

    override fun onResume() = modeChoice.show()

    override fun onStatus(status: TaskStatus) {
        val context = view.context
        val selected = store.operatingMode == OperatingMode.AUTO_REPLY
        val state = context.getString(titleOf(homeStateOf(status, status.stoppedByError)))
        binding.textReplyStatus.text = context.getString(
            R.string.overlay_mode_state,
            context.getString(store.operatingMode.shortTitle),
            state,
        )
        binding.buttonReplyStart.isEnabled = status.phase == TaskPhase.IDLE
        binding.buttonReplyStop.isEnabled = selected && status.phase != TaskPhase.IDLE
    }

    override fun show(config: TaskConfig) = with(binding) {
        inputTriggers.setText(config.reactive.triggers.joinToString("\n"))
        inputReaction.setText(config.reactive.command)
        checkRestrictReactive.isChecked = config.reactive.restrictToRoom
        inputReactiveRoom.setText(config.reactive.room)
        switchReplyReturn.isChecked = config.reactive.returnBeforeSend
        replyHours.show(config.reactive.hours.allDay, config.reactive.hours.startMinute, config.reactive.hours.endMinute)
        inputKeywords.setText(config.popupKeywords.joinToString(", "))
        switchSkipLists.isChecked = config.skipListItems
    }

    override fun read(base: TaskConfig) = with(binding) {
        base.copy(
            reactive = ReactiveConfig(
                triggers = TaskConfig.parseKeywords(inputTriggers.textValue()),
                command = inputReaction.textValue().trim(),
                restrictToRoom = checkRestrictReactive.isChecked,
                room = inputReactiveRoom.textValue().trim(),
                returnBeforeSend = switchReplyReturn.isChecked,
                hours = readHours(),
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

    private fun readHours() = CommandEntry("", allDay = replyHours.allDay, startMinute = replyHours.start, endMinute = replyHours.end)

    /** The picked times live outside any view, so the framework cannot restore them. */
    override fun saveState(state: Bundle) = state.putString(STATE_HOURS, SettingsStore.encodeReplyHours(readHours()))

    override fun restoreState(state: Bundle) {
        state.getString(STATE_HOURS)?.let(SettingsStore::decodeReplyHours)?.let {
            replyHours.show(it.allDay, it.startMinute, it.endMinute)
        }
    }

    private companion object {
        const val STATE_HOURS = "reply_hours"
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
