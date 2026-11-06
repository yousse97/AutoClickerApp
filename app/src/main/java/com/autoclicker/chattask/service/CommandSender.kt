package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.autoclicker.chattask.core.KeywordSet
import com.autoclicker.chattask.core.ViewIdMatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

enum class SendResult { SENT, SCREEN_LOCKED, TARGET_NOT_VISIBLE, NO_INPUT, TYPE_FAILED, NOT_CONFIRMED }

/**
 * Types a command into the chat box and presses send.
 *
 * A send only counts once the input box has cleared. That is the one signal that works across
 * chat apps, and it is what lets the sender move on to the next strategy when one has no effect.
 */
class CommandSender(private val service: AccessibilityService) {
    private val mutex = Mutex()
    private val sendLabels = KeywordSet(listOf("إرسال", "أرسل", "send"))

    suspend fun send(target: String, command: String): SendResult = mutex.withLock {
        if (!isDeviceReady()) return SendResult.SCREEN_LOCKED
        if (NodeTools.targetRoots(service, target).isEmpty()) return SendResult.TARGET_NOT_VISIBLE
        val input = findInput(target) ?: return SendResult.NO_INPUT
        if (!typeCommand(target, input, command)) return SendResult.TYPE_FAILED

        for (strategy in sendStrategies(target)) {
            humanPause()
            if (strategy() && awaitCleared(target)) return SendResult.SENT
        }
        findInput(target)?.let(::clearField)
        SendResult.NOT_CONFIRMED
    }

