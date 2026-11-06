package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.chattask.core.KeywordSet
import com.autoclicker.chattask.core.RoomMatcher
import kotlinx.coroutines.delay

enum class RecoveryResult { READY, ABORTED, NOT_LAUNCHABLE, NO_CHAT, ROOM_NOT_FOUND }

/**
 * Brings the target app back after a crash or a rest period and returns to the chat.
 *
 * With a room identifier, it looks for the room on screen, then opens the bottom Channels tab and
 * My Channels, with Favorites as a fallback, scrolling through the list. A room is matched by
 * visible text, so the identifier must be what the app shows.
 */
class RoomNavigator(
    private val service: AccessibilityService,
    private val sender: CommandSender,
    private val popups: PopupEngine,
) {
    private val channelsLabels = KeywordSet(listOf("القنوات", "channels"))
    private val myChannelsLabels = KeywordSet(listOf("قنواتي الخاصة", "قنواتي", "my channels"))
    private val favoriteLabels = KeywordSet(
        listOf("المفضلة", "مفضلة", "favorites", "favourites", "favorite", "favourite"),
    )

    /** True if [room] is shown in the header area, which is where a chat names itself. */
    fun isInRoom(target: String, room: String): Boolean {
        val headerBottom = service.resources.displayMetrics.heightPixels * HEADER_HEIGHT_RATIO
        for (root in NodeTools.targetRoots(service, target)) {
            var found = false
            NodeTools.walk(root) { node ->
                val named = RoomMatcher.matches(node.text, room) ||
                    RoomMatcher.matches(node.contentDescription, room)
                if (!found && named && node.isVisibleToUser && !node.isEditable &&
                    NodeTools.boundsOf(node).top < headerBottom
                ) {
                    found = true
                }
            }
            if (found) return true
        }
        return false
    }

    suspend fun recover(
        target: String,
        room: String,
        requireRoom: Boolean,
        keepGoing: () -> Boolean,
    ): RecoveryResult {
        if (!launch(target)) return RecoveryResult.NOT_LAUNCHABLE
        var waited = 0L
        while (NodeTools.targetRoots(service, target).isEmpty()) {
            if (!keepGoing()) return RecoveryResult.ABORTED
            if (waited >= WINDOW_TIMEOUT_MS) return RecoveryResult.NO_CHAT
            delay(POLL_MS)
            waited += POLL_MS
        }
        return navigate(target, room.trim(), requireRoom, keepGoing)
    }

    private fun launch(target: String): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(target) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { service.startActivity(intent) }.isSuccess
    }

    private suspend fun navigate(
        target: String,
        room: String,
        requireRoom: Boolean,
        keepGoing: () -> Boolean,
    ): RecoveryResult {
        var roomClicks = 0
        var scrolls = 0
        var backPresses = 0
        var channelsOpened = false
        var myChannelsOpened = false
        var favoritesOpened = false
        var waited = 0L
        while (waited < NAVIGATION_TIMEOUT_MS) {
            if (!keepGoing()) return RecoveryResult.ABORTED
            popups.scan(full = true)
            if (isInChat(target)) {
                if (!requireRoom || isInRoom(target, room)) return RecoveryResult.READY
                if (backPresses++ < MAX_BACK_PRESSES) {
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }
            } else if (room.isNotEmpty()) {
                when {
                    roomClicks < MAX_ROOM_CLICKS && clickRoom(target, room) -> roomClicks++
                    !channelsOpened && clickChannelsTab(target) -> {
                        channelsOpened = true
                        scrolls = 0
                    }
                    channelsOpened && !myChannelsOpened && clickLabel(target, myChannelsLabels) -> {
                        myChannelsOpened = true
                        scrolls = 0
                    }
                    !channelsOpened && !favoritesOpened && clickLabel(target, favoriteLabels) -> {
                        favoritesOpened = true
                        scrolls = 0
                    }
                    scrolls < MAX_SCROLLS && scrollList(target) -> scrolls++
                }
            }
            delay(STEP_MS)
            waited += STEP_MS
        }
        return if (room.isEmpty()) RecoveryResult.NO_CHAT else RecoveryResult.ROOM_NOT_FOUND
    }

    /** A message box sits low on screen, which tells it apart from a search bar at the top. */
    private fun isInChat(target: String): Boolean {
        val input = sender.findInput(target) ?: return false
        val height = service.resources.displayMetrics.heightPixels
        return NodeTools.boundsOf(input).centerY() > height * MIN_INPUT_HEIGHT_RATIO
    }

    private fun clickRoom(target: String, room: String): Boolean = clickFirst(target) {
        RoomMatcher.matches(it.text, room) || RoomMatcher.matches(it.contentDescription, room)
    }

    private fun clickLabel(target: String, labels: KeywordSet): Boolean = clickFirst(target) {
        labels.matches(it.text) || labels.matches(it.contentDescription)
    }

    /** Limited to the bottom of the screen, as a chat could mention the word elsewhere. */
    private fun clickChannelsTab(target: String): Boolean {
        val tabBarTop = service.resources.displayMetrics.heightPixels * TAB_BAR_TOP_RATIO
        return clickFirst(target) {
            (channelsLabels.matches(it.text) || channelsLabels.matches(it.contentDescription)) &&
                NodeTools.boundsOf(it).centerY() > tabBarTop
        }
    }

    private fun clickFirst(target: String, wanted: (AccessibilityNodeInfo) -> Boolean): Boolean {
        for (root in NodeTools.targetRoots(service, target)) {
            var found: AccessibilityNodeInfo? = null
            NodeTools.walk(root) { node ->
                if (found == null && node.isVisibleToUser && !node.isEditable && wanted(node)) {
                    found = node
                }
            }
            found?.let { return NodeTools.click(service, it) }
        }
        return false
    }

    /** Scrolls the largest list on screen, and returns false once it has reached the end. */
    private fun scrollList(target: String): Boolean {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        for (root in NodeTools.targetRoots(service, target)) {
            NodeTools.walk(root) { node ->
                if (node.isScrollable && node.isVisibleToUser) {
                    val bounds = NodeTools.boundsOf(node)
                    val area = bounds.width() * bounds.height()
                    if (area > bestArea) {
                        best = node
                        bestArea = area
                    }
                }
            }
        }
        return best?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
    }

    private companion object {
        const val POLL_MS = 500L
        const val STEP_MS = 1_500L
        const val WINDOW_TIMEOUT_MS = 25_000L
        const val NAVIGATION_TIMEOUT_MS = 45_000L
        const val MAX_ROOM_CLICKS = 3
        const val MAX_SCROLLS = 12
        const val MAX_BACK_PRESSES = 3
        const val HEADER_HEIGHT_RATIO = 0.2
        const val TAB_BAR_TOP_RATIO = 0.75
        const val MIN_INPUT_HEIGHT_RATIO = 0.4
    }
}
