package com.autoclicker.chattask.ui

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CommandEntry
import com.autoclicker.chattask.core.SendMode
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.databinding.RowCommandBinding

/**
 * The dynamic command rows. They share view ids, so the framework cannot restore them. The list
 * opts out of view state, and the activity saves [read] across recreation instead.
 */
class CommandListEditor(private val list: LinearLayout, private val addButton: View) {
    private val rows = ArrayList<RowCommandBinding>()

    init {
        list.isSaveFromParentEnabled = false
        addButton.setOnClickListener { addRow(CommandEntry("")) }
    }

    fun show(commands: List<CommandEntry>) {
        rows.clear()
        list.removeAllViews()
        commands.take(TaskConfig.MAX_COMMANDS).forEach(::addRow)
    }

    fun read(): List<CommandEntry> = rows.mapIndexed { index, row ->
        val delayed = index > 0 && row.radioDelay.isChecked
        CommandEntry(
            text = row.inputText.text?.toString().orEmpty().trim(),
            mode = if (delayed) SendMode.DELAYED else SendMode.SIMULTANEOUS,
            delaySec = row.inputDelay.text?.toString()?.toIntOrNull() ?: 0,
        )
    }

    /** Marks blank rows, or clears the marks when [message] is null. */
    fun showBlankError(message: String?) {
        rows.forEach { row ->
            row.layoutText.error = message?.takeIf { row.inputText.text.isNullOrBlank() }
        }
    }

    private fun addRow(command: CommandEntry) {
        val row = RowCommandBinding.inflate(LayoutInflater.from(list.context), list, false)
        row.inputText.setText(command.text)
        if (command.delaySec > 0) row.inputDelay.setText(command.delaySec.toString())
        row.groupMode.check(
            if (command.mode == SendMode.DELAYED) R.id.radioDelay else R.id.radioSimultaneous,
        )
        row.groupMode.setOnCheckedChangeListener { _, _ -> refresh() }
        row.buttonRemove.setOnClickListener {
            rows.remove(row)
            list.removeView(row.root)
            refresh()
        }
        rows.add(row)
        list.addView(row.root)
        refresh()
    }

    private fun refresh() {
        rows.forEachIndexed { index, row ->
            val context = row.root.context
            row.layoutText.hint = context.getString(R.string.label_command_numbered, index + 1)
            row.buttonRemove.visibility = if (rows.size > 1) View.VISIBLE else View.GONE
            row.groupMode.visibility = if (index > 0) View.VISIBLE else View.GONE
            row.layoutDelay.visibility =
                if (index > 0 && row.radioDelay.isChecked) View.VISIBLE else View.GONE
        }
        addButton.visibility = if (rows.size < TaskConfig.MAX_COMMANDS) View.VISIBLE else View.GONE
    }
}
