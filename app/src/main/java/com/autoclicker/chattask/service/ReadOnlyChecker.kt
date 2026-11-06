package com.autoclicker.chattask.service

import android.accessibilityservice.AccessibilityService
import android.provider.Settings
import com.autoclicker.chattask.core.CheckResult
import com.autoclicker.chattask.core.CheckStatus
import com.autoclicker.chattask.core.EventType
import com.autoclicker.chattask.core.ReadOnlyCheck
import com.autoclicker.chattask.core.TaskConfig
import com.autoclicker.chattask.core.WolfUi
import com.autoclicker.chattask.data.EventLog

/** "Check without sending". It is given no sender, so it has no way to type or send. */
object ReadOnlyChecker {
    fun run(service: AccessibilityService, navigator: RoomNavigator, config: TaskConfig): List<CheckResult> {
        val results = ReadOnlyCheck.evaluate(
            accessibilityOn = true,
            overlayAllowed = Settings.canDrawOverlays(service),
            wolfInFront = NodeTools.activePackage(service) == WolfUi.PACKAGE,
            screen = navigator.readScreen(),
            restrictToRoom = config.restrictToRoom,
            room = config.targetRoom,
            sendButtonVisible = navigator.isSendButtonVisible(),
        )
        val passed = results.none { it.status == CheckStatus.FAIL }
        EventLog.add(service, if (passed) EventType.CHECK_PASSED else EventType.CHECK_FAILED)
        return results
    }
}
