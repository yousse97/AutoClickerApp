package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.chattask.core.AnrAction
import com.autoclicker.chattask.core.AnrGuard
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.WolfUi
import com.autoclicker.chattask.data.EventLog

/**
 * Reads the system's own "not responding" dialog, and only when it names WOLF. Both of its buttons
 * must exist, which rules out the crash dialog, and every other window is ignored.
 */
class AnrHandler(
    private val service: AccessibilityService,
    private val reopenWolf: () -> Unit,
    private val stop: () -> Unit,
) {
    private val guard = AnrGuard()

    fun onRecovered() = guard.onRecovered()

    /** Returns true while the dialog is up, so nothing else acts behind it. */
    fun tick(now: Long): Boolean {
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        val close = root?.takeIf { it.packageName?.toString() == SYSTEM_PACKAGE }?.let(::findCloseButton)
        when (guard.observe(close != null, now)) {
            AnrAction.NONE -> Unit
            AnrAction.SEEN -> EventLog.add(service, EventType.ANR_SEEN)
            AnrAction.GIVE_UP -> {
                EventLog.add(service, EventType.ANR_GAVE_UP)
                stop()
            }
            AnrAction.CLOSE -> {
                val closed = close?.let { NodeTools.click(service, it) } == true
                EventLog.add(service, if (closed) EventType.ANR_CLOSED else EventType.ANR_CLOSE_FAILED)
                if (closed) reopenWolf()
            }
        }
        return close != null
    }

    private fun findCloseButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val close = root.findAccessibilityNodeInfosByViewId(CLOSE_ID)?.firstOrNull()
        val wait = root.findAccessibilityNodeInfosByViewId(WAIT_ID)?.firstOrNull()
        return if (close != null && wait != null && namesWolf(root)) close else findByText(root)
    }

    private fun namesWolf(root: AccessibilityNodeInfo): Boolean {
        val label = wolfLabel()
        var found = false
        NodeTools.walk(root) { node ->
            if (AnrGuard.namesWolf(node.text, label)) found = true
        }
        return found
    }

    /** The fallback when a phone renames the ids. The text must still name WOLF first. */
    private fun findByText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!namesWolf(root)) return null
        var found: AccessibilityNodeInfo? = null
        NodeTools.walk(root) { node ->
            if (node.text?.toString()?.trim() in CLOSE_LABELS) found = node
        }
        return found
    }

    private fun wolfLabel(): String = runCatching {
        val manager = service.packageManager
        val info = manager.getApplicationInfo(WolfUi.PACKAGE, 0)
        manager.getApplicationLabel(info).toString()
    }.getOrDefault(FALLBACK_LABEL)

    private companion object {
        const val SYSTEM_PACKAGE = "android"
        const val CLOSE_ID = "android:id/aerr_close"
        const val WAIT_ID = "android:id/aerr_wait"
        const val FALLBACK_LABEL = "WOLF"
        val CLOSE_LABELS = listOf("Close app", "إغلاق التطبيق")
    }
}
