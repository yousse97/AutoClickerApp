package com.autoclicker.chattask.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.autoclicker.chattask.core.ConfigError
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.data.SettingsStore
import com.autoclicker.chattask.databinding.QuickCommandsBinding
import com.autoclicker.chattask.databinding.QuickReplyBinding

/** Builds the fields Home shows for a mode. They read and write the same saved values as the pages. */
fun quickSection(form: QuickForm, inflater: LayoutInflater, parent: ViewGroup): SettingsSection = when (form) {
    QuickForm.COMMANDS -> QuickCommandsSection(inflater, parent)
    QuickForm.AUTO_REPLY -> QuickReplySection(inflater, parent)
}

/** Commands and intervals. Each command's hours stay hidden here and are kept as saved. */
private class QuickCommandsSection(inflater: LayoutInflater, parent: ViewGroup) : SettingsSection {
    private val binding = QuickCommandsBinding.inflate(inflater, parent, false)
    private val editor = CommandListEditor(binding.commandList, binding.buttonAddCommand, showHours = false) {}
    override val view: View get() = binding.root

    override fun show(config: TaskConfig) {
        editor.show(config.commands)
        binding.inputRoom.setText(config.targetRoom)
    }

    override fun read(base: TaskConfig) = base.withQuickCommands(editor.read(), binding.inputRoom.textValue())

    override fun showErrors(errors: List<ConfigError>, message: (ConfigError) -> String) {
        editor.showBlankError(errors.messageOf(message, ConfigError.EMPTY_COMMAND))
        binding.layoutRoom.error = errors.messageOf(message, ConfigError.ROOM_TOO_LONG, ConfigError.ROOM_REQUIRED)
        val others = errors - setOf(ConfigError.EMPTY_COMMAND, ConfigError.ROOM_TOO_LONG, ConfigError.ROOM_REQUIRED)
        binding.textCommandsError.text = others.firstOrNull()?.let(message)
        binding.textCommandsError.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
    }

    /** The rows share view ids, so the framework cannot restore them. */
    override fun saveState(state: Bundle) =
        state.putString(STATE_COMMANDS, SettingsStore.encodeCommands(editor.read()))

    override fun restoreState(state: Bundle) {
        state.getString(STATE_COMMANDS)?.let(SettingsStore::decodeCommands)?.let(editor::show)
    }

    private companion object {
        const val STATE_COMMANDS = "quick_commands"
    }
}

private class QuickReplySection(inflater: LayoutInflater, parent: ViewGroup) : SettingsSection {
    private val binding = QuickReplyBinding.inflate(inflater, parent, false)
    override val view: View get() = binding.root

    override fun show(config: TaskConfig) = with(binding) {
        inputTriggers.setText(config.reactive.triggers.joinToString("\n"))
        inputReaction.setText(config.reactive.command)
        inputRoom.setText(config.reactive.room)
    }

    override fun read(base: TaskConfig) = with(binding) {
        base.withQuickReply(inputTriggers.textValue(), inputReaction.textValue(), inputRoom.textValue())
    }

    override fun showErrors(errors: List<ConfigError>, message: (ConfigError) -> String) = with(binding) {
        layoutTriggers.error = errors.messageOf(message, ConfigError.REACTIVE_NO_TRIGGER)
        layoutReaction.error = errors.messageOf(message, ConfigError.REACTIVE_NO_COMMAND, ConfigError.COMMAND_TOO_LONG)
        layoutRoom.error = errors.messageOf(message, ConfigError.REACTIVE_ROOM_REQUIRED, ConfigError.ROOM_TOO_LONG)
    }
}
