package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.chattask.core.PopupTrigger
import com.autoclicker.chattask.core.ReactiveConfig
import com.autoclicker.chattask.core.ReactiveDetector
import com.autoclicker.chattask.core.TextNormalizer
import com.autoclicker.chattask.core.WolfUi
import com.autoclicker.chattask.data.EventLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Auto reply: watches the target app for trigger phrases and sends the reaction command, but only
 * during its active hours. It is configured only in Auto reply mode, and has no link to the timers.
 */
class ReactiveEngine(
    private val service: AccessibilityService,
    private val sender: CommandSender,
    private val navigator: RoomNavigator,
    private val onResult: (Boolean) -> Unit,
    private val needRoom: (String) -> Unit,
) {
    private class Params(val target: String, val config: ReactiveConfig, val triggers: List<String>)

    @Volatile private var params: Params? = null
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val detector = ReactiveDetector()
    private val popupTrigger = PopupTrigger()
    @Volatile private var pendingPopup = false

    /** True while a popup response waits, which keeps the session out of idle. */
    val hasPending: Boolean
        get() = pendingPopup && params?.config?.isActive(LocalDateTime.now()) == true

    fun launchIn(scope: CoroutineScope, dispatcher: CoroutineDispatcher): Job =
        scope.launch(dispatcher) {
            for (ignored in signal) {
                try {
                    params?.let { scan(it) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ignoredFailure: RuntimeException) {
                    // A window can vanish in the middle of a query. The next event scans again.
                }
                delay(MIN_SCAN_GAP_MS)
            }
        }

    /** Pass null outside Auto reply mode, which leaves nothing able to reply. */
    fun configure(target: String, config: ReactiveConfig?) {
        detector.forgetBaseline()
        val triggers = config?.triggers?.distinctBy(TextNormalizer::normalize).orEmpty()
        params = config?.takeIf { triggers.isNotEmpty() && it.command.isNotBlank() }
            ?.let { Params(target, it, triggers) }
    }

    fun disable() {
        params = null
    }

    fun onEvent() {
        if (params != null) signal.trySend(Unit)
    }

    private suspend fun scan(active: Params) {
        // Anything that arrived during idle hours is old news when the active hours begin.
        if (!active.config.isActive(LocalDateTime.now())) {
            pendingPopup = false
            return detector.forgetBaseline()
        }
        val matches = findMatches(active)
        val inPopup = matches.filter(::isPopupNode)
        if (popupTrigger.observe(inPopup.isNotEmpty(), SystemClock.elapsedRealtime())) {
            pendingPopup = true
            Notices.logPopup(service)
            traceStructure(inPopup.first())
        }
        if (pendingPopup) answerPopup(active)
        if (sender.findInput(active.target) == null) return detector.forgetBaseline()
        val count = (matches - inPopup.toSet()).size
        if (!detector.observe(count, SystemClock.elapsedRealtime())) return
        val config = active.config
        val screen = navigator.readScreen()
        if (!screen.isChannel) return
        if (config.restrictToRoom && !WolfUi.isRoom(screen.title, config.room)) return
        onResult(react(active))
    }

    /** Waits for the channel, then replies there. The private chat itself is never touched. */
    private suspend fun answerPopup(active: Params) {
        val config = active.config
        val screen = navigator.readScreen()
        val inRoom = !config.restrictToRoom || WolfUi.isRoom(screen.title, config.room)
        if (!screen.isChannel || !inRoom) {
            if (config.restrictToRoom) needRoom(config.room)
            return
        }
        pendingPopup = false
        onResult(react(active))
    }

    private fun findMatches(active: Params): List<AccessibilityNodeInfo> {
        val seen = HashMap<String, AccessibilityNodeInfo>()
        for (root in NodeTools.targetRoots(service, active.target)) {
            for (trigger in active.triggers) {
                root.findAccessibilityNodeInfosByText(trigger)
                    ?.filter(::isMessage)
                    ?.forEach { seen["${NodeTools.boundsOf(it)}|${it.text}|${it.contentDescription}"] = it }
            }
        }
        return seen.values.toList()
    }

    /** Messages live in the chat view and previews in lists, so anything else is a popup. */
    private fun isPopupNode(node: AccessibilityNodeInfo): Boolean {
        if (NodeTools.isInsideList(node)) return false
        var current = node.parent
        var depth = 0
        while (current != null && depth++ < MAX_ANCESTORS) {
            if (WolfUi.entryName(current.viewIdResourceName) == CHAT_VIEW) return false
            current = current.parent
        }
        return true
    }

    /** Logs only ids and class names, to confirm the popup's real structure. Never the text. */
    private fun traceStructure(node: AccessibilityNodeInfo) {
        val chain = ArrayList<String>()
        var current: AccessibilityNodeInfo? = node
        while (current != null && chain.size < MAX_ANCESTORS) {
            chain += "${current.className?.toString()?.substringAfterLast('.')}:" +
                WolfUi.entryName(current.viewIdResourceName).orEmpty()
            current = current.parent
        }
        Log.i(EventLog.TAG, "popup structure ${chain.joinToString(" < ")}")
    }

    private fun isMessage(node: AccessibilityNodeInfo): Boolean = node.isVisibleToUser && !node.isEditable

    private suspend fun react(active: Params): Boolean {
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS)
            if (sender.send(active.target, active.config.command.trim()) == SendResult.SENT) return true
        }
        return false
    }

    private companion object {
        const val CHAT_VIEW = "chat"
        const val MAX_ANCESTORS = 30
        const val MIN_SCAN_GAP_MS = 800L
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 1_500L
    }
}
