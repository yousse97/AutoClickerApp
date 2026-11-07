package com.autoclicker.chattask.ui

import android.os.Bundle
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.LogEvent
import com.autoclicker.chattask.data.EventLog
import com.autoclicker.chattask.databinding.ActivityLogBinding

/** Shows the last send, the last error, the failure streak and the latest 200 events. */
class LogActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.pageBar.root.setTitle(R.string.log_title)
        binding.pageBar.root.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    override fun onResume() {
        super.onResume()
        val events = EventLog.read(this)
        val none = getString(R.string.log_none)
        binding.textLastSend.text =
            getString(R.string.log_last_send, events.lastSend?.let(::timeOf) ?: none)
        binding.textLastError.text =
            getString(R.string.log_last_error, events.lastError?.let(::describe) ?: none)
        binding.textFailures.text = getString(R.string.log_failures, events.consecutiveFailures)
        binding.textEvents.text = events.all.asReversed().joinToString("\n", transform = ::describe)
            .ifEmpty { none }
    }

    private fun describe(event: LogEvent): String = "${timeOf(event)}  ${getString(labelOf(event.type))}"

    private fun timeOf(event: LogEvent): String =
        DateFormat.format("MM-dd HH:mm:ss", event.timeMs).toString()

    @StringRes
    private fun labelOf(type: EventType): Int = when (type) {
        EventType.SESSION_STARTED -> R.string.event_session_started
        EventType.SESSION_STOPPED -> R.string.event_session_stopped
        EventType.SESSION_RESTORED -> R.string.event_session_restored
        EventType.RESTORE_FAILED -> R.string.event_restore_failed
        EventType.SENT -> R.string.event_sent
        EventType.SEND_FAILED -> R.string.event_send_failed
        EventType.HELD_LOCKED -> R.string.event_held_locked
        EventType.HELD_OTHER_APP -> R.string.event_held_other_app
        EventType.HELD_NOT_CHANNEL -> R.string.event_held_not_channel
        EventType.HELD_WRONG_ROOM -> R.string.event_held_wrong_room
        EventType.RELAUNCH_OK -> R.string.event_relaunch_ok
        EventType.RELAUNCH_FAILED -> R.string.event_relaunch_failed
        EventType.ROOM_NOT_FOUND -> R.string.event_room_not_found
        EventType.ROOM_AMBIGUOUS -> R.string.event_room_ambiguous
        EventType.TAB_NOT_FOUND -> R.string.event_tab_not_found
        EventType.STOPPED_LOCKED -> R.string.event_stopped_locked
        EventType.STOPPED_RELAUNCH -> R.string.event_stopped_relaunch
        EventType.STOPPED_FAILURES -> R.string.event_stopped_failures
        EventType.REACTION_SENT -> R.string.event_reaction_sent
        EventType.REACTION_FAILED -> R.string.event_reaction_failed
        EventType.ANR_SEEN -> R.string.event_anr_seen
        EventType.ANR_CLOSED -> R.string.event_anr_closed
        EventType.ANR_CLOSE_FAILED -> R.string.event_anr_close_failed
        EventType.ANR_GAVE_UP -> R.string.event_anr_gave_up
        EventType.STOPPED_ANR -> R.string.event_stopped_anr
        EventType.POPUP_TRIGGER -> R.string.event_popup_trigger
        EventType.POPUP_SENDER_UNVERIFIED -> R.string.event_popup_unverified
        EventType.SCHEDULE_START -> R.string.event_schedule_start
        EventType.COMMAND_SKIPPED -> R.string.event_command_skipped
        EventType.SCHEDULE_STOP -> R.string.event_schedule_stop
        EventType.SCHEDULE_MISSED -> R.string.event_schedule_missed
        EventType.IDLE_SCREEN_OFF -> R.string.event_idle_screen_off
        EventType.CHECK_PASSED -> R.string.event_check_passed
        EventType.CHECK_FAILED -> R.string.event_check_failed
        EventType.APP_KILLED_MEMORY -> R.string.event_killed_memory
        EventType.APP_KILLED_SYSTEM -> R.string.event_killed_system
        EventType.APP_CRASHED -> R.string.event_crashed
        EventType.APP_STOPPED_BY_USER -> R.string.event_stopped_user
    }
}
