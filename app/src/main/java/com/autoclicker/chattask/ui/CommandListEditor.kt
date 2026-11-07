package com.autoclicker.chattask.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CommandEntry
import com.autoclicker.chattask.core.DailySchedule
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.databinding.RowCommandBinding
import java.util.Calendar

/**
 * The dynamic command rows. They share view ids, so the framework cannot restore them. The list
 * opts out of view state, and the activity saves [read] across recreation instead.
 */
class CommandListEditor(
    private val list: LinearLayout,
    private val addButton: View,
    private val onChanged: () -> Unit,
) {
    private class Row(val binding: RowCommandBinding, var start: Int, var end: Int)

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
            allDay = row.binding.radioAllDay.isChecked,
            startMinute = row.start,
            endMinute = row.end,
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
        val row = Row(binding, command.startMinute, command.endMinute)
        binding.inputText.setText(command.text)
        if (command.intervalSec > 0) {
            binding.inputMinutes.setText((command.intervalSec / 60).toString())
            binding.inputSeconds.setText((command.intervalSec % 60).toString())
        }
        binding.groupHours.check(if (command.allDay) R.id.radioAllDay else R.id.radioDaily)
        binding.groupHours.setOnCheckedChangeListener { _, _ -> refresh() }
        binding.buttonStart.setOnClickListener { pickTime(row.start) { row.start = it } }
        binding.buttonEnd.setOnClickListener { pickTime(row.end) { row.end = it } }
        binding.buttonClearTimes.setOnClickListener {
            row.start = DailySchedule.UNSET
            row.end = DailySchedule.UNSET
            refresh()
        }
        binding.buttonRemove.setOnClickListener {
            rows.remove(row)
            list.removeView(binding.root)
            refresh()
        }
        rows.add(row)
        list.addView(binding.root)
        refresh()
    }

    private fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        val clock = Calendar.getInstance()
        val nowMinute = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
        val initial = if (current >= 0) current else nowMinute
        TimePickerDialog(
            list.context,
            { _, hour, minute ->
                onPicked(hour * 60 + minute)
                refresh()
            },
            initial / 60,
            initial % 60,
            DateFormat.is24HourFormat(list.context),
        ).show()
    }

    private fun refresh() {
        val context = list.context
        rows.forEachIndexed { index, row ->
            val binding = row.binding
            binding.layoutText.hint = context.getString(R.string.label_command_numbered, index + 1)
            binding.buttonRemove.visibility = if (rows.size > 1) View.VISIBLE else View.GONE
            val allDay = binding.radioAllDay.isChecked
            binding.rowHours.visibility = if (allDay) View.GONE else View.VISIBLE
            binding.textSummary.text = summaryOf(allDay, row)
            binding.buttonStart.text = context.getString(R.string.schedule_start, timeLabel(row.start))
            binding.buttonEnd.text = context.getString(R.string.schedule_end, timeLabel(row.end))
            val anyTime = row.start >= 0 || row.end >= 0
            binding.buttonClearTimes.visibility = if (!allDay && anyTime) View.VISIBLE else View.GONE
        }
        addButton.visibility = if (rows.size < TaskConfig.MAX_COMMANDS) View.VISIBLE else View.GONE
        onChanged()
    }

    private fun summaryOf(allDay: Boolean, row: Row): String {
        val context = list.context
        return when {
            allDay -> context.getString(R.string.summary_all_day)
            !DailySchedule(row.start, row.end).isComplete -> context.getString(R.string.summary_pick_times)
            else -> context.getString(R.string.summary_daily, timeLabel(row.start), timeLabel(row.end))
        }
    }

    /** Twelve-hour phones show AM or PM here, so the picked period is always clear. */
    private fun timeLabel(minute: Int): String = timeText(list, minute)

    companion object {
        fun timeText(view: View, minute: Int): String {
            if (minute < 0) return view.context.getString(R.string.schedule_not_set)
            val time = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, minute / 60)
                set(Calendar.MINUTE, minute % 60)
            }
            return DateFormat.getTimeFormat(view.context).format(time.time)
        }
    }
}