    /** The bottom-most editable field, which is where chat apps put their message box. */
    fun findInput(target: String): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE
        for (root in NodeTools.targetRoots(service, target)) {
            NodeTools.walk(root) { node ->
                val usable = node.isEditable && node.isVisibleToUser && node.isEnabled && !node.isPassword
                if (usable) {
                    val bottom = NodeTools.boundsOf(node).bottom
                    if (bottom > bestBottom) {
                        best = node
                        bestBottom = bottom
                    }
                }
            }
        }
        return best
    }

    private fun isDeviceReady(): Boolean {
        val power = service.getSystemService(PowerManager::class.java)
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private suspend fun typeCommand(
        target: String,
        input: AccessibilityNodeInfo,
        command: String,
    ): Boolean {
        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        input.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        humanPause()
        // Focusing can rebuild the layout, so the earlier node may already be stale.
        val field = findInput(target) ?: input
        setText(field, command)
        if (awaitText(target, command)) return true
        pasteText(findInput(target) ?: field, command)
        return awaitText(target, command)
    }

    private fun setText(field: AccessibilityNodeInfo, text: String?): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clearField(field: AccessibilityNodeInfo) {
        setText(field, null)
    }

    /** Fallback for apps that ignore ACTION_SET_TEXT. It does replace the clipboard. */
    private fun pasteText(field: AccessibilityNodeInfo, text: String) {
        val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("command", text))
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, fieldText(field).length)
        }
        field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }

    private fun fieldText(field: AccessibilityNodeInfo): String {
        if (field.isShowingHintText) return ""
        val text = field.text?.toString().orEmpty()
        return if (text == field.hintText?.toString()) "" else text
    }

    private suspend fun awaitText(target: String, command: String): Boolean = poll(TEXT_TIMEOUT_MS) {
        findInput(target)?.let { fieldText(it).trim() == command.trim() } == true
    }

    private suspend fun awaitCleared(target: String): Boolean = poll(CLEAR_TIMEOUT_MS) {
        findInput(target)?.let { fieldText(it).isBlank() } == true
    }

    private suspend fun poll(timeoutMs: Long, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (waited <= timeoutMs) {
            if (condition()) return true
            delay(POLL_MS)
            waited += POLL_MS
        }
        return false
    }

    /** Ordered from most to least certain. The row guess runs last as it may hit a wrong icon. */
    private fun sendStrategies(target: String): List<suspend () -> Boolean> = listOf(
        { clickNamedSendButton(target) },
        { pressImeEnter(target) },
        { clickRowButton(target) },
    )

    private suspend fun clickNamedSendButton(target: String): Boolean {
        // Many apps only swap the microphone for a send button after text appears.
        var button: AccessibilityNodeInfo? = null
        poll(BUTTON_TIMEOUT_MS) {
            button = findNamedSendButton(target)
            button != null
        }
        return button?.let { NodeTools.click(service, it) } == true
    }

    private fun pressImeEnter(target: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val input = findInput(target) ?: return false
        val enter = AccessibilityAction.ACTION_IME_ENTER
        return input.actionList.any { it.id == enter.id } && input.performAction(enter.id)
    }

    private fun clickRowButton(target: String): Boolean =
        findRowButton(target)?.let { NodeTools.click(service, it) } == true

    private fun findNamedSendButton(target: String): AccessibilityNodeInfo? {
        val inputBounds = findInput(target)?.let(NodeTools::boundsOf) ?: return null
        val tolerance = max(inputBounds.height(), MIN_ROW_TOLERANCE_PX)
        val rowTop = inputBounds.top - tolerance
        val rowBottom = inputBounds.bottom + tolerance
        var best: AccessibilityNodeInfo? = null
        var bestDistance = Int.MAX_VALUE
        forEachActionable(target) { node, bounds ->
            val named = sendLabels.matches(node.contentDescription) || sendLabels.matches(node.text) ||
                ViewIdMatcher.isSend(node.viewIdResourceName)
            if (named && bounds.centerY() in rowTop..rowBottom) {
                val distance = abs(bounds.centerY() - inputBounds.centerY())
                if (distance < bestDistance) {
                    best = node
                    bestDistance = distance
                }
            }
        }
        return best
    }

    /**
     * Last resort: the outermost unlabelled button in the input's row, on the side away from it.
     * Comparing against the input keeps this correct in both RTL and LTR layouts.
     */
    private fun findRowButton(target: String): AccessibilityNodeInfo? {
        val inputBounds = findInput(target)?.let(NodeTools::boundsOf) ?: return null
        val screenWidth = service.resources.displayMetrics.widthPixels
        val sendIsOnRight = inputBounds.centerX() < screenWidth / 2
        var best: AccessibilityNodeInfo? = null
        var bestEdge = if (sendIsOnRight) Int.MIN_VALUE else Int.MAX_VALUE
        forEachActionable(target) { node, bounds ->
            val inRow = bounds.centerY() in inputBounds.top..inputBounds.bottom
            val plausibleSize = bounds.width() in MIN_BUTTON_PX..(screenWidth * 3 / 10)
            if (inRow && plausibleSize && node.isClickable && !isOtherComposerIcon(node)) {
                val better = if (sendIsOnRight) bounds.right > bestEdge else bounds.left < bestEdge
                if (better) {
                    best = node
                    bestEdge = if (sendIsOnRight) bounds.right else bounds.left
                }
            }
        }
        return best
    }

    private fun isOtherComposerIcon(node: AccessibilityNodeInfo): Boolean {
        val description = "${node.viewIdResourceName} ${node.contentDescription}".lowercase()
        return OTHER_COMPOSER_ICONS.any { it in description }
    }

    private inline fun forEachActionable(
        target: String,
        visit: (AccessibilityNodeInfo, Rect) -> Unit,
    ) {
        for (root in NodeTools.targetRoots(service, target)) {
            NodeTools.walk(root) { node ->
                if (node.isVisibleToUser && node.isEnabled && !node.isEditable) {
                    visit(node, NodeTools.boundsOf(node))
                }
            }
        }
    }

    private suspend fun humanPause() = delay(Random.nextLong(HUMAN_PAUSE_MIN_MS, HUMAN_PAUSE_MAX_MS))

    private companion object {
        const val POLL_MS = 100L
        const val TEXT_TIMEOUT_MS = 800L
        const val BUTTON_TIMEOUT_MS = 1_500L
        const val CLEAR_TIMEOUT_MS = 1_500L
        const val HUMAN_PAUSE_MIN_MS = 120L
        const val HUMAN_PAUSE_MAX_MS = 320L
        const val MIN_ROW_TOLERANCE_PX = 120
        const val MIN_BUTTON_PX = 24
        val OTHER_COMPOSER_ICONS = listOf(
            "mic", "voice", "record", "audio", "attach", "emoji", "sticker", "gif", "camera", "plus",
            "صوت", "تسجيل", "مرفق", "كاميرا", "ملصق", "إيموجي", "رموز",
        )
    }
}
