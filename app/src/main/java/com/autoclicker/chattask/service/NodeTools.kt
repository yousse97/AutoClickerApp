package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Helpers over the accessibility node tree.
 *
 * Nodes are not recycled on purpose: since API 33 recycle() is a no-op, and on older versions an
 * unrecycled node is simply garbage collected.
 */
object NodeTools {
    private const val SYSTEM_UI = "com.android.systemui"
    private const val MAX_WALK_NODES = 2_500
    private const val MAX_CLICK_ASCENT = 4
    private const val MAX_ANCESTORS = 40
    private const val TAP_DURATION_MS = 60L

    /** Root of each on-screen window of [pkg], top-most first. Empty if the app is not visible. */
    fun targetRoots(service: AccessibilityService, pkg: String): List<AccessibilityNodeInfo> {
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        val roots = windows.sortedByDescending { it.layer }
            .mapNotNull { runCatching { it.root }.getOrNull() }
            .filter { it.packageName?.toString() == pkg }
        if (roots.isNotEmpty()) return roots
        return listOfNotNull(service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg })
    }

    /** The app the user is looking at, ignoring this app's overlay and the system UI. */
    fun foregroundPackage(service: AccessibilityService): String? {
        val own = service.packageName
        val active = service.rootInActiveWindow?.packageName?.toString()
        if (active != null && active != own && active != SYSTEM_UI) return active
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        return windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.layer }
            .firstNotNullOfOrNull { window ->
                runCatching { window.root }.getOrNull()?.packageName?.toString()
                    ?.takeIf { it != own && it != SYSTEM_UI }
            }
    }

    /** Breadth-first walk with a node budget, so a huge tree cannot stall the worker thread. */
    inline fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var budget = walkBudget()
        while (queue.isNotEmpty() && budget-- > 0) {
            val node = queue.removeFirst()
            visit(node)
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
    }

    fun walkBudget(): Int = MAX_WALK_NODES

    fun boundsOf(node: AccessibilityNodeInfo): Rect = Rect().also(node::getBoundsInScreen)

    fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean = nearestClickable(node) != null

    /** True if the node sits in a list or grid, which is where chat messages live. */
    fun isInsideList(node: AccessibilityNodeInfo): Boolean {
        if (node.collectionItemInfo != null) return true
        var current = node.parent
        var depth = 0
        while (current != null && depth++ < MAX_ANCESTORS) {
            if (current.collectionInfo != null || current.collectionItemInfo != null) return true
            val className = current.className?.toString().orEmpty()
            if (className.endsWith("RecyclerView") || className.endsWith("ListView")) return true
            current = current.parent
        }
        return false
    }

    /** ACTION_CLICK on the closest clickable node, or a real tap if the app ignores the action. */
    fun click(service: AccessibilityService, node: AccessibilityNodeInfo): Boolean {
        val clickable = nearestClickable(node)
        if (clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        return tap(service, boundsOf(node))
    }

    private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth++ <= MAX_CLICK_ASCENT) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
        }
        return null
    }

    private fun tap(service: AccessibilityService, bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return service.dispatchGesture(gesture, null, null)
    }
}
