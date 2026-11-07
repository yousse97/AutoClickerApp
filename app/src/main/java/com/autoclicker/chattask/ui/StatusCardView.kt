package com.autoclicker.chattask.ui

import android.content.Context
import android.text.format.DateFormat
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.BarState
import com.autoclicker.chattask.core.CheckItem
import com.autoclicker.chattask.core.CheckResult
import com.autoclicker.chattask.core.CheckStatus
import com.autoclicker.chattask.core.CommandEntry
import com.autoclicker.chattask.core.CommandState
import com.autoclicker.chattask.core.formatDuration
import com.autoclicker.chattask.data.EventLog
import com.autoclicker.chattask.databinding.ActivityMainBinding
import com.autoclicker.chattask.service.CommandView
import com.autoclicker.chattask.service.HomeState
import com.autoclicker.chattask.service.ScheduleKeeper
import com.autoclicker.chattask.service.TaskStatus
import com.autoclicker.chattask.service.homeStateOf
import java.time.LocalDateTime
import java.util.Date

/** Draws the home status card. Every state has its own words, not only a color. */
class StatusCardView(private val context: Context, private val binding: ActivityMainBinding) {
    fun render(status: TaskStatus, commands: List<CommandEntry>) {
        val events = EventLog.read(context)
        val state = homeStateOf(status, events.lastStop != null)
        binding.cardStatus.setCardBackgroundColor(context.getColor(colorOf(state)))
        val text = context.getColor(if (state == HomeState.PAUSED) R.color.status_on_paused else R.color.bar_text)
        binding.textStatusTitle.setTextColor(text)
        binding.textStatusReason.setTextColor(text)
        binding.textStatusTitle.setText(titleOf(state))
        binding.textStatusReason.text = reasonOf(state, status)
        val none = context.getString(R.string.log_none)
        val lines = arrayListOf(
            context.getString(R.string.log_last_send, events.lastSend?.let { time(it.timeMs, true) } ?: none),
            context.getString(R.string.status_next_command, nextCommand(status, none)),
            context.getString(R.string.log_failures, events.consecutiveFailures),
        )
        nextChange(commands)?.let(lines::add)
        commands.forEachIndexed { index, command ->
            lines += commandLine(index, command, status.commands.getOrNull(index))
        }
        binding.textStatusFacts.text = lines.joinToString("\n")
    }

    private fun nextCommand(status: TaskStatus, none: String): String {
        if (status.nextInMs <= 0) return none
        val name = status.nextIndex?.let(status.commandNames::getOrNull)
        return listOfNotNull(name, formatDuration(status.nextInMs)).joinToString(" · ")
    }

    /** The next daily start or stop, taken from the real clock. */
    private fun nextChange(commands: List<CommandEntry>): String? {
        val now = LocalDateTime.now()
        val windows = commands.filter { !it.allDay && it.window.isComplete }.map { it.window }
        val start = windows.minOfOrNull { it.nextStart(now) }
        val end = windows.minOfOrNull { it.nextEnd(now) }
        if (start == null || end == null) return null
        val stopsFirst = end < start
        val text = if (stopsFirst) R.string.status_schedule_stop else R.string.status_schedule_start
        return context.getString(text, time(ScheduleKeeper.toEpochMs(minOf(start, end)), false))
    }

    /** Interval, active hours, state and next send for one command. */
    private fun commandLine(index: Int, command: CommandEntry, view: CommandView?): String {
        val hours = if (command.allDay) {
            context.getString(R.string.status_all_day)
        } else {
            "${CommandListEditor.timeText(binding.root, command.startMinute)} - " +
                CommandListEditor.timeText(binding.root, command.endMinute)
        }
        val state = when (view?.state) {
            CommandState.RUNNING -> R.string.status_running
            CommandState.PENDING -> R.string.command_pending
            CommandState.IDLE -> R.string.status_idle
            null -> R.string.status_stopped
        }
        val next = view?.nextInMs?.let(::formatDuration) ?: "-"
        return context.getString(
            R.string.status_command_line,
            index + 1,
            formatDuration(command.intervalSec * 1000L),
            hours,
            context.getString(state),
            next,
        )
    }

    private fun time(ms: Long, withDate: Boolean): String {
        val clock = DateFormat.getTimeFormat(context).format(Date(ms))
        return if (withDate) "${DateFormat.getMediumDateFormat(context).format(Date(ms))} $clock" else clock
    }

    private fun colorOf(state: HomeState) = when (state) {
        HomeState.RUNNING -> R.color.status_running
        HomeState.ATTENTION -> R.color.status_attention
        HomeState.PAUSED -> R.color.status_paused
        HomeState.IDLE -> R.color.status_idle
        HomeState.ERROR -> R.color.status_error
        HomeState.STOPPED -> R.color.status_stopped
    }

    private fun titleOf(state: HomeState) = when (state) {
        HomeState.RUNNING -> R.string.status_running
        HomeState.ATTENTION -> R.string.status_attention
        HomeState.PAUSED -> R.string.status_paused
        HomeState.IDLE -> R.string.status_idle
        HomeState.ERROR -> R.string.status_error
        HomeState.STOPPED -> R.string.status_stopped
    }

    private fun reasonOf(state: HomeState, status: TaskStatus): String = when (state) {
        HomeState.RUNNING -> context.getString(R.string.status_running_reason)
        HomeState.ATTENTION -> context.getString(
            if (status.barState == BarState.COMMAND_READY) R.string.overlay_command_ready else R.string.status_send_soon,
        )
        HomeState.PAUSED -> context.getString(
            if (status.heldByLock) R.string.notice_screen_locked else R.string.overlay_paused,
        )
        HomeState.IDLE -> context.getString(R.string.status_idle_until, time(status.idleUntilMs, false))
        HomeState.ERROR -> if (status.notice != 0) context.getString(status.notice) else context.getString(R.string.status_error_reason)
        HomeState.STOPPED -> context.getString(R.string.status_stopped_reason)
    }
}

/** One line of the check report. */
object CheckText {
    fun lineFor(context: Context, result: CheckResult): String {
        val mark = when (result.status) {
            CheckStatus.PASS -> R.string.check_pass
            CheckStatus.FAIL -> R.string.check_fail
            CheckStatus.INFO -> R.string.check_info
        }
        val fix = if (result.status == CheckStatus.PASS) "" else "\n${context.getString(fixOf(result.item))}"
        return "${context.getString(mark)}: ${context.getString(nameOf(result.item))}$fix"
    }

    private fun nameOf(item: CheckItem) = when (item) {
        CheckItem.ACCESSIBILITY -> R.string.permission_accessibility
        CheckItem.OVERLAY -> R.string.permission_overlay
        CheckItem.WOLF_OPEN -> R.string.check_wolf_open
        CheckItem.CHANNEL -> R.string.check_channel
        CheckItem.ROOM -> R.string.check_room
        CheckItem.MESSAGE_BOX -> R.string.check_message_box
        CheckItem.SEND_BUTTON -> R.string.check_send_button
    }

    private fun fixOf(item: CheckItem) = when (item) {
        CheckItem.ACCESSIBILITY -> R.string.check_fix_accessibility
        CheckItem.OVERLAY -> R.string.check_fix_overlay
        CheckItem.WOLF_OPEN -> R.string.check_fix_wolf
        CheckItem.CHANNEL -> R.string.check_fix_channel
        CheckItem.ROOM -> R.string.check_fix_room
        CheckItem.MESSAGE_BOX -> R.string.check_fix_channel
        CheckItem.SEND_BUTTON -> R.string.check_note_send
    }
}
