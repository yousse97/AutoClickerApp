package com.autoclicker.chattask.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CommandEntry
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.databinding.RowCommandBinding

/**
 * The dynamic command rows. They share view ids, so the framework cannot restore them. The list
 * opts out of view state, and the activity saves [read] across recreation instead.
 */
class CommandListEditor(
    private val list: LinearLayout,
    private val addButton: View,
    private val onChanged: () -> Unit,
) {
    private class Row(val binding: RowCommandBinding, val hours: HoursEditor)

    private val rows = ArrayList<Row>()

    init {
        list.isSaveFromParentEnabled = false
        addButton.setOnClickListener { addRow(CommandEntry("")) }
    }

    fun show(commands: List<CommandEntry>) {
        rows.clear()
        list.removeAllViews()
        commands.take(TaskConfig.MAX_COMMANDS).forEach(::addRow)
    }

    fun read(): List<CommandEntry> = rows.map { row ->
        val minutes = row.binding.inputMinutes.text?.toString()?.toIntOrNull() ?: 0
        val seconds = row.binding.inputSeconds.text?.toString()?.toIntOrNull() ?: 0
        CommandEntry(
            text = row.binding.inputText.text?.toString().orEmpty().trim(),
            intervalSec = (minutes * 60L + seconds).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            allDay = row.hours.allDay,
            startMinute = row.hours.start,
            endMinute = row.hours.end,
        )
    }

    /** Marks blank rows, or clears the marks when [message] is null. */
    fun showBlankError(message: String?) {
        rows.forEach { row ->
            row.binding.layoutText.error = message?.takeIf { row.binding.inputText.text.isNullOrBlank() }
        }
    }

    private fun addRow(command: CommandEntry) {
        val binding = RowCommandBinding.inflate(LayoutInflater.from(list.context), list, false)
        val hours = HoursEditor(binding.hours, R.string.summary_pick_times) { refresh() }
        val row = Row(binding, hours)
        binding.inputText.setText(command.text)
        if (command.intervalSec > 0) {
            binding.inputMinutes.setText((command.intervalSec / 60).toString())
            binding.inputSeconds.setText((command.intervalSec % 60).toString())
        }
        hours.show(command.allDay, command.startMinute, command.endMinute)
        binding.buttonRemove.setOnClickListener {
            rows.remove(row)
            list.removeView(binding.root)
            refresh()
        }
        rows.add(row)
        list.addView(binding.root)
        refresh()
    }

    private fun refresh() {
        val context = list.context
        rows.forEachIndexed { index, row ->
            val binding = row.binding
            binding.layoutText.hint = context.getString(R.string.label_command_numbered, index + 1)
            binding.buttonRemove.visibility = if (rows.size > 1) View.VISIBLE else View.GONE
            row.hours.render()
        }
        addButton.visibility = if (rows.size < TaskConfig.MAX_COMMANDS) View.VISIBLE else View.GONE
        onChanged()
    }
}
