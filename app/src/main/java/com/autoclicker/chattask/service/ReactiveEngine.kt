package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.NotificationAge
import com.autoclicker.chattask.core.PopupTrigger
import com.autoclicker.chattask.core.ReactiveConfig
import com.autoclicker.chattask.core.ReactiveDetector
import com.autoclicker.chattask.core.ReplyGate
import com.autoclicker.chattask.core.TextNormalizer
import com.autoclicker.chattask.core.TriggerMatcher
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
 * Auto reply: watches the screen and the selected app's notifications for trigger phrases, then
 * goes to the saved channel and sends the reaction command once. It works only during active
 * hours, is configured only in Auto reply mode, and has no link to the timers.
 */
class ReactiveEngine(
    private val service: AccessibilityService,
    private val sender: CommandSender,
    private val navigator: RoomNavigator,
    private val onResult: (Boolean) -> Unit,
    private val navigate: suspend (String) -> Boolean,
) {
    private class Params(
        val target: String,
        val config: ReactiveConfig,
        val triggers: List<String>,
        val ownTexts: List<String>,
    )

    @Volatile private var params: Params? = null
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val detector = ReactiveDetector()
    private val popupTrigger = PopupTrigger()
    private val gate = ReplyGate()
    private var lastPlace: String? = null
    @Volatile private var pendingReply = false
    @Volatile private var startedAtMs = 0L

    /** True while a reply waits, which keeps the session out of idle. */
    val hasPending: Boolean get() = pendingReply

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
    fun configure(target: String, config: ReactiveConfig?, commands: List<String>) {
        detector.forgetBaseline()
        startedAtMs = System.currentTimeMillis()
        val triggers = config?.triggers?.distinctBy(TextNormalizer::normalize).orEmpty()
        params = config?.takeIf { triggers.isNotEmpty() && it.command.isNotBlank() && it.room.isNotBlank() }
            ?.let { Params(target, it, triggers, commands + it.command) }
    }

    fun disable() {
        params = null
        pendingReply = false
        gate.cancel()
    }

    fun onEvent() {
        if (params != null) signal.trySend(Unit)
    }

    fun onNotification(texts: List<CharSequence?>, postedMs: Long, messageMs: Long) {
        val active = params ?: return
        val wall = System.currentTimeMillis()
        if (!NotificationAge.isFresh(postedMs, messageMs, startedAtMs, gate.lastHandledWallMs, wall)) return
        if (TriggerMatcher.matches(texts, active.triggers, active.ownTexts)) requestReply(active)
    }

    /** At most one gets through. The rest are dropped here, so nothing piles up. */
    private fun requestReply(active: Params) {
        val inHours = active.config.isActive(LocalDateTime.now())
        if (!gate.tryBegin(SystemClock.elapsedRealtime(), System.currentTimeMillis(), inHours)) return
        pendingReply = true
        EventLog.add(service, EventType.TRIGGER_HANDLED)
        signal.trySend(Unit)
    }

    private suspend fun scan(active: Params) {
        // Anything that arrived during idle hours is old news when the active hours begin.
        if (!active.config.isActive(LocalDateTime.now())) {
            if (pendingReply) gate.cancel()
            pendingReply = false
            return detector.forgetBaseline()
        }
        if (!pendingReply) watchScreen(active)
        if (pendingReply) answer(active)
    }

    private fun watchScreen(active: Params) {
        val screen = navigator.readScreen()
        // Each screen has its own history, so only a rise on the same screen is news.
        val place = "${screen.title}|${screen.hasInput}"
        if (place != lastPlace) detector.forgetBaseline()
        lastPlace = place
        val matches = findMatches(active)
        val inPopup = matches.filter(::isPopupNode)
        val popup = popupTrigger.observe(inPopup.isNotEmpty(), SystemClock.elapsedRealtime())
        val message = detector.observe((matches - inPopup.toSet()).size, SystemClock.elapsedRealtime())
        if (popup || message) requestReply(active)
    }

    private suspend fun answer(active: Params) {
        pendingReply = false
        val sent = replyInChannel(active)
        gate.finish(SystemClock.elapsedRealtime(), sent == true)
        detector.forgetBaseline()
        if (sent != null) onResult(sent)
    }

    /** Null means Stop or the end of the hours overtook it, so nothing was tried and nothing failed. */
    private suspend fun replyInChannel(active: Params): Boolean? {
        val room = active.config.room
        if (!isInRoom(room) && !navigate(room)) return if (stillWanted(active)) false else null
        repeat(MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS)
            if (!stillWanted(active)) return null
            if (!isInRoom(room)) return false
            if (sender.send(active.target, active.config.command.trim()) == SendResult.SENT) return true
        }
        return false
    }

    private fun stillWanted(active: Params) = params === active && active.config.isActive(LocalDateTime.now())

    /** The title must equal the saved channel, and only a channel has the members button. */
    private fun isInRoom(room: String): Boolean {
        val screen = navigator.readScreen()
        return screen.isChannel && WolfUi.isRoom(screen.title, room)
    }

    private fun findMatches(active: Params): List<AccessibilityNodeInfo> {
        val seen = HashMap<String, AccessibilityNodeInfo>()
        for (root in NodeTools.targetRoots(service, active.target)) {
            for (trigger in active.triggers) {
                root.findAccessibilityNodeInfosByText(trigger)
                    ?.filter { isMessage(it) && !isOwn(it, active) }
                    ?.forEach { seen["${NodeTools.boundsOf(it)}|${it.text}|${it.contentDescription}"] = it }
            }
        }
        return seen.values.toList()
    }

    private fun isOwn(node: AccessibilityNodeInfo, active: Params): Boolean =
        listOfNotNull(node.text, node.contentDescription).any { TriggerMatcher.isOwn(it, active.ownTexts) }

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

    private fun isMessage(node: AccessibilityNodeInfo): Boolean = node.isVisibleToUser && !node.isEditable

    private companion object {
        const val CHAT_VIEW = "chat"
        const val MAX_ANCESTORS = 30
        const val MIN_SCAN_GAP_MS = 800L
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 1_500L
    }
}
