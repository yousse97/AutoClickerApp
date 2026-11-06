package com.autoclicker.chattask.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.autoclicker.chattask.R
import com.autoclicker.chattask.data.LanguageManager
import com.autoclicker.chattask.ui.MainActivity

/** Notifications that tell the user why a session stopped or could not be restored. */
object Alerts {
    private const val CHANNEL_ID = "alerts"
    private const val NOTIFICATION_ID = 2

    fun post(context: Context, @StringRes message: Int, offerRetry: Boolean = false) {
        val texts = LanguageManager.localize(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                texts.getString(R.string.notification_alerts_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(texts.getString(R.string.notification_stopped_title))
            .setContentText(texts.getString(message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(texts.getString(message)))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags),
            )
        if (offerRetry) {
            val retry = Intent(context, OverlayService::class.java).setAction(OverlayService.ACTION_RETRY)
            builder.addAction(
                R.drawable.ic_play,
                texts.getString(R.string.notification_retry),
                PendingIntent.getForegroundService(context, 2, retry, flags),
            )
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }
}
