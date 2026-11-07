package com.autoclicker.chattask.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autoclicker.chattask.core.WolfUi

/**
 * Hands the selected app's notifications to Auto reply. Every other app is dropped on the first
 * line. The text is compared in memory and then forgotten: it is never saved, logged or shown.
 */
class ReplyNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (posted.packageName != WolfUi.PACKAGE) return
        val notification = posted.notification ?: return
        val isSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (posted.isOngoing || isSummary) return
        AutoTaskService.instance?.onTargetNotification(textsOf(notification), posted.postTime, notification.`when`)
    }

    private fun textsOf(notification: Notification): List<CharSequence?> {
        val extras = notification.extras
        // A message with no person is the user's own, so it can never count as a trigger.
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?.messages?.filter { it.person != null }?.map { it.text }.orEmpty()
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList().orEmpty()
        val plain = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
            .map(extras::getCharSequence)
        return if (messages.isEmpty()) plain + lines else messages
    }

    companion object {
        fun isEnabled(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
