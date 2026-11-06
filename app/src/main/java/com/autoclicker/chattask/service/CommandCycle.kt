package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import com.autoclicker.chattask.R
import com.autoclicker.chattask.core.CommandSequence
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.Foreground
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.core.WolfUi
import com.autoclicker.chattask.data.EventLog
import kotlinx.coroutines.delay

/** What one cycle did. [heldBy] is set when a send had to wait. */
class CycleOutcome(val completed: Boolean, val sentAny: Boolean, val heldBy: EventType?, val sendNotice: Int)

/** Sends one cycle's commands, checking before every single send that this is the right place. */
class CommandCycle(
    private val service: AccessibilityService,
    private val sender: CommandSender,
    private val navigator: RoomNavigator,
    private val popups: PopupEngine,
    private val isWorking: () -> Boolean,
    private val isNavigating: () -> Boolean,
) {
    private var lastHold: EventType? = null

    fun clearHold() {
        lastHold = null
    }

    /** Logged once per reason, because a held send is rechecked every second. */
    fun recordHold(reason: EventType) {
        if (reason != lastHold) {
            lastHold = reason
            EventLog.add(service, reason)
        }
        val notice = when (reason) {
            EventType.HELD_LOCKED -> R.string.notice_screen_locked
            EventType.HELD_WRONG_ROOM -> R.string.notice_wrong_room
            EventType.HELD_NOT_CHANNEL -> R.string.notice_not_channel
            else -> R.string.notice_target_hidden
        }
        TaskBus.update { it.copy(notice = notice) }
    }

    suspend fun run(config: TaskConfig): CycleOutcome {
        var sentAny = false
        var heldBy: EventType? = null
        var sendNotice = 0
        val completed = CommandSequence.run(config.commands, isWorking, { delay(it) }) { command ->
            heldBy = if (isNavigating()) EventType.HELD_OTHER_APP else holdReason(config)
            if (heldBy != null) return@run false
            val result = sendWithRetries(command)
            sendNotice = Notices.forSend(result)
            heldBy = holdFor(result)
            if (result == SendResult.SENT) {
                sentAny = true
                EventLog.add(service, EventType.SENT)
            }
            result == SendResult.SENT
        }
        return CycleOutcome(completed, sentAny, heldBy, sendNotice)
    }

    /** Why a send may not go out right now, or null if this is a confirmed valid channel. */
    private fun holdReason(config: TaskConfig): EventType? {
        when (NodeTools.foreground(service)) {
            Foreground.LOCKED -> return EventType.HELD_LOCKED
            Foreground.HOME, Foreground.OTHER_APP -> return EventType.HELD_OTHER_APP
            Foreground.WOLF -> Unit
        }
        val screen = navigator.readScreen()
        return when {
            !screen.isChannel -> EventType.HELD_NOT_CHANNEL
            config.restrictToRoom && !WolfUi.isRoom(screen.title, config.targetRoom) ->
                EventType.HELD_WRONG_ROOM
            else -> null
        }
    }

    private fun holdFor(result: SendResult): EventType? = when (result) {
        SendResult.SCREEN_LOCKED -> EventType.HELD_LOCKED
        SendResult.TARGET_NOT_VISIBLE -> EventType.HELD_OTHER_APP
        else -> null
    }

    private suspend fun sendWithRetries(command: String): SendResult {
        var last = SendResult.NOT_CONFIRMED
        for (attempt in 1..MAX_SEND_ATTEMPTS) {
            popups.scan(full = true)
            last = sender.send(WolfUi.PACKAGE, command)
            if (last == SendResult.SENT || holdFor(last) != null) break
            if (attempt < MAX_SEND_ATTEMPTS) delay(RETRY_DELAY_MS)
            // The user may have paused to use the chat, so do not type over them.
            if (!isWorking()) break
        }
        return last
    }

    private companion object {
        const val MAX_SEND_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 2_500L
    }
}
