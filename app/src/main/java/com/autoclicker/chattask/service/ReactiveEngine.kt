package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.chattask.core.ReactiveConfig
import com.autoclicker.chattask.core.ReactiveDetector
import com.autoclicker.chattask.core.TextNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * YOUSE 2: watches the target app for trigger phrases and sends the reaction command.
 *
 * It has its own loop and no link to the Task 1 timer. Sends share [CommandSender]'s lock, so a
 * reaction waits for a message being typed and never mixes with it.
 */
class ReactiveEngine(
    private val service: AccessibilityService,
    private val sender: CommandSender,
    private val navigator: RoomNavigator,
    private val onResult: (Boolean) -> Unit,
) {
    private class Params(val target: String, val config: ReactiveConfig, val triggers: List<String>)

    @Volatile private var params: Params? = null
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val detector = ReactiveDetector()

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

    fun configure(target: String, config: ReactiveConfig) {
        detector.forgetBaseline()
        val triggers = config.triggers.distinctBy(TextNormalizer::normalize)
        val usable = config.enabled && triggers.isNotEmpty() && config.command.isNotBlank()
        params = if (usable) Params(target, config, triggers) else null
    }

    fun disable() {
        params = null
    }

    fun onEvent() {
        if (params != null) signal.trySend(Unit)
    }

    private suspend fun scan(active: Params) {
        if (sender.findInput(active.target) == null) return detector.forgetBaseline()
        val count = countMatches(active)
        if (!detector.observe(count, SystemClock.elapsedRealtime())) return
        val config = active.config
        if (config.restrictToRoom && !navigator.isInRoom(active.target, config.room)) return
        onResult(react(active))
    }

    private fun countMatches(active: Params): Int {
        val seen = HashSet<String>()
        for (root in NodeTools.targetRoots(service, active.target)) {
            for (trigger in active.triggers) {
                root.findAccessibilityNodeInfosByText(trigger)
                    ?.filter(::isMessage)
                    ?.forEach { seen += "${NodeTools.boundsOf(it)}|${it.text}|${it.contentDescription}" }
            }
        }
        return seen.size
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
        const val MIN_SCAN_GAP_MS = 400L
        const val MAX_ATTEMPTS = 2
        const val RETRY_DELAY_MS = 1_500L
    }
}
