package com.autoclicker.chattask.ui

import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.view.View
import androidx.annotation.StringRes
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.DailySchedule
import com.autoclicker.chattask.databinding.HoursPickerBinding
import java.util.Calendar

/** 24 hours or Daily, the From and To times, and the work and idle summary. */
class HoursEditor(
    private val binding: HoursPickerBinding,
    @StringRes private val pickTimesText: Int,
    private val onChanged: () -> Unit,
) {
    private val context get() = binding.root.context
    var start = DailySchedule.UNSET
        private set
    var end = DailySchedule.UNSET
        private set
    val allDay: Boolean get() = binding.radioAllDay.isChecked

    init {
        binding.groupHours.setOnCheckedChangeListener { _, _ -> changed() }
        binding.buttonStart.setOnClickListener { pickTime(start) { start = it } }
        binding.buttonEnd.setOnClickListener { pickTime(end) { end = it } }
        binding.buttonClearTimes.setOnClickListener {
            start = DailySchedule.UNSET
            end = DailySchedule.UNSET
            changed()
        }
    }

    fun show(allDay: Boolean, startMinute: Int, endMinute: Int) {
        start = startMinute
        end = endMinute
        binding.groupHours.check(if (allDay) R.id.radioAllDay else R.id.radioDaily)
        render()
    }

    fun render() {
        binding.rowHours.visibility = if (allDay) View.GONE else View.VISIBLE
        binding.textSummary.text = summary()
        binding.buttonStart.text = context.getString(R.string.schedule_start, timeLabel(start))
        binding.buttonEnd.text = context.getString(R.string.schedule_end, timeLabel(end))
        val anyTime = start >= 0 || end >= 0
        binding.buttonClearTimes.visibility = if (!allDay && anyTime) View.VISIBLE else View.GONE
    }

    private fun changed() {
        render()
        onChanged()
    }

    private fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        val clock = Calendar.getInstance()
        val nowMinute = clock.get(Calendar.HOUR_OF_DAY) * 60 + clock.get(Calendar.MINUTE)
        val initial = if (current >= 0) current else nowMinute
        TimePickerDialog(
            context,
            { _, hour, minute ->
                onPicked(hour * 60 + minute)
                changed()
            },
            initial / 60,
            initial % 60,
            DateFormat.is24HourFormat(context),
        ).show()
    }

    private fun summary(): String = when {
        allDay -> context.getString(R.string.summary_all_day)
        !DailySchedule(start, end).isComplete -> context.getString(pickTimesText)
        else -> context.getString(R.string.summary_daily, timeLabel(start), timeLabel(end))
    }

    /** Twelve-hour phones show AM or PM here, so the picked period is always clear. */
    private fun timeLabel(minute: Int): String = timeText(binding.root, minute)

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
