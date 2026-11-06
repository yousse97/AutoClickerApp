package com.autoclicker.chattask.core

enum class CheckStatus { PASS, FAIL, INFO }

enum class CheckItem { ACCESSIBILITY, OVERLAY, WOLF_OPEN, CHANNEL, ROOM, MESSAGE_BOX, SEND_BUTTON }

data class CheckResult(val item: CheckItem, val status: CheckStatus)

/**
 * "Check without sending". This takes facts already read from the screen and returns verdicts, so
 * it has no way to type, tap or send.
 */
object ReadOnlyCheck {
    fun evaluate(
        accessibilityOn: Boolean,
        overlayAllowed: Boolean,
        wolfInFront: Boolean,
        screen: WolfScreen,
        restrictToRoom: Boolean,
        room: String,
        sendButtonVisible: Boolean,
    ): List<CheckResult> = buildList {
        add(CheckResult(CheckItem.ACCESSIBILITY, verdict(accessibilityOn)))
        add(CheckResult(CheckItem.OVERLAY, verdict(overlayAllowed)))
        add(CheckResult(CheckItem.WOLF_OPEN, verdict(wolfInFront)))
        if (!wolfInFront) return@buildList
        add(CheckResult(CheckItem.CHANNEL, verdict(screen.isChannel)))
        if (restrictToRoom) add(CheckResult(CheckItem.ROOM, verdict(WolfUi.isRoom(screen.title, room))))
        add(CheckResult(CheckItem.MESSAGE_BOX, verdict(screen.hasInput)))
        // WOLF shows the send button only after text is typed, and this check never types.
        val send = if (sendButtonVisible) CheckStatus.PASS else CheckStatus.INFO
        add(CheckResult(CheckItem.SEND_BUTTON, send))
    }

    private fun verdict(passed: Boolean) = if (passed) CheckStatus.PASS else CheckStatus.FAIL
}
