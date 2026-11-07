package com.autoclicker.chattask.service

import android.content.Context
import androidx.annotation.StringRes
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.ConfigError
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.data.EventLog

/** Maps results to the log event and the message the overlay shows. */
object Notices {
    /** WOLF gives the popup no sender field that could be read, so that stays unconfirmed. */
    fun logPopup(context: Context) {
        EventLog.add(context, EventType.POPUP_TRIGGER)
        EventLog.add(context, EventType.POPUP_SENDER_UNVERIFIED)
    }

    fun eventFor(result: RecoveryResult): EventType = when (result) {
        RecoveryResult.READY, RecoveryResult.ABORTED -> EventType.RELAUNCH_OK
        RecoveryResult.ROOM_NOT_FOUND -> EventType.ROOM_NOT_FOUND
        RecoveryResult.AMBIGUOUS_ROOM -> EventType.ROOM_AMBIGUOUS
        RecoveryResult.TAB_NOT_FOUND -> EventType.TAB_NOT_FOUND
        RecoveryResult.NOT_LAUNCHABLE, RecoveryResult.NO_CHAT -> EventType.RELAUNCH_FAILED
    }

    /** The overlay is the only feedback when Start is pressed, so it names what is missing. */
    @StringRes
    fun forErrors(errors: List<ConfigError>): Int = when {
        ConfigError.EMPTY_COMMAND in errors || ConfigError.REACTIVE_NO_COMMAND in errors ->
            R.string.notice_enter_command
        ConfigError.ROOM_REQUIRED in errors || ConfigError.REACTIVE_ROOM_REQUIRED in errors ->
            R.string.notice_enter_channel
        else -> R.string.notice_invalid_settings
    }

    @StringRes
    fun forRecovery(result: RecoveryResult): Int = when (result) {
        RecoveryResult.READY, RecoveryResult.ABORTED -> 0
        RecoveryResult.NOT_LAUNCHABLE -> R.string.notice_not_launchable
        RecoveryResult.NO_CHAT -> R.string.notice_no_chat
        RecoveryResult.TAB_NOT_FOUND -> R.string.notice_tab_not_found
        RecoveryResult.ROOM_NOT_FOUND -> R.string.notice_room_not_found
        RecoveryResult.AMBIGUOUS_ROOM -> R.string.notice_room_ambiguous
    }

    @StringRes
    fun forSend(result: SendResult): Int = when (result) {
        SendResult.SENT -> 0
        SendResult.SCREEN_LOCKED -> R.string.notice_screen_locked
        SendResult.TARGET_NOT_VISIBLE -> R.string.notice_target_hidden
        SendResult.NO_INPUT -> R.string.notice_no_input
        SendResult.TYPE_FAILED -> R.string.notice_type_failed
        SendResult.NOT_CONFIRMED -> R.string.notice_not_confirmed
    }
}
