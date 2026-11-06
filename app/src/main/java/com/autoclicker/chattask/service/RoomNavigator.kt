package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.autoclicker.chattask.core.WolfScreen
import com.autoclicker.chattask.core.WolfUi
import com.autoclicker.chattask.data.EventLog
import kotlinx.coroutines.delay

enum class RecoveryResult {
    READY,
    ABORTED,
    NOT_LAUNCHABLE,
    NO_CHAT,
    TAB_NOT_FOUND,
    ROOM_NOT_FOUND,
    AMBIGUOUS_ROOM,
}

/**
 * Reads the WOLF screen, and reopens WOLF and goes to the chosen channel.
 *
 * The path is fixed: go back until the bottom bar shows, tap Channels, find the exact channel,
 * scroll only if needed, then open and verify it. Every element is found by its id, and every step
 * goes through [NodeTools.targetRoots], so nothing happens unless WOLF is the active window.
 */
class RoomNavigator(
    private val service: AccessibilityService,
    private val popups: PopupEngine,
) {
    fun readScreen(): WolfScreen {
        var hasInput = false
        var title: String? = null
        var hasMembers = false
        var isChannelList = false
        var hasBottomNav = false
        for (root in NodeTools.targetRoots(service, WolfUi.PACKAGE)) {
            NodeTools.walk(root) { node ->
                when (WolfUi.entryName(node.viewIdResourceName)) {
                    WolfUi.INPUT -> hasInput = hasInput || node.isVisibleToUser
                    WolfUi.TITLE -> title = node.text?.toString()
                    WolfUi.CHANNELS_SCREEN -> isChannelList = true
                    WolfUi.BOTTOM_NAV -> hasBottomNav = hasBottomNav || node.isVisibleToUser
                }
                if (node.contentDescription?.toString() == WolfUi.MEMBERS_BUTTON_LABEL) hasMembers = true
            }
        }
        return WolfScreen(hasInput, title, hasMembers, isChannelList, hasBottomNav)
    }

    fun isSendButtonVisible(): Boolean {
        var visible = false
        for (root in NodeTools.targetRoots(service, WolfUi.PACKAGE)) {
            NodeTools.walk(root) { node ->
                if (WolfUi.isSendButton(node.viewIdResourceName) && node.isVisibleToUser) visible = true
            }
        }
        return visible
    }

    /** Opens WOLF if another screen is in front, then goes to [room] unless it is blank. */
    suspend fun recover(room: String, keepGoing: () -> Boolean): RecoveryResult {
        if (NodeTools.activePackage(service) != WolfUi.PACKAGE && !launch()) {
            return RecoveryResult.NOT_LAUNCHABLE
        }
        var waited = 0L
        while (NodeTools.targetRoots(service, WolfUi.PACKAGE).isEmpty()) {
            if (!keepGoing()) return RecoveryResult.ABORTED
            if (waited >= WINDOW_TIMEOUT_MS) return RecoveryResult.NO_CHAT
            delay(POLL_MS)
            waited += POLL_MS
        }
        return if (room.isBlank()) RecoveryResult.READY else enterRoom(room.trim(), keepGoing)
    }

    private fun launch(): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(WolfUi.PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { service.startActivity(intent) }.isSuccess
    }

    private suspend fun enterRoom(room: String, keepGoing: () -> Boolean): RecoveryResult {
        var roomClicks = 0
        var scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var scrolls = 0
        var waited = 0L
        while (waited < NAVIGATION_TIMEOUT_MS) {
            // Leaving WOLF cancels the attempt. It is not a failure, and nothing outside is touched.
            val stillInWolf = NodeTools.activePackage(service) == WolfUi.PACKAGE
            if (!keepGoing() || !stillInWolf) return RecoveryResult.ABORTED
            popups.scan(full = true)
            val screen = readScreen()
            Log.i(
                EventLog.TAG,
                "nav channel=${screen.isChannel} input=${screen.hasInput} " +
                    "bar=${screen.hasBottomNav} list=${screen.isChannelList}",
            )
            when {
                screen.isChannel && WolfUi.isRoom(screen.title, room) -> return RecoveryResult.READY
                // Back is only pressed without the bottom bar. On the main screen it offers to log out.
                !screen.hasBottomNav -> goBack()
                !screen.isChannelList -> if (!clickChannelsTab()) return RecoveryResult.TAB_NOT_FOUND
                else -> {
                    val rows = matchingRows(room)
                    when {
                        rows.size > 1 -> return RecoveryResult.AMBIGUOUS_ROOM
                        rows.size == 1 && roomClicks >= MAX_ROOM_CLICKS -> return RecoveryResult.ROOM_NOT_FOUND
                        rows.size == 1 -> {
                            roomClicks++
                            NodeTools.click(service, rows.single())
                        }
                        scrolls >= MAX_SCROLLS -> return RecoveryResult.ROOM_NOT_FOUND
                        scrollList(scrollAction) -> scrolls++
                        scrollAction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ->
                            scrollAction = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else -> return RecoveryResult.ROOM_NOT_FOUND
                    }
                }
            }
            delay(STEP_MS)
            waited += STEP_MS
        }
        return RecoveryResult.ROOM_NOT_FOUND
    }

    private fun goBack() {
        if (!clickById(WolfUi.BACK_ARROW) && NodeTools.activePackage(service) == WolfUi.PACKAGE) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private fun clickById(id: String): Boolean {
        for (root in NodeTools.targetRoots(service, WolfUi.PACKAGE)) {
            var found: AccessibilityNodeInfo? = null
            NodeTools.walk(root) { node ->
                if (found == null && WolfUi.entryName(node.viewIdResourceName) == id) found = node
            }
            found?.let { return NodeTools.click(service, it) }
        }
        return false
    }

    private fun clickChannelsTab(): Boolean {
        for (root in NodeTools.targetRoots(service, WolfUi.PACKAGE)) {
            val tabs = ArrayList<AccessibilityNodeInfo>()
            NodeTools.walk(root) { node ->
                if (WolfUi.entryName(node.viewIdResourceName) == WolfUi.NAV_ITEM) tabs += node
            }
            tabs.getOrNull(WolfUi.CHANNELS_TAB_INDEX)?.let { return NodeTools.click(service, it) }
        }
        return false
    }

    /** Visible rows whose name is exactly [room]. More than one means the name is ambiguous. */
    private fun matchingRows(room: String): List<AccessibilityNodeInfo> {
        val matches = ArrayList<AccessibilityNodeInfo>()
        for (root in NodeTools.targetRoots(service, WolfUi.PACKAGE)) {
            NodeTools.walk(root) { node ->
                val isRow = WolfUi.entryName(node.viewIdResourceName) == WolfUi.CHANNEL_ROW
                if (isRow && node.isVisibleToUser && rowIsNamed(node, room)) matches += node
            }
        }
        return matches
    }

    /** Only the name is compared, so a member count or time can never match a numeric channel. */
    private fun rowIsNamed(row: AccessibilityNodeInfo, room: String): Boolean {
        var insideName: AccessibilityNodeInfo? = null
        NodeTools.walk(row) { node ->
            if (WolfUi.entryName(node.viewIdResourceName) == WolfUi.CHANNEL_ROW_NAME) insideName = node
        }
        val name = insideName ?: return false
        var found = false
        NodeTools.walk(name) { node ->
            if (WolfUi.isRoom(node.text?.toString(), room)) found = true
        }
        return found
    }

    /** Returns false once the list cannot move any further in that direction. */
    private fun scrollList(action: Int): Boolean {
        for (root in NodeTools.targetRoots(service, WolfUi.PACKAGE)) {
            var list: AccessibilityNodeInfo? = null
            NodeTools.walk(root) { node ->
                if (WolfUi.entryName(node.viewIdResourceName) == WolfUi.CHANNEL_LIST) list = node
            }
            list?.let { return it.performAction(action) }
        }
        return false
    }

    private companion object {
        const val POLL_MS = 500L
        const val STEP_MS = 1_500L
        const val WINDOW_TIMEOUT_MS = 25_000L
        const val NAVIGATION_TIMEOUT_MS = 60_000L
        const val MAX_ROOM_CLICKS = 2
        const val MAX_SCROLLS = 40
    }
}
