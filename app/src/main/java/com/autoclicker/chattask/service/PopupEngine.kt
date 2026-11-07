package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.chattask.core.ClickGuard
import com.autoclicker.chattask.core.KeywordSet
import com.autoclicker.chattask.core.ViewIdMatcher
import com.autoclicker.chattask.core.WolfUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Finds and clicks popup dismiss buttons inside the target app only.
 *
 * Two tiers keep it fast. Keyword lookups use findAccessibilityNodeInfosByText, which searches
 * inside the app process and returns quickly, so they run on every content change. The full tree
 * walk, which also finds icon-only close buttons by resource id, is limited to window changes and
 * a slower cadence, because TYPE_WINDOW_CONTENT_CHANGED can arrive dozens of times a second.
 */
class PopupEngine(
    private val service: AccessibilityService,
    private val onDismissed: () -> Unit,
) {
    private class Params(val target: String, val keywords: KeywordSet, val skipLists: Boolean)

    @Volatile private var params: Params? = null
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val windowChanged = AtomicBoolean(false)
    private var guard = ClickGuard()
    private var lastFullScanMs = 0L

    fun launchIn(scope: CoroutineScope, dispatcher: CoroutineDispatcher): Job =
        scope.launch(dispatcher) {
            for (ignored in signal) {
                val now = SystemClock.elapsedRealtime()
                val full = windowChanged.getAndSet(false) || now - lastFullScanMs >= FULL_SCAN_GAP_MS
                try {
                    scan(full)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ignored: RuntimeException) {
                    // A window can vanish in the middle of a query. The next event scans again.
                }
                delay(MIN_SCAN_GAP_MS)
            }
        }

    fun configure(target: String, keywords: List<String>, skipLists: Boolean) {
        params = Params(target, KeywordSet(keywords), skipLists)
    }

    fun disable() {
        params = null
    }

    fun onEvent(eventType: Int) {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) windowChanged.set(true)
        signal.trySend(Unit)
    }

    /** Clicks at most one button. Must run on the engine's worker thread. */
    fun scan(full: Boolean): Boolean {
        val active = params ?: return false
        val now = SystemClock.elapsedRealtime()
        if (full) lastFullScanMs = now
        for (root in NodeTools.targetRoots(service, active.target)) {
            if (isLogoutDialog(root)) return false
            val node = findByKeyword(root, active) ?: if (full) findByWalk(root, active) else null
            if (node == null || !guard.allow(signature(node), now)) continue
            if (NodeTools.click(service, node)) {
                onDismissed()
                return true
            }
        }
        return false
    }

    /** WOLF's logout dialog confirms with the word "تم", so it must never be dismissed. */
    private fun isLogoutDialog(root: AccessibilityNodeInfo): Boolean {
        val titles = root.findAccessibilityNodeInfosByViewId(WolfUi.DIALOG_TITLE_ID).orEmpty()
        return titles.any { WolfUi.isLogoutTitle(it.text) }
    }

    private fun findByKeyword(root: AccessibilityNodeInfo, active: Params): AccessibilityNodeInfo? {
        for (keyword in active.keywords.raw) {
            val hit = root.findAccessibilityNodeInfosByText(keyword)
                ?.firstOrNull { isDismissTarget(it, active) }
            if (hit != null) return hit
        }
        return null
    }

    private fun findByWalk(root: AccessibilityNodeInfo, active: Params): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        NodeTools.walk(root) { node ->
            if (found == null && isDismissTarget(node, active)) found = node
        }
        return found
    }

    private fun isDismissTarget(node: AccessibilityNodeInfo, active: Params): Boolean {
        if (!node.isVisibleToUser || !node.isEnabled || node.isEditable || node.isPassword) return false
        val hasMatchingLabel = active.keywords.matches(node.text) ||
            active.keywords.matches(node.contentDescription)
        val isCloseIcon = node.text.isNullOrBlank() && ViewIdMatcher.isClose(node.viewIdResourceName)
        if (!hasMatchingLabel && !isCloseIcon) return false
        val isButtonLike = node.className?.contains("Button") == true ||
            NodeTools.hasClickableAncestor(node)
        if (!isButtonLike) return false
        return !(active.skipLists && NodeTools.isInsideList(node))
    }

    private fun signature(node: AccessibilityNodeInfo): String =
        "${node.viewIdResourceName}|${node.text}|${node.contentDescription}|${NodeTools.boundsOf(node)}"

    private companion object {
        const val MIN_SCAN_GAP_MS = 300L
        const val FULL_SCAN_GAP_MS = 3_000L
    }
}
