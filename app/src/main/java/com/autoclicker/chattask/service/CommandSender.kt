package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.autoclicker.chattask.core.InputMatcher
import com.autoclicker.chattask.core.TextNormalizer
import com.autoclicker.chattask.core.WolfUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

enum class SendResult {
    SENT,
    SCREEN_LOCKED,
    TARGET_NOT_VISIBLE,
    NO_INPUT,
    TYPE_FAILED,
    NOT_CONFIRMED,
}

/**
 * Types a command into the chat box and presses send.
 *
 * A send only counts once the input box has cleared. That is the one signal that works across
 * chat apps, and it is what lets the sender move on to the next strategy when one has no effect.
 */
class CommandSender(private val service: AccessibilityService) {
    private val mutex = Mutex()
    private val sendLabels = listOf("إرسال", "أرسل", "send").map(TextNormalizer::normalize)

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

    /** The bottom-most message box, which is where chat apps put it. */
    fun findInput(target: String): AccessibilityNodeInfo? {
        var byId: AccessibilityNodeInfo? = null
        var byBounds: AccessibilityNodeInfo? = null
        var byHint: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE
        for (root in NodeTools.targetRoots(service, target)) {
            NodeTools.walk(root) { node ->
                if (isUsableInput(node)) {
                    val isField = node.isEditable || node.className?.contains("EditText") == true
                    val bottom = NodeTools.boundsOf(node).bottom
                    when {
                        WolfUi.entryName(node.viewIdResourceName) == WolfUi.INPUT -> byId = node
                        isField && bottom > bestBottom -> {
                            byBounds = node
                            bestBottom = bottom
                        }
                        !isField -> byHint = node
                    }
                }
            }
        }
        return byId ?: byBounds ?: byHint
    }

    private fun isUsableInput(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled || node.isPassword) return false
        val matches = InputMatcher.isMessageBox(node.className, node.isEditable, node.hintText, node.text)
        // Only a real field is trusted inside a list, where a short message may repeat the hint.
        return matches && (node.isEditable || !NodeTools.isInsideList(node))
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
        if (!input.isFocused) {
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            NodeTools.click(service, input)
            poll(FOCUS_TIMEOUT_MS) { findInput(target)?.isEditable == true }
        }
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
        return if (text == field.hintText?.toString() || InputMatcher.isHint(text)) "" else text
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

    /**
     * Ordered from most to least certain. There is deliberately no guessing by position, because
     * the same row in WOLF holds the send-a-gift button and the voice recorder.
     */
    private fun sendStrategies(target: String): List<suspend () -> Boolean> = listOf(
        { clickNamedSendButton(target) },
        { pressImeEnter(target) },
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

    private fun findNamedSendButton(target: String): AccessibilityNodeInfo? {
        var byId: AccessibilityNodeInfo? = null
        var byLabel: AccessibilityNodeInfo? = null
        for (root in NodeTools.targetRoots(service, target)) {
            NodeTools.walk(root) { node ->
                if (node.isVisibleToUser && node.isEnabled && !node.isEditable) {
                    if (WolfUi.isSendButton(node.viewIdResourceName)) byId = node
                    if (isSendLabel(node.contentDescription) || isSendLabel(node.text)) byLabel = node
                }
            }
        }
        return byId ?: byLabel
    }

    /** The whole label must match, so "إرسال هدية" (send a gift) never counts as send. */
    private fun isSendLabel(label: CharSequence?): Boolean =
        !label.isNullOrBlank() && TextNormalizer.normalize(label) in sendLabels

    private suspend fun humanPause() = delay(Random.nextLong(HUMAN_PAUSE_MIN_MS, HUMAN_PAUSE_MAX_MS))

    private companion object {
        const val POLL_MS = 100L
        const val TEXT_TIMEOUT_MS = 800L
        const val FOCUS_TIMEOUT_MS = 800L
        const val BUTTON_TIMEOUT_MS = 1_500L
        const val CLEAR_TIMEOUT_MS = 1_500L
        const val HUMAN_PAUSE_MIN_MS = 120L
        const val HUMAN_PAUSE_MAX_MS = 320L
    }
}
