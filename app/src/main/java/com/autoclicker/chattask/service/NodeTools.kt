package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.PowerManager
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.chattask.core.Foreground
import com.autoclicker.chattask.core.WolfUi

/**
 * Helpers over the accessibility node tree.
 *
 * Nodes are not recycled on purpose: since API 33 recycle() is a no-op, and on older versions an
 * unrecycled node is simply garbage collected.
 */
object NodeTools {
    private const val MAX_WALK_NODES = 2_500
    private const val MAX_CLICK_ASCENT = 4
    private const val MAX_ANCESTORS = 40
    private const val TAP_DURATION_MS = 60L

    /**
     * Root of each on-screen window of [pkg], top-most first. Every read and action starts here,
     * and the list is empty unless the active window belongs to [pkg], so no other app is touched.
     */
    fun targetRoots(service: AccessibilityService, pkg: String): List<AccessibilityNodeInfo> {
        if (activePackage(service) != pkg) return emptyList()
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        val roots = windows.sortedByDescending { it.layer }
            .mapNotNull { runCatching { it.root }.getOrNull() }
            .filter { it.packageName?.toString() == pkg }
        if (roots.isNotEmpty()) return roots
        return listOfNotNull(service.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg })
    }

    /** The name of the app in front. Nothing else is read from a window outside WOLF. */
    fun activePackage(service: AccessibilityService): String? =
        runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()

    /** Where the user is. Only the name of the app in front is read to tell these apart. */
    fun foreground(service: AccessibilityService): Foreground {
        val power = service.getSystemService(PowerManager::class.java)
        val keyguard = service.getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) return Foreground.LOCKED
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return when (activePackage(service)) {
            WolfUi.PACKAGE -> Foreground.WOLF
            service.packageManager.resolveActivity(home, 0)?.activityInfo?.packageName -> Foreground.HOME
            else -> Foreground.OTHER_APP
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
